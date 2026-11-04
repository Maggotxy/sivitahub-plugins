package cn.originalrpg.community;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CommunityStorageRequest;
import cn.originalrpg.api.model.CommunityStorageResult;
import cn.originalrpg.api.model.CommunityStorageSnapshot;
import cn.originalrpg.api.rules.CommunityStorageRules;
import cn.originalrpg.api.service.CommunityStorageService;
import cn.originalrpg.api.service.CommunityStorageSessionService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Authenticated UI adapter for the single Core custody authority; all Bukkit access stays on main. */
final class CommunityStorageController implements CommandExecutor, TabCompleter, Listener {
    private static final List<String> ACTIONS = List.of("list", "deposit", "withdraw", "pending", "approve", "cancel", "materials", "help");
    private final Plugin plugin;
    private final Server server;
    private final BukkitScheduler scheduler;
    private final Logger logger;
    private final CommunityStorageService service;
    private final MessageService messages;
    private final BooleanSupplier localEnabled;
    private final Supplier<CommunityStorageSessionService> bridge;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean stopped;

    CommunityStorageController(Plugin plugin, CommunityStorageService service, MessageService messages,
                               BooleanSupplier localEnabled, Supplier<CommunityStorageSessionService> bridge) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.scheduler = server.getScheduler();
        this.logger = plugin.getLogger();
        this.service = service;
        this.messages = messages;
        this.localEnabled = localEnabled;
        this.bridge = bridge;
    }

    void clear() {
        stopped = true;
        for (Session session : sessions.values()) closeMenu(session);
        sessions.clear();
    }

    @EventHandler public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Session previous = sessions.put(event.player().getUniqueId(), new Session(event.player()));
        if (previous != null) closeMenu(previous);
    }

    @EventHandler public void onRevoked(AuthenticatedPlayerRevokedEvent event) { remove(event.player()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { remove(event.getPlayer()); }

    private void remove(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.player == player && sessions.remove(player.getUniqueId(), session)) closeMenu(session);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        Session session = sessions.get(player.getUniqueId());
        if (!current(session) || session.player != player) return true;
        if (service == null || !service.enabled() || !localEnabled.getAsBoolean()) {
            send(player, "community-storage.disabled", Map.of());
            return true;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        UUID actorId = player.getUniqueId();
        switch (action) {
            case "help" -> help(player);
            case "materials" -> materials(player);
            case "list" -> {
                if (args.length > 2) { help(player); break; }
                int page = page(args);
                if (page < 0) { help(player); break; }
                Inventory expected = player.getOpenInventory().getTopInventory();
                run(session, () -> service.snapshot(actorId), (target, snapshot) -> {
                    if (snapshot.isEmpty()) { send(target, "community-storage.result.not-member", Map.of()); return; }
                    if (target.getOpenInventory().getTopInventory() != expected) return;
                    CommunityStorageSnapshot view = snapshot.orElseThrow();
                    send(target, "community-storage.capacity", Map.of("used", view.supplies().size(), "maximum", view.capacity()));
                    session.menu = new CommunityStorageMenu(server, target, messages, view, page);
                    target.openInventory(session.menu.getInventory());
                });
            }
            case "deposit" -> {
                if (args.length != 1) { help(player); break; }
                if (session.busy) { send(player, "community.operation-pending", Map.of()); break; }
                CommunityStorageSessionService adapter = bridge.get();
                if (adapter == null) send(player, "community-storage.adapter-unavailable", Map.of());
                else adapter.deposit(player);
            }
            case "pending" -> {
                if (args.length > 2 || page(args) < 0) { help(player); break; }
                int page = page(args);
                run(session, () -> service.snapshot(actorId), (target, snapshot) -> showPending(target, snapshot, page));
            }
            case "withdraw" -> {
                if (args.length < 2 || args.length > 3) { help(player); break; }
                UUID listingId = id(args[1]);
                UUID recipientId = args.length == 3 ? recipient(args[2]) : actorId;
                if (listingId == null || recipientId == null) {
                    send(player, "community-storage.invalid-target", Map.of()); break;
                }
                String key = "storage-request:" + UUID.randomUUID();
                run(session, () -> service.requestWithdrawal(actorId, listingId, recipientId, key), this::showResult);
            }
            case "approve", "cancel" -> {
                UUID requestId = args.length == 2 ? id(args[1]) : null;
                if (requestId == null) { help(player); break; }
                run(session, () -> action.equals("approve") ? service.approveWithdrawal(actorId, requestId)
                        : service.cancelWithdrawal(actorId, requestId), this::showResult);
            }
            default -> help(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return ACTIONS.stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("withdraw")) {
            return server.getOnlinePlayers().stream().map(Player::getName)
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CommunityStorageMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.viewer().equals(player.getUniqueId())) return;
        Session session = sessions.get(player.getUniqueId());
        if (!current(session) || session.menu != menu) return;
        String command = menu.actionAt(event.getRawSlot());
        if (command == null) return;
        scheduler.runTask(plugin, () -> {
            if (!current(session) || session.menu != menu || player.getOpenInventory().getTopInventory() != menu.getInventory()) return;
            player.closeInventory();
            player.performCommand(command);
        });
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CommunityStorageMenu) event.setCancelled(true);
    }

    private <T> void run(Session session, Supplier<CompletionStage<T>> operation, Renderer<T> renderer) {
        if (session.busy) { send(session.player, "community.operation-pending", Map.of()); return; }
        session.busy = true;
        CompletionStage<T> pending;
        try { pending = operation.get(); }
        catch (RuntimeException failure) {
            session.busy = false;
            fail(session.player, failure);
            return;
        }
        pending.whenComplete((value, error) -> onMain(() -> {
            session.busy = false;
            if (!current(session)) return;
            if (error != null) fail(session.player, error);
            else renderer.show(session.player, value);
        }));
    }

    private void showResult(Player player, CommunityStorageResult result) {
        send(player, "community-storage.result." + result.status().name().toLowerCase(Locale.ROOT).replace('_', '-'), Map.of());
        result.request().ifPresent(request -> showRequest(player, request));
        if (result.status() == CommunityStorageResult.Status.DELIVERED) {
            result.request().ifPresent(request -> {
                Player recipient = server.getPlayer(request.recipientId());
                Session recipientSession = sessions.get(request.recipientId());
                if (recipient != null && current(recipientSession)) {
                    recipient.sendMessage(Component.text(messages.text(recipient, "community-storage.mailbox-link"), NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/market claim")));
                }
            });
        }
    }

    private void showPending(Player player, Optional<CommunityStorageSnapshot> snapshot, int requestedPage) {
        if (snapshot.isEmpty()) { send(player, "community-storage.result.not-member", Map.of()); return; }
        List<CommunityStorageRequest> requests = snapshot.orElseThrow().requests();
        if (requests.isEmpty()) { send(player, "community-storage.no-pending", Map.of()); return; }
        int page = Math.clamp(requestedPage, 0, (requests.size() - 1) / 8);
        for (int index = page * 8; index < Math.min(page * 8 + 8, requests.size()); index++) showRequest(player, requests.get(index));
        if ((page + 1) * 8 < requests.size()) {
            player.sendMessage(Component.text(messages.text(player, "community-storage.button.next"), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/communitystorage pending " + (page + 2))));
        }
    }

    private void showRequest(Player player, CommunityStorageRequest request) {
        send(player, "community-storage.request", Map.of("id", request.id(), "listing", request.listingId(),
                "recipient", request.recipientId(), "signatures", request.approvals().size(),
                "state", messages.text(player, "community-storage.request-state." + request.state().name().toLowerCase(Locale.ROOT))));
        if (request.state() != CommunityStorageRequest.State.PENDING) return;
        for (String action : List.of("approve", "cancel")) {
            player.sendMessage(Component.text("[" + messages.text(player, "community-storage.button." + action) + "]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand("/communitystorage " + action + " " + request.id())));
        }
    }

    private UUID recipient(String value) {
        UUID id = id(value);
        if (id != null) return id;
        Player online = server.getPlayerExact(value);
        if (online != null) return online.getUniqueId();
        var cached = server.getOfflinePlayerIfCached(value);
        return cached == null ? null : cached.getUniqueId();
    }

    private void materials(Player player) {
        send(player, "community-storage.material-rule", Map.of());
        List<String> allowed = CommunityStorageRules.materials().stream().sorted().toList();
        for (int start = 0; start < allowed.size(); start += 5) {
            Component line = Component.empty();
            for (int index = start; index < Math.min(start + 5, allowed.size()); index++) {
                if (index > start) line = line.append(Component.text(", "));
                line = line.append(Component.translatable("block." + allowed.get(index).replace(':', '.')));
            }
            player.sendMessage(line.color(NamedTextColor.GRAY));
        }
    }

    private void help(Player player) {
        messages.lines(player, "community-storage.help").forEach(line -> player.sendMessage(Component.text(line, NamedTextColor.GRAY)));
    }

    private void closeMenu(Session session) {
        if (session.menu != null && session.player.getOpenInventory().getTopInventory() == session.menu.getInventory()) {
            session.player.closeInventory();
        }
    }

    private boolean current(Session session) {
        return !stopped && session != null && sessions.get(session.player.getUniqueId()) == session
                && server.getPlayer(session.player.getUniqueId()) == session.player && session.player.isOnline();
    }

    private void fail(Player player, Throwable error) {
        logger.warning("Community storage operation failed: " + error.getMessage());
        send(player, "community.error", Map.of());
    }

    private void send(Player player, String key, Map<String, ?> values) {
        player.sendMessage(Component.text(messages.text(player, key, values), NamedTextColor.GRAY));
    }

    private void onMain(Runnable action) {
        try { scheduler.runTask(plugin, action); }
        catch (IllegalPluginAccessException ignored) { /* The durable transaction outlives a closed UI session. */ }
    }

    private static int page(String[] args) {
        if (args.length < 2) return 0;
        try { int value = Integer.parseInt(args[1]); return value >= 1 && value <= 1000 ? value - 1 : -1; }
        catch (NumberFormatException invalid) { return -1; }
    }
    private static UUID id(String text) {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    @FunctionalInterface private interface Renderer<T> { void show(Player player, T value); }
    private static final class Session {
        private final Player player;
        private boolean busy;
        private CommunityStorageMenu menu;
        private Session(Player player) { this.player = player; }
    }
}
