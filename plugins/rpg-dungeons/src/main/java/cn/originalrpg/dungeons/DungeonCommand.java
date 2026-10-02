package cn.originalrpg.dungeons;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.DungeonEntryService;
import cn.originalrpg.api.model.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

final class DungeonCommand implements CommandExecutor, TabCompleter, Listener {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final DungeonEntryService entries;
    private final DungeonManager manager;
    private final MessageService messages;
    private final NamespacedKey returnLocation;
    private final NamespacedKey ticketId;
    private final NamespacedKey ticketEscrowMarker;
    private final Set<UUID> pendingEntries = ConcurrentHashMap.newKeySet();
    private final DungeonPartyService parties;
    private final SeasonExpeditionController seasonExpeditions;
    private static final int MAXIMUM_COMMIT_STATUS_ATTEMPTS = 5;
    private static final long[] COMMIT_STATUS_RETRY_TICKS = {10L, 20L, 40L, 80L};
    private static final int MAXIMUM_TICKET_ESCROW_ATTEMPTS = 5;

    DungeonCommand(
            Plugin plugin, ProfileService profiles, DungeonEntryService entries,
            DungeonManager manager, MessageService messages, DungeonPartyService parties,
            SeasonExpeditionController seasonExpeditions
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.entries = entries;
        this.manager = manager;
        this.messages = messages;
        returnLocation = new NamespacedKey(plugin, "return_location");
        ticketId = new NamespacedKey(plugin, "ticket_id");
        ticketEscrowMarker = new NamespacedKey(plugin, "ticket_entry_escrow");
        this.parties = java.util.Objects.requireNonNull(parties, "parties");
        this.seasonExpeditions = java.util.Objects.requireNonNull(seasonExpeditions, "seasonExpeditions");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("leave")) {
            leave(player);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("party")) {
            handleParty(player, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("season")) {
            handleSeason(player, args);
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("enter")) {
            return false;
        }
        enter(player, args[1], null);
        return true;
    }

