package cn.originalrpg.api.i18n;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.event.PlayerLocaleChangeEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Refreshes only owned items in authenticated players' personal storage.
 * Slot events also cover asynchronous mailbox delivery, without polling or
 * mutating an open container, a forge input, or an unconfirmed market receipt.
 */
public final class PlayerItemTextRefresh implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Predicate<ItemStack> managed;
    private final Predicate<Player> personalView;
    private final BiFunction<Player, ItemStack, Boolean> render;
    private final Map<UUID, Player> sessions = new HashMap<>();
    private final Map<UUID, Player> pending = new HashMap<>();

    public PlayerItemTextRefresh(
            Plugin plugin, Predicate<ItemStack> managed, BiFunction<Player, ItemStack, Boolean> render
    ) {
        this(plugin, managed, render,
                player -> personalView(player.getOpenInventory().getTopInventory().getType().name()));
    }

    PlayerItemTextRefresh(Plugin plugin, Predicate<ItemStack> managed,
                          BiFunction<Player, ItemStack, Boolean> render, Predicate<Player> personalView) {
        this.plugin = plugin;
        this.managed = managed;
        this.render = render;
        this.personalView = personalView;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        sessions.put(player.getUniqueId(), player);
        pending.remove(player.getUniqueId());
        schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLocaleChanged(PlayerLocaleChangeEvent event) {
        schedule(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRevoked(AuthenticatedPlayerRevokedEvent event) {
        clear(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChanged(PlayerInventorySlotChangeEvent event) {
        if (managed.test(event.getNewItemStack())) schedule(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && managed.test(event.getItem().getItemStack())) {
            schedule(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? player.getInventory().getItem(event.getHotbarButton()) : null;
        if (managed.test(event.getCurrentItem()) || managed.test(event.getCursor()) || managed.test(hotbar)
                || (event.getClick() == ClickType.SWAP_OFFHAND
                    && managed.test(player.getInventory().getItemInOffHand()))) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && managed.test(event.getOldCursor())) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) schedule(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisabled(org.bukkit.event.server.PluginDisableEvent event) {
        if (event.getPlugin() == plugin) close();
    }

    @Override
    public void close() {
        sessions.clear();
        pending.clear();
    }

    private void clear(Player player) {
        sessions.remove(player.getUniqueId(), player);
        pending.remove(player.getUniqueId(), player);
    }

    private void schedule(Player player) {
        UUID id = player.getUniqueId();
        if (sessions.get(id) != player || !plugin.isEnabled() || pending.putIfAbsent(id, player) != null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // A callback from an old connection must not clear the new session's task.
            if (!pending.remove(id, player) || sessions.get(id) != player || !plugin.isEnabled()
                    || !player.isOnline() || plugin.getServer().getPlayer(id) != player) return;
            if (!personalView.test(player)) return;
            refresh(player, player.getInventory());
            refresh(player, player.getEnderChest());
            ItemStack cursor = player.getItemOnCursor();
            if (refresh(player, cursor)) player.setItemOnCursor(cursor);
        });
    }

    static boolean personalView(String type) {
        return "CRAFTING".equals(type) || "CREATIVE".equals(type) || "ENDER_CHEST".equals(type);
    }

    private void refresh(Player player, Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (refresh(player, item)) inventory.setItem(slot, item);
        }
    }

    private boolean refresh(Player player, ItemStack item) {
        return managed.test(item) && ItemTextPresentation.mayRefresh(item.getItemMeta()) && render.apply(player, item);
    }
}
