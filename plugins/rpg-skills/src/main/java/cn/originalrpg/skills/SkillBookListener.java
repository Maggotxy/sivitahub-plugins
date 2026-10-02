package cn.originalrpg.skills;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.SkillBookRedemption;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LogoutEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class SkillBookListener implements Listener {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final MessageService messages;
    private final NamespacedKey skillBook = key("rpgskills:skill_book");
    private final NamespacedKey bookInstance = key("rpgskills:book_instance");
    private final Map<UUID, PendingBookUse> pendingPlayers = new ConcurrentHashMap<>();

    SkillBookListener(Plugin plugin, ProfileService profiles, MessageService messages) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack item = event.getItem();
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String id = data.get(skillBook, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!AuthMeApi.getInstance().isAuthenticated(player)) return;
        ConfigurationSection book = plugin.getConfig().getConfigurationSection("skill-books." + id);
        if (book == null || !SkillBookCatalog.IMPLEMENTED.contains(id)
                || !SkillBookCatalog.SKILL_POINTS.equals(
                        book.getString("effect", SkillBookCatalog.SKILL_POINTS))) {
            player.sendMessage(Component.text(messages.text(player, "skill-books.unknown"), NamedTextColor.RED));
            return;
        }
        PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
        if (profile == null) {
            profiles.get(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "skill-books.profile-loading"),
                    NamedTextColor.YELLOW));
            return;
        }
        int minimumEffectiveLevel = Math.max(1,
                book.getInt("requirements.minimum-effective-level", 1));
        if (profile.effectiveLevel() < minimumEffectiveLevel) {
            player.sendMessage(Component.text(messages.text(player, "skill-books.level-required", Map.of(
                    "level", minimumEffectiveLevel)), NamedTextColor.RED));
            return;
        }
        String instanceValue = data.get(bookInstance, PersistentDataType.STRING);
        UUID instanceId;
        try {
            instanceId = UUID.fromString(instanceValue == null ? "" : instanceValue);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(messages.text(player, "skill-books.invalid"), NamedTextColor.RED));
            return;
        }
        PendingBookUse pending = new PendingBookUse(player, instanceId);
        if (!beginPending(pending)) {
            player.sendMessage(Component.text(messages.text(player, "skill-books.pending"), NamedTextColor.YELLOW));
            return;
        }

        int minimum = Math.max(1, book.getInt("points.minimum", 1));
        int maximum = Math.max(minimum, book.getInt("points.maximum", minimum));
        int points = deterministicPoints(instanceId, minimum, maximum);
        String limitGroup = book.getString("limit.group");
        if (limitGroup != null && limitGroup.isBlank()) limitGroup = null;
        int maxUses = limitGroup == null ? 0 : Math.max(1, book.getInt("limit.maximum-uses", 5));
        profiles.redeemSkillBook(player.getUniqueId(), instanceId, id, points, limitGroup, maxUses)
                .whenComplete((result, error) -> sync(() -> {
                    if (!pendingPlayers.remove(player.getUniqueId(), pending) || !currentSession(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "skill-books.failed"), NamedTextColor.RED));
                        plugin.getLogger().warning("Skill-book redemption failed for "
                                + player.getUniqueId() + ": " + error.getMessage());
                        return;
                    }
                    if (result.status() == SkillBookRedemption.Status.LIMIT_REACHED) {
                        player.sendMessage(Component.text(messages.text(player, "skill-books.limit-reached", Map.of(
                                "uses", result.usesInLimitGroup(), "limit", maxUses)), NamedTextColor.YELLOW));
                        return;
                    }
                    if (result.status() == SkillBookRedemption.Status.POINT_LIMIT) {
                        player.sendMessage(Component.text(messages.text(player,
                                "skill-books.point-limit"), NamedTextColor.YELLOW));
                        return;
                    }
                    boolean consumed = consumeInstance(player, instanceId);
                    if (result.status() == SkillBookRedemption.Status.DUPLICATE) {
                        player.sendMessage(Component.text(messages.text(player, consumed
                                ? "skill-books.duplicate-consumed" : "skill-books.already-settled"), NamedTextColor.GRAY));
                        return;
                    }
                    player.sendMessage(Component.text(messages.text(player, consumed
                            ? "skill-books.success" : "skill-books.success-reconcile", Map.of(
                            "points", result.pointsGranted(), "total", result.profile().skillPoints())),
                            NamedTextColor.GREEN));
                }));
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearPending(event.player());
    }

    @EventHandler
    public void onLogout(LogoutEvent event) {
        clearPending(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPending(event.getPlayer());
    }

    private boolean beginPending(PendingBookUse pending) {
        UUID playerId = pending.player().getUniqueId();
        PendingBookUse active = pendingPlayers.putIfAbsent(playerId, pending);
        if (active == null) return true;
        if (active.player() == pending.player()) return false;
        return currentSession(pending.player()) && pendingPlayers.replace(playerId, active, pending);
    }

    private void clearPending(Player player) {
        PendingBookUse pending = pendingPlayers.get(player.getUniqueId());
        if (pending != null && pending.player() == player) {
            pendingPlayers.remove(player.getUniqueId(), pending);
        }
    }

    private static boolean currentSession(Player player) {
        if (player == null || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return false;
        try {
            return AuthMeApi.getInstance().isAuthenticated(player);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // The database redemption is idempotent. A book left in inventory
            // is reconciled as a duplicate on its next authenticated use.
        }
    }

    private boolean consumeInstance(Player player, UUID instanceId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack candidate = contents[slot];
            ItemMeta meta = candidate == null ? null : candidate.getItemMeta();
            if (meta == null) continue;
            String value = meta.getPersistentDataContainer().get(bookInstance, PersistentDataType.STRING);
            if (!instanceId.toString().equals(value)) continue;
            if (candidate.getAmount() <= 1) player.getInventory().setItem(slot, null);
            else candidate.setAmount(candidate.getAmount() - 1);
            return true;
        }
        return false;
    }

    private static int deterministicPoints(UUID instanceId, int minimum, int maximum) {
        int range = maximum - minimum + 1;
        long mixed = instanceId.getMostSignificantBits() ^ Long.rotateLeft(instanceId.getLeastSignificantBits(), 17);
        return minimum + Math.floorMod(mixed, range);
    }

    private static NamespacedKey key(String value) {
        return java.util.Objects.requireNonNull(NamespacedKey.fromString(value), value);
    }

    private record PendingBookUse(Player player, UUID instanceId) {}
}