    private void enter(Player player, String id, SeasonExpeditionRotation seasonRotation) {
        if (seasonRotation != null && (!seasonRotation.dungeonId().equals(id)
                || !seasonExpeditions.remainsActive(seasonRotation))) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.season.expired"),
                    NamedTextColor.YELLOW));
            return;
        }
        ConfigurationSection dungeon = plugin.getConfig().getConfigurationSection("dungeons." + id);
        if (dungeon == null || !dungeon.getBoolean("enabled")) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.unavailable"), NamedTextColor.YELLOW));
            return;
        }
        DungeonPartyService.EntryView party = parties.entry(player.getUniqueId());
        if (party.status() == DungeonPartyService.Status.NOT_LEADER) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.party.leader-entry-only", Map.of(
                    "leader", playerName(party.leader()))), NamedTextColor.RED));
            return;
        }
        int minimumPartySize = Math.clamp(dungeon.getInt("minimum-party-size", 1), 1, 8);
        int maximumPartySize = Math.clamp(dungeon.getInt("maximum-party-size", 4), minimumPartySize, 8);
        if (party.members().size() < minimumPartySize || party.members().size() > maximumPartySize) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.party.size-invalid", Map.of(
                    "minimum", minimumPartySize, "maximum", maximumPartySize)), NamedTextColor.RED));
            return;
        }
        List<Player> members = onlineMembers(party.members());
        if (members.size() != party.members().size()) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.party.member-offline"), NamedTextColor.RED));
            return;
        }
        double readyDistance = Math.max(4.0, plugin.getConfig().getDouble("party.ready-distance", 24.0));
        Player distant = members.stream().filter(member -> member.getWorld() != player.getWorld()
                        || member.getLocation().distanceSquared(player.getLocation()) > readyDistance * readyDistance)
                .findFirst().orElse(null);
        if (distant != null) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.party.member-distant", Map.of(
                    "player", distant.getName(), "distance", (int) readyDistance)), NamedTextColor.RED));
            return;
        }
        Player occupied = members.stream().filter(member -> member.getPersistentDataContainer()
                .has(returnLocation, PersistentDataType.STRING)).findFirst().orElse(null);
        if (occupied != null) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.party.member-inside", Map.of(
                    "player", occupied.getName())), NamedTextColor.RED));
            return;
        }
        LinkedHashSet<UUID> reserved = new LinkedHashSet<>();
        for (Player member : members) {
            if (!pendingEntries.add(member.getUniqueId())) {
                clearPending(reserved);
                player.sendMessage(Component.text(messages.text(player, "dungeon.party.member-pending", Map.of(
                        "player", member.getName())), NamedTextColor.YELLOW));
                return;
            }
            reserved.add(member.getUniqueId());
        }
        String requiredTicket = dungeon.getString("ticket-id", "");
        Player missingTicket = requiredTicket.isBlank() ? null : members.stream()
                .filter(member -> findTicket(member, requiredTicket) == null).findFirst().orElse(null);
        if (missingTicket != null) {
            clearPending(reserved);
            player.sendMessage(Component.text(messages.text(player, "dungeon.party.member-ticket", Map.of(
                    "player", missingTicket.getName())), NamedTextColor.RED));
            return;
        }
        int cost = Math.max(1, dungeon.getInt("energy-cost", 20));
        int minimumLevel = Math.max(1, dungeon.getInt("minimum-effective-level", 1));
        loadProfiles(reserved).whenComplete((loaded, error) -> sync(() -> {
            if (error != null) {
                clearPending(reserved);
                notifyMembers(members, "dungeon.settlement-failed", NamedTextColor.RED, Map.of());
                return;
            }
            UUID underLevel = reserved.stream().filter(memberId -> loaded.get(memberId).effectiveLevel() < minimumLevel)
                    .findFirst().orElse(null);
            if (underLevel != null) {
                clearPending(reserved);
                notifyMembers(members, "dungeon.party.member-level", NamedTextColor.RED, Map.of(
                        "player", playerName(underLevel), "level", minimumLevel));
                return;
            }
            reservePartyEnergy(new ArrayList<>(reserved), cost).whenComplete((settlement, spendError) -> sync(() -> {
                if (spendError != null) {
                    clearPending(reserved);
                    notifyMembers(members, "dungeon.settlement-failed", NamedTextColor.RED, Map.of());
                    return;
                }
                if (settlement.failedPlayer() != null) {
                    refundReservations(settlement.reservations(), "party-energy-rollback");
                    clearPending(reserved);
                    notifyMembers(members, settlement.databaseFailure()
                                    ? "dungeon.settlement-failed" : "dungeon.party.member-energy",
                            NamedTextColor.RED, settlement.databaseFailure() ? Map.of() : Map.of(
                                    "player", playerName(settlement.failedPlayer())));
                    return;
                }
                DungeonManager.InstanceRequestStatus status = manager.requestInstance(
                        id, Set.copyOf(reserved), seasonRotation, world -> {
                    if (world == null) {
                        refundReservations(settlement.reservations(), "instance-creation-failed");
                        clearPending(reserved);
                        notifyMembers(onlineMembers(reserved), "dungeon.instance-failed", NamedTextColor.RED, Map.of());
                        return;
                    }
                    teleportParty(player.getUniqueId(), id, dungeon, requiredTicket,
                            settlement.reservations(), reserved, world, seasonRotation);
                });
                if (status == DungeonManager.InstanceRequestStatus.QUEUED) {
                    notifyMembers(members, "dungeon.instance-preparing", NamedTextColor.YELLOW, Map.of());
                }
            }));
        }));
    }

    private CompletionStage<Map<UUID, PlayerProfile>> loadProfiles(Set<UUID> members) {
        Map<UUID, CompletableFuture<PlayerProfile>> futures = new java.util.HashMap<>();
        members.forEach(member -> futures.put(member, profiles.get(member).toCompletableFuture()));
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<UUID, PlayerProfile> loaded = new java.util.HashMap<>();
            futures.forEach((member, future) -> loaded.put(member, future.join()));
            return Map.copyOf(loaded);
        });
    }

    private CompletionStage<EnergySettlement> reservePartyEnergy(List<UUID> members, int cost) {
        CompletionStage<EnergySettlement> stage = CompletableFuture.completedFuture(
                new EnergySettlement(Map.of(), null, false));
        for (UUID member : members) {
            stage = stage.thenCompose(current -> {
                if (current.failedPlayer() != null) return CompletableFuture.completedFuture(current);
                UUID reservationId = UUID.randomUUID();
                return entries.reserve(reservationId, member, cost).handle((status, error) -> {
                    if (error != null) return new EnergySettlement(current.reservations(), member, true);
                    if (status != DungeonEntryService.ReservationStatus.SUCCESS) {
                        return new EnergySettlement(current.reservations(), member,
                                status != DungeonEntryService.ReservationStatus.INSUFFICIENT_ENERGY);
                    }
                    Map<UUID, UUID> updated = new java.util.LinkedHashMap<>(current.reservations());
                    updated.put(member, reservationId);
                    return new EnergySettlement(Map.copyOf(updated), null, false);
                });
            });
        }
        return stage;
    }

    private void teleportParty(
            UUID leaderId, String id, ConfigurationSection dungeon, String requiredTicket,
            Map<UUID, UUID> reservations, Set<UUID> reserved, World world,
            SeasonExpeditionRotation seasonRotation
    ) {
        List<Player> members = onlineMembers(reserved);
        DungeonPartyService.EntryView currentParty = parties.entry(leaderId);
        boolean partyUnchanged = currentParty.status() == DungeonPartyService.Status.SUCCESS
                && Set.copyOf(currentParty.members()).equals(Set.copyOf(reserved));
        boolean seasonActive = seasonRotation == null || seasonExpeditions.remainsActive(seasonRotation);
        if (!partyUnchanged || !seasonActive || members.size() != reserved.size() || (!requiredTicket.isBlank() && members.stream()
                .anyMatch(member -> findTicket(member, requiredTicket) == null))) {
            rollbackEntry(reservations, reserved, world, "party-changed-while-queued");
            notifyMembers(members, seasonActive ? "dungeon.party.changed-while-queued" : "dungeon.season.expired",
                    NamedTextColor.RED, Map.of());
            return;
        }
        Location entrance = new Location(world, dungeon.getDouble("x"), dungeon.getDouble("y"),
                dungeon.getDouble("z"), (float) dungeon.getDouble("yaw"), (float) dungeon.getDouble("pitch"));
        UUID entryHold = manager.holdEntrySettlement(world);
        if (entryHold == null) {
            rollbackEntry(reservations, reserved, world, "entry-session-changed");
            notifyMembers(members, "dungeon.instance-failed", NamedTextColor.RED, Map.of());
            return;
        }
        if (!requiredTicket.isBlank()) {
            prepareTicketEscrows(members, reservations, requiredTicket).whenComplete((result, error) -> sync(() -> {
                if (error != null || result == null || !result.allHeld()
                        || members.stream().anyMatch(member -> !currentSession(member))) {
                    reconcileFailedTicketEscrows(result);
                    rollbackEntry(reservations, reserved, world, "party-ticket-escrow-failed");
                    manager.releaseEntrySettlement(world, entryHold);
                    notifyMembers(onlineMembers(reserved), "dungeon.settlement-failed",
                            NamedTextColor.RED, Map.of());
                    return;
                }
                if (!consumeEscrowedTickets(result.candidates())) {
                    // Every durable row must first be reconciled with the
                    // marked inventory copy.  The following rollback can put
                    // the ticket in the mailbox immediately; leaving the
                    // marked copy in place until relog would both lock a slot
                    // and create an avoidable duplicate-risk window.
                    reconcileFailedTicketEscrows(result);
                    rollbackEntry(reservations, reserved, world, "party-ticket-inventory-changed");
                    manager.releaseEntrySettlement(world, entryHold);
                    notifyMembers(onlineMembers(reserved), "dungeon.ticket-changed",
                            NamedTextColor.RED, Map.of());
                    return;
                }
                beginPartyTeleport(leaderId, id, reservations, reserved, world, entrance, entryHold, members);
            }));
            return;
        }
        beginPartyTeleport(leaderId, id, reservations, reserved, world, entrance, entryHold, members);
    }

    private void beginPartyTeleport(
            UUID leaderId, String id, Map<UUID, UUID> reservations, Set<UUID> reserved,
            World world, Location entrance, UUID entryHold, List<Player> members
    ) {
        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            Player member = members.get(index);
            saveReturnLocation(member);
            Location destination = entrance.clone().add((index % 2) * 1.5, 0.0, (index / 2) * 1.5);
            teleports.add(member.teleportAsync(destination));
        }
        CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> sync(() -> {
            boolean successful = error == null && teleports.stream().allMatch(future -> Boolean.TRUE.equals(future.join()))
                    && members.stream().allMatch(member -> member.isOnline() && member.getWorld().equals(world));
            if (!successful) {
                rollbackEntry(reservations, reserved, world, "party-teleport-failed");
                manager.releaseEntrySettlement(world, entryHold);
                notifyMembers(onlineMembers(reserved), "dungeon.teleport-failed", NamedTextColor.RED, Map.of());
                return;
            }
            entries.commitAll(reservations).whenComplete((committed, commitError) -> sync(() -> {
                if (commitError == null && Boolean.TRUE.equals(committed)) {
                    finishCommittedEntry(leaderId, id, reservations, reserved,
                            world, entrance, entryHold);
                    return;
                }
                // A failed Future does not prove the database transaction rolled back: the
                // commit may have reached PostgreSQL while the response was lost. Keep the
                // instance assigned and resolve the durable reservation state before either
                // consuming tickets or refunding/returning the party.
                resolveAmbiguousCommit(leaderId, id, reservations, reserved,
                        world, entrance, entryHold, 1);
            }));
        }));
    }

    private void resolveAmbiguousCommit(
            UUID leaderId, String id,
            Map<UUID, UUID> reservations, Set<UUID> reserved,
            World world, Location entrance, UUID entryHold, int attempt
    ) {
        entries.partyCommitStatus(reservations).whenComplete((status, error) -> sync(() -> {
            if (error != null && attempt < MAXIMUM_COMMIT_STATUS_ATTEMPTS) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> resolveAmbiguousCommit(leaderId, id,
                                reservations, reserved, world, entrance, entryHold, attempt + 1),
                        COMMIT_STATUS_RETRY_TICKS[attempt - 1]);
                return;
            }
            if (error == null && status == DungeonEntryService.PartyCommitStatus.COMMITTED) {
                finishCommittedEntry(leaderId, id, reservations, reserved,
                        world, entrance, entryHold);
                return;
            }
            if (error == null && status == DungeonEntryService.PartyCommitStatus.RESERVED) {
                rollbackEntry(reservations, reserved, world, "party-energy-commit-not-applied");
                manager.releaseEntrySettlement(world, entryHold);
                notifyMembers(onlineMembers(reserved), "dungeon.settlement-failed",
                        NamedTextColor.RED, Map.of());
                return;
            }
            // INVALID or an exhausted database outage is deliberately quarantined.
            // Startup/orphan recovery owns any refundable RESERVED rows; an already
            // COMMITTED party is never double-refunded, and this instance cannot be
            // handed to another party while the players remain registered in it.
            clearPending(reserved);
            notifyMembers(onlineMembers(reserved), "dungeon.settlement-failed",
                    NamedTextColor.RED, Map.of());
            plugin.getLogger().severe("Dungeon party entry commit could not be resolved; reservations="
                    + reservations.values() + ", world=" + world.getName());
        }));
    }

    private void finishCommittedEntry(
            UUID leaderId, String id,
            Map<UUID, UUID> reservations, Set<UUID> reserved,
            World world, Location entrance, UUID entryHold
    ) {
        List<Player> currentMembers = onlineMembers(reserved).stream()
                .filter(member -> member.getWorld().equals(world)).toList();
        clearPending(reserved);
        manager.releaseEntrySettlement(world, entryHold);
        if (currentMembers.isEmpty()) return;
        Player leader = Bukkit.getPlayer(leaderId);
        manager.ensureBoss(id, world, entrance,
                leader != null && leader.isOnline() && leader.getWorld().equals(world)
                        ? leader : currentMembers.getFirst());
        notifyMembers(currentMembers, "dungeon.party.entered", NamedTextColor.GREEN, Map.of(
                "dungeon", messages.text(currentMembers.getFirst(), "dungeon.names." + id),
                "players", currentMembers.size()));
    }

    private void rollbackEntry(Map<UUID, UUID> reservations, Set<UUID> reserved, World world, String reason) {
        refundReservations(reservations, reason);
        clearPending(reserved);
        for (UUID memberId : reserved) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                String encoded = member.getPersistentDataContainer().get(returnLocation, PersistentDataType.STRING);
                if (encoded != null && member.getWorld().equals(world)) returnPlayer(member, encoded, false);
                else {
                    member.getPersistentDataContainer().remove(returnLocation);
                    member.saveData();
                    manager.releasePlayer(memberId, world);
                }
            } else {
                manager.releasePlayer(memberId, world);
            }
        }
    }

    private void clearPending(Iterable<UUID> members) {
        members.forEach(pendingEntries::remove);
    }

    private List<Player> onlineMembers(Iterable<UUID> memberIds) {
        List<Player> result = new ArrayList<>();
        for (UUID memberId : memberIds) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) result.add(member);
        }
        return result;
    }

    private void notifyMembers(List<Player> members, String key, NamedTextColor color, Map<String, ?> values) {
        members.forEach(member -> member.sendMessage(Component.text(messages.text(member, key, values), color)));
    }

    private void handleSeason(Player player, String[] args) {
        int partySize = Math.clamp(parties.entry(player.getUniqueId()).members().size(), 1, 4);
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            seasonExpeditions.showStatus(player, partySize);
            return;
        }
        if (args[1].equalsIgnoreCase("enter")) {
            java.util.Optional<SeasonExpeditionRotation> rotation = seasonExpeditions.current();
            if (rotation.isEmpty()) {
                player.sendMessage(Component.text(messages.text(player, "dungeon.season.locked"),
                        NamedTextColor.YELLOW));
                return;
            }
            enter(player, rotation.get().dungeonId(), rotation.get());
            return;
        }
        if (args[1].equalsIgnoreCase("top")) {
            int division = partySize;
            if (args.length >= 3) {
                try {
                    division = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                    division = 0;
                }
            }
            if (division < 1 || division > 4) {
                player.sendMessage(Component.text(messages.text(player, "dungeon.season.usage"),
                        NamedTextColor.YELLOW));
                return;
            }
            seasonExpeditions.showLeaderboard(player, division);
            return;
        }
        player.sendMessage(Component.text(messages.text(player, "dungeon.season.usage"), NamedTextColor.AQUA));
    }

    private void handleParty(Player player, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> sendPartyResult(player, parties.create(player.getUniqueId()), "created");
            case "invite" -> {
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null) {
                    player.sendMessage(Component.text(messages.text(player, "dungeon.party.player-unavailable"), NamedTextColor.RED));
                    return;
                }
                DungeonPartyService.Result result = parties.invite(player.getUniqueId(), target.getUniqueId());
                if (result.status() != DungeonPartyService.Status.SUCCESS) {
                    sendPartyResult(player, result, null);
                    return;
                }
                player.sendMessage(Component.text(messages.text(player, "dungeon.party.invited", Map.of(
                        "player", target.getName())), NamedTextColor.GREEN));
                target.sendMessage(Component.text(messages.text(target, "dungeon.party.invitation", Map.of(
                        "leader", player.getName())), NamedTextColor.AQUA));
            }
            case "accept" -> {
                Player leader = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (leader == null) {
                    player.sendMessage(Component.text(messages.text(player, "dungeon.party.player-unavailable"), NamedTextColor.RED));
                    return;
                }
                DungeonPartyService.Result result = parties.accept(player.getUniqueId(), leader.getUniqueId());
                if (result.status() != DungeonPartyService.Status.SUCCESS) {
                    sendPartyResult(player, result, null);
                    return;
                }
                notifyParty(result.party(), "dungeon.party.joined", NamedTextColor.GREEN, Map.of(
                        "player", player.getName()));
            }
            case "leave" -> {
                DungeonPartyService.PartyView before = parties.view(player.getUniqueId());
                DungeonPartyService.Result result = parties.leave(player.getUniqueId());
                if (result.status() != DungeonPartyService.Status.SUCCESS) {
                    sendPartyResult(player, result, null);
                    return;
                }
                player.sendMessage(Component.text(messages.text(player, "dungeon.party.left"), NamedTextColor.YELLOW));
                if (before != null) notifyMembers(onlineMembers(before.members()), "dungeon.party.member-left",
                        NamedTextColor.YELLOW, Map.of("player", player.getName()));
            }
            case "kick" -> {
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null) {
                    player.sendMessage(Component.text(messages.text(player, "dungeon.party.player-unavailable"), NamedTextColor.RED));
                    return;
                }
                DungeonPartyService.Result result = parties.kick(player.getUniqueId(), target.getUniqueId());
                if (result.status() != DungeonPartyService.Status.SUCCESS) {
                    sendPartyResult(player, result, null);
                    return;
                }
                target.sendMessage(Component.text(messages.text(target, "dungeon.party.kicked"), NamedTextColor.RED));
                notifyParty(result.party(), "dungeon.party.member-kicked", NamedTextColor.YELLOW, Map.of(
                        "player", target.getName()));
            }
            case "disband" -> {
                DungeonPartyService.PartyView before = parties.view(player.getUniqueId());
                DungeonPartyService.Result result = parties.disband(player.getUniqueId());
                if (result.status() != DungeonPartyService.Status.SUCCESS) {
                    sendPartyResult(player, result, null);
                    return;
                }
                if (before != null) notifyMembers(onlineMembers(before.members()), "dungeon.party.disbanded",
                        NamedTextColor.YELLOW, Map.of());
            }
            case "info" -> showParty(player);
            default -> player.sendMessage(Component.text(messages.text(player, "dungeon.party.usage"), NamedTextColor.AQUA));
        }
    }

    private void showParty(Player player) {
        DungeonPartyService.PartyView party = parties.view(player.getUniqueId());
        if (party == null) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.party.status.not_in_party"), NamedTextColor.YELLOW));
            return;
        }
        String names = party.members().stream().map(this::playerName).collect(java.util.stream.Collectors.joining(", "));
        player.sendMessage(Component.text(messages.text(player, "dungeon.party.info", Map.of(
                "leader", playerName(party.leader()), "members", names,
                "count", party.members().size(), "maximum", party.maximumSize())), NamedTextColor.AQUA));
    }

    private void sendPartyResult(Player player, DungeonPartyService.Result result, String successKey) {
        if (result.status() == DungeonPartyService.Status.SUCCESS && successKey != null) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.party." + successKey), NamedTextColor.GREEN));
            return;
        }
        String status = result.status().name().toLowerCase(Locale.ROOT);
        player.sendMessage(Component.text(messages.text(player, "dungeon.party.status." + status), NamedTextColor.RED));
    }

    private void notifyParty(
            DungeonPartyService.PartyView party, String key, NamedTextColor color, Map<String, ?> values
    ) {
        if (party != null) notifyMembers(onlineMembers(party.members()), key, color, values);
    }

    private String playerName(UUID playerId) {
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? playerId.toString().substring(0, 8) : name;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) return List.of("enter", "leave", "party", "season").stream()
                .filter(value -> value.startsWith(prefix)).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("enter")) {
            ConfigurationSection dungeons = plugin.getConfig().getConfigurationSection("dungeons");
            return dungeons == null ? List.of() : dungeons.getKeys(false).stream()
                    .filter(value -> value.startsWith(prefix)).sorted().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            return List.of("create", "invite", "accept", "leave", "kick", "disband", "info").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
            return List.of("status", "enter", "top").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("season")
                && args[1].equalsIgnoreCase("top")) {
            return List.of("1", "2", "3", "4").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("party")
                && (args[1].equalsIgnoreCase("invite") || args[1].equalsIgnoreCase("accept")
                || args[1].equalsIgnoreCase("kick"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private void leave(Player player) {
        String value = player.getPersistentDataContainer().get(returnLocation, PersistentDataType.STRING);
        if (value == null) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.no-return-point"), NamedTextColor.YELLOW));
            return;
        }
        returnPlayer(player, value, false);
    }

    private void saveReturnLocation(Player player) {
        Location location = player.getLocation();
        String encoded = location.getWorld().getUID() + ";" + location.getX() + ";" + location.getY() + ";"
                + location.getZ() + ";" + location.getYaw() + ";" + location.getPitch();
        player.getPersistentDataContainer().set(returnLocation, PersistentDataType.STRING, encoded);
        player.saveData();
    }

    private Location decodeLocation(String encoded) {
        try {
            String[] parts = encoded.split(";", -1);
            if (parts.length != 6) return null;
            World world = Bukkit.getWorld(java.util.UUID.fromString(parts[0]));
            if (world == null) return null;
            return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ItemStack findTicket(Player player, String requiredTicket) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getItemMeta() == null) continue;
            if (requiredTicket.equals(item.getItemMeta().getPersistentDataContainer().get(ticketId, PersistentDataType.STRING))) {
                return item;
            }
        }
        return null;
    }

    private CompletionStage<TicketEscrowPreparation> prepareTicketEscrows(
            List<Player> members, Map<UUID, UUID> reservations, String requiredTicket
    ) {
        List<TicketCandidate> candidates = new ArrayList<>();
        for (Player member : members) {
            ItemStack ticket = findTicket(member, requiredTicket);
            UUID reservationId = reservations.get(member.getUniqueId());
            if (ticket == null || reservationId == null) {
                return CompletableFuture.completedFuture(new TicketEscrowPreparation(List.copyOf(candidates), false));
            }
            ItemStack single = ticket.clone();
            single.setAmount(1);
            ItemMeta markerMeta = ticket.getItemMeta();
            markerMeta.getPersistentDataContainer().set(
                    ticketEscrowMarker, PersistentDataType.STRING, reservationId.toString());
            ticket.setItemMeta(markerMeta);
            member.saveData();
            candidates.add(new TicketCandidate(member, reservationId,
                    Base64.getEncoder().encodeToString(single.serializeAsBytes())));
        }
        CompletionStage<Boolean> stage = CompletableFuture.completedFuture(true);
        for (TicketCandidate candidate : candidates) {
            stage = stage.thenCompose(allHeld -> {
                if (!allHeld) return CompletableFuture.completedFuture(false);
                return escrowTicketWithRetry(candidate, requiredTicket, 1);
            });
        }
        return stage.handle((allHeld, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Dungeon ticket escrow preparation failed; every marked candidate "
                        + "will be reconciled before the entry reservation is refunded: " + error.getMessage());
            }
            // Never complete exceptionally after inventory markers have been
            // written.  The caller needs the complete candidate set in order
            // to reconcile a partially successful party escrow immediately.
            return new TicketEscrowPreparation(List.copyOf(candidates),
                    error == null && Boolean.TRUE.equals(allHeld));
        });
    }

    private CompletionStage<Boolean> escrowTicketWithRetry(
            TicketCandidate candidate, String requiredTicket, int attempt
    ) {
        return entries.escrowTicket(candidate.reservationId(), candidate.player().getUniqueId(),
                        requiredTicket, candidate.payload())
                .handle((held, error) -> {
                    if (error == null) return CompletableFuture.completedFuture(Boolean.TRUE.equals(held));
                    if (attempt >= MAXIMUM_TICKET_ESCROW_ATTEMPTS) {
                        return entries.ticketEscrow(candidate.reservationId(), candidate.player().getUniqueId())
                                .thenApply(escrow -> escrow.filter(value ->
                                        value.ticketId().equals(requiredTicket)
                                                && value.itemPayload().equals(candidate.payload())).isPresent());
                    }
                    return delayedTicketEscrowRetry(candidate, requiredTicket, attempt + 1);
                }).thenCompose(stage -> stage);
    }

    private CompletionStage<Boolean> delayedTicketEscrowRetry(
            TicketCandidate candidate, String requiredTicket, int attempt
    ) {
        CompletableFuture<Boolean> delayed = new CompletableFuture<>();
        long ticks = COMMIT_STATUS_RETRY_TICKS[Math.min(attempt - 2, COMMIT_STATUS_RETRY_TICKS.length - 1)];
        try {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> escrowTicketWithRetry(candidate, requiredTicket, attempt)
                            .whenComplete((held, error) -> {
                                if (error == null) delayed.complete(held);
                                else delayed.completeExceptionally(error);
                            }), ticks);
        } catch (IllegalStateException error) {
            delayed.completeExceptionally(error);
        }
        return delayed;
    }

    private boolean consumeEscrowedTickets(List<TicketCandidate> candidates) {
        Map<UUID, ItemStack> currentTickets = new java.util.LinkedHashMap<>();
        for (TicketCandidate candidate : candidates) {
            Player player = candidate.player();
            if (!currentSession(player)) return false;
            ItemStack current = markedTicket(player, candidate.reservationId());
            if (current == null) return false;
            currentTickets.put(candidate.reservationId(), current);
        }
        for (TicketCandidate candidate : candidates) {
            removeEscrowedTicket(candidate.player(), currentTickets.get(candidate.reservationId()),
                    candidate.reservationId());
        }
        return true;
    }

    private void reconcileFailedTicketEscrows(TicketEscrowPreparation result) {
        if (result == null) return;
        for (TicketCandidate candidate : result.candidates()) {
            reconcileTicketEscrow(candidate.player(), candidate.reservationId(),
                    candidate.payload(), 1, "entry-rollback");
        }
    }

    private void reconcileTicketEscrow(
            Player player, UUID reservationId, String expectedPayload, int attempt, String reason
    ) {
        entries.ticketEscrow(reservationId, player.getUniqueId()).whenComplete((held, error) -> {
            if (error != null) {
                if (attempt < MAXIMUM_TICKET_ESCROW_ATTEMPTS) {
                    scheduleTicketReconciliation(
                            player, reservationId, expectedPayload, attempt + 1, reason);
                } else {
                    plugin.getLogger().severe("Dungeon ticket reconciliation exhausted for player="
                            + player.getUniqueId() + " reservation=" + reservationId + " reason=" + reason
                            + "; the inventory marker remains locked for login recovery");
                }
                return;
            }
            sync(() -> {
                if (!currentSession(player)) return;
                ItemStack current = markedTicket(player, reservationId);
                if (current == null) return;
                if (held.isEmpty()) {
                    if (clearTicketEscrowMarker(current, reservationId)) player.saveData();
                    return;
                }
                if (expectedPayload != null && !expectedPayload.equals(held.get().itemPayload())) {
                    plugin.getLogger().severe("Dungeon ticket reconciliation found a payload mismatch for player="
                            + player.getUniqueId() + " reservation=" + reservationId + " reason=" + reason
                            + "; the inventory marker remains locked for administrator inspection");
                    return;
                }
                removeEscrowedTicket(player, current, reservationId);
            });
        });
    }

    private void scheduleTicketReconciliation(
            Player player, UUID reservationId, String expectedPayload, int attempt, String reason
    ) {
        long ticks = COMMIT_STATUS_RETRY_TICKS[Math.min(
                attempt - 2, COMMIT_STATUS_RETRY_TICKS.length - 1)];
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!plugin.isEnabled() || !currentSession(player)) return;
                reconcileTicketEscrow(player, reservationId, expectedPayload, attempt, reason);
            }, ticks);
        } catch (IllegalStateException ignored) {
            // Player data retains the marker.  A later authenticated login owns
            // the durable recovery when shutdown prevents scheduling a retry.
        }
    }

    private ItemStack markedTicket(Player player, UUID reservationId) {
        for (ItemStack candidate : player.getInventory().getContents()) {
            if (matchesTicketEscrowMarker(candidate, reservationId)) return candidate;
        }
        return null;
    }

    private boolean matchesTicketEscrowMarker(ItemStack item, UUID reservationId) {
        return item != null && item.getAmount() > 0 && item.getItemMeta() != null
                && reservationId.toString().equals(item.getItemMeta().getPersistentDataContainer()
                .get(ticketEscrowMarker, PersistentDataType.STRING));
    }

    private void removeEscrowedTicket(Player player, ItemStack item, UUID reservationId) {
        if (item == null || item.getAmount() < 1 || !clearTicketEscrowMarker(item, reservationId)) return;
        if (item.getAmount() == 1) item.setAmount(0);
        else item.setAmount(item.getAmount() - 1);
        player.saveData();
    }

    private boolean clearTicketEscrowMarker(ItemStack item, UUID reservationId) {
        if (item == null || item.getItemMeta() == null) return false;
        ItemMeta meta = item.getItemMeta();
        String value = meta.getPersistentDataContainer().get(ticketEscrowMarker, PersistentDataType.STRING);
        if (!reservationId.toString().equals(value)) return false;
        meta.getPersistentDataContainer().remove(ticketEscrowMarker);
        item.setItemMeta(meta);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketClick(InventoryClickEvent event) {
        ItemStack hotbar = event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player player
                ? player.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offhand = event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                && event.getWhoClicked() instanceof Player player
                ? player.getInventory().getItemInOffHand() : null;
        if (hasTicketEscrowMarker(event.getCurrentItem()) || hasTicketEscrowMarker(event.getCursor())
                || hasTicketEscrowMarker(hotbar) || hasTicketEscrowMarker(offhand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketDrag(InventoryDragEvent event) {
        if (hasTicketEscrowMarker(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketDrop(PlayerDropItemEvent event) {
        if (hasTicketEscrowMarker(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketUse(PlayerInteractEvent event) {
        if (hasTicketEscrowMarker(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketConsume(PlayerItemConsumeEvent event) {
        if (hasTicketEscrowMarker(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketOffhandSwap(PlayerSwapHandItemsEvent event) {
        if (hasTicketEscrowMarker(event.getMainHandItem())
                || hasTicketEscrowMarker(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketInventoryMove(InventoryMoveItemEvent event) {
        if (hasTicketEscrowMarker(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEscrowedTicketInventoryPickup(InventoryPickupItemEvent event) {
        if (hasTicketEscrowMarker(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onEscrowedTicketDeath(PlayerDeathEvent event) {
        var iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (!hasTicketEscrowMarker(item)) continue;
            iterator.remove();
            event.getItemsToKeep().add(item);
        }
    }

    private boolean hasTicketEscrowMarker(ItemStack item) {
        return item != null && item.getItemMeta() != null && item.getItemMeta()
                .getPersistentDataContainer().has(ticketEscrowMarker, PersistentDataType.STRING);
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        recoverTicketEscrowMarkers(player);
        String encoded = player.getPersistentDataContainer().get(returnLocation, PersistentDataType.STRING);
        if (encoded == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) returnPlayer(player, encoded, true);
        }, 1L);
    }

    private void recoverTicketEscrowMarkers(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (!hasTicketEscrowMarker(item)) continue;
            String value = item.getItemMeta().getPersistentDataContainer()
                    .get(ticketEscrowMarker, PersistentDataType.STRING);
            UUID reservationId;
            try {
                reservationId = UUID.fromString(value);
            } catch (IllegalArgumentException error) {
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().remove(ticketEscrowMarker);
                item.setItemMeta(meta);
                player.saveData();
                continue;
            }
            reconcileTicketEscrow(player, reservationId,
                    payloadWithoutEscrowMarker(item, reservationId), 1, "authenticated-login");
        }
    }

    private String payloadWithoutEscrowMarker(ItemStack item, UUID reservationId) {
        if (!matchesTicketEscrowMarker(item, reservationId)) return null;
        ItemStack single = item.clone();
        single.setAmount(1);
        if (!clearTicketEscrowMarker(single, reservationId)) return null;
        return Base64.getEncoder().encodeToString(single.serializeAsBytes());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        // The entry reservation protocol remains durable and owns this latch
        // until commit or rollback. Releasing it on /logout would allow the
        // same account to start a second reservation after authenticating
        // again while the first transaction is still in flight.
        parties.revoke(event.player().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String encoded = player.getPersistentDataContainer().get(returnLocation, PersistentDataType.STRING);
        if (encoded == null) return;
        Location destination = safeReturnLocation(encoded);
        World previousWorld = player.getWorld();
        event.setRespawnLocation(destination);
        player.getPersistentDataContainer().remove(returnLocation);
        player.saveData();
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> manager.releasePlayer(player.getUniqueId(), previousWorld), 1L);
        player.sendMessage(Component.text(messages.text(player, "dungeon.respawn-returned"), NamedTextColor.YELLOW));
    }

    private void returnPlayer(Player player, String encoded, boolean recovered) {
        Location destination = safeReturnLocation(encoded);
        World previousWorld = player.getWorld();
        player.teleportAsync(destination).whenComplete((success, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                if (currentSession(player)) player.sendMessage(Component.text(
                        messages.text(player, "dungeon.return-failed"), NamedTextColor.RED));
                return;
            }
            // An async teleport may complete after a fast reconnect. Never let
            // the stale Player handle clear/save the replacement login's return
            // point or release its current instance membership.
            if (!currentSession(player)) return;
            player.getPersistentDataContainer().remove(returnLocation);
            player.saveData();
            manager.releasePlayer(player.getUniqueId(), previousWorld);
            if (recovered) {
                player.sendMessage(Component.text(messages.text(player, "dungeon.session-recovered"), NamedTextColor.YELLOW));
            }
        }));
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private Location safeReturnLocation(String encoded) {
        Location decoded = decodeLocation(encoded);
        if (decoded != null) return decoded;
        plugin.getLogger().warning("Invalid or unavailable dungeon return location; using primary world spawn");
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private void refundReservations(Map<UUID, UUID> reservations, String reason) {
        reservations.forEach((playerId, reservationId) -> entries.refund(reservationId, playerId)
                .exceptionally(error -> {
                    plugin.getLogger().severe("Dungeon energy reservation refund failed for " + playerId
                            + " reservation=" + reservationId + " reason=" + reason + ": " + error.getMessage());
                    return 0;
                }));
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Plugin disable owns the durable reservation/journal recovery path.
            // Never fall back to a JDBC or teleport completion thread for Bukkit state.
        }
    }

    private record EnergySettlement(Map<UUID, UUID> reservations, UUID failedPlayer, boolean databaseFailure) {
    }

    private record TicketCandidate(Player player, UUID reservationId, String payload) { }

    private record TicketEscrowPreparation(List<TicketCandidate> candidates, boolean allHeld) { }
}
