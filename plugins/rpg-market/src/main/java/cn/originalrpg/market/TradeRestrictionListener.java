package cn.originalrpg.market;

import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

final class TradeRestrictionListener implements Listener {
    private final EquipmentService equipment;
    private final MessageService messages;
    private final NamespacedKey equipmentOwner = NamespacedKey.fromString("rpgitems:owner");
    private final NamespacedKey commerceOwner = NamespacedKey.fromString("rpgcommerce:owner");
    private final NamespacedKey materialOwner = NamespacedKey.fromString("rpgitems:material_owner");
    private final NamespacedKey sulfurOwner = NamespacedKey.fromString("rpgsulfur:owner");
    private final NamespacedKey adaptiveMaterial = NamespacedKey.fromString("rpgmobs:adaptive_material");
    private final NamespacedKey huntMaterial = NamespacedKey.fromString("rpgworld:hunt_material");
    private final NamespacedKey bossMaterial = NamespacedKey.fromString("rpgdungeons:boss_material");
    private final NamespacedKey dungeonTicket = NamespacedKey.fromString("rpgdungeons:ticket_id");
    private final NamespacedKey skillBook = NamespacedKey.fromString("rpgskills:skill_book");
    private final NamespacedKey pickupOwner = NamespacedKey.fromString("rpgmarket:pickup_owner");
    private final NamespacedKey pendingDelivery = NamespacedKey.fromString("rpgmarket:delivery_id");
    private final NamespacedKey guideHandbook = NamespacedKey.fromString(SystemItemPolicy.GUIDE_HANDBOOK);
    private final NamespacedKey companionSignalOwner = NamespacedKey.fromString(
            SystemItemPolicy.COMPANION_SIGNAL_OWNER);

    TradeRestrictionListener(EquipmentService equipment, MessageService messages) {
        this.equipment = equipment;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestricted(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(messages.text(event.getPlayer(), "market.restricted-drop"), NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            if (isRestricted(event.getItem().getItemStack())) event.setCancelled(true);
            return;
        }
        Item itemEntity = event.getItem();
        ItemMeta meta = itemEntity.getItemStack().getItemMeta();
        if (meta == null) return;
        String pickupOwnerId = pickupOwner == null ? null
                : meta.getPersistentDataContainer().get(pickupOwner, PersistentDataType.STRING);
        if (pickupOwnerId != null) {
            try {
                if (!UUID.fromString(pickupOwnerId).equals(player.getUniqueId())) {
                    event.setCancelled(true);
                    return;
                }
            } catch (IllegalArgumentException exception) {
                event.setCancelled(true);
                return;
            }
            meta.getPersistentDataContainer().remove(pickupOwner);
            itemEntity.getItemStack().setItemMeta(meta);
        }
        String ownerId = owner(meta);
        if (ownerId != null) {
            try {
                if (!UUID.fromString(ownerId).equals(player.getUniqueId())) event.setCancelled(true);
            } catch (IllegalArgumentException exception) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isTrustedInventory(event.getView().getTopInventory().getHolder())) return;
        int topSize = event.getView().getTopInventory().getSize();
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? player.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offhand = event.getClick() == ClickType.SWAP_OFFHAND
                ? player.getInventory().getItemInOffHand() : null;
        ContainerTransferPolicy.Decision decision = ContainerTransferPolicy.decide(
                event.getRawSlot(), topSize, event.getAction().name(), event.isShiftClick(),
                event.getClick() == ClickType.SWAP_OFFHAND,
                isRestricted(event.getCurrentItem()), isRestricted(event.getCursor()),
                isRestricted(hotbar), isRestricted(offhand));
        if (decision.blocked()) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(messages.text(player,
                    decision == ContainerTransferPolicy.Decision.BLOCK_DROP
                            ? "market.restricted-drop" : "market.restricted-container"), NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isRestricted(event.getOldCursor())) return;
        if (isTrustedInventory(event.getView().getTopInventory().getHolder())) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(messages.text(player, "market.restricted-container-short"), NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (isRestricted(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isRestricted(event.getItem())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        var iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (!keepOnDeath(event.getEntity(), item)) continue;
            iterator.remove();
            event.getItemsToKeep().add(item);
        }
    }

    /**
     * Trading restrictions are intentionally broader than death protection.
     * An unbound forged item cannot be thrown into an arbitrary hopper, but it
     * still drops on death; only assets cryptographically/PDC-bound to the
     * dying account survive.
     */
    private boolean keepOnDeath(Player player, ItemStack item) {
        if (DeathProtectionPolicy.ownsEquipment(player.getUniqueId(), equipment.inspect(item))) return true;
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return false;
        var data = meta.getPersistentDataContainer();
        return DeathProtectionPolicy.ownsMarker(player.getUniqueId(),
                value(data, commerceOwner), value(data, materialOwner), value(data, sulfurOwner),
                value(data, pickupOwner), value(data, guideHandbook), value(data, companionSignalOwner));
    }

    private boolean isCustom(ItemStack item) {
        return equipment.isCustomEquipment(item);
    }

    private boolean isRestricted(ItemStack item) {
        return NestedItemPolicy.contains(item, this::isDirectlyRestricted);
    }

    private boolean isDirectlyRestricted(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (isCustom(item) || meta == null) return isCustom(item);
        var data = meta.getPersistentDataContainer();
        return has(data, commerceOwner, PersistentDataType.STRING)
                || has(data, materialOwner, PersistentDataType.STRING)
                || has(data, sulfurOwner, PersistentDataType.STRING)
                || has(data, adaptiveMaterial, PersistentDataType.BYTE)
                || has(data, huntMaterial, PersistentDataType.INTEGER)
                || has(data, bossMaterial, PersistentDataType.STRING)
                || has(data, dungeonTicket, PersistentDataType.STRING)
                || has(data, skillBook, PersistentDataType.STRING)
                || has(data, pickupOwner, PersistentDataType.STRING)
                || has(data, pendingDelivery, PersistentDataType.STRING)
                || data.getKeys().stream().map(NamespacedKey::toString)
                        .anyMatch(SystemItemPolicy::isNonTransferableMarker);
    }

    private static <P, C> boolean has(
            org.bukkit.persistence.PersistentDataContainer data,
            NamespacedKey key,
            PersistentDataType<P, C> type
    ) {
        return key != null && data.has(key, type);
    }

    private static String value(
            org.bukkit.persistence.PersistentDataContainer data, NamespacedKey key
    ) {
        return key == null ? null : data.get(key, PersistentDataType.STRING);
    }

    private String owner(ItemMeta meta) {
        if (equipmentOwner != null) {
            String value = meta.getPersistentDataContainer().get(equipmentOwner, PersistentDataType.STRING);
            if (value != null) return value;
        }
        if (commerceOwner != null) {
            String value = meta.getPersistentDataContainer().get(commerceOwner, PersistentDataType.STRING);
            if (value != null) return value;
        }
        if (materialOwner != null) {
            String value = meta.getPersistentDataContainer().get(materialOwner, PersistentDataType.STRING);
            if (value != null) return value;
        }
        if (sulfurOwner != null) {
            String value = meta.getPersistentDataContainer().get(sulfurOwner, PersistentDataType.STRING);
            if (value != null) return value;
        }
        if (guideHandbook != null) {
            String value = meta.getPersistentDataContainer().get(guideHandbook, PersistentDataType.STRING);
            if (value != null) return value;
        }
        return companionSignalOwner == null ? null
                : meta.getPersistentDataContainer().get(companionSignalOwner, PersistentDataType.STRING);
    }

    /**
     * Menu GUIs route clicks themselves, so the transfer policy must not fight
     * them. Trust is an explicit opt-in ({@link TrustedMenuHolder}), never a
     * namespace guess: an unmarked custom GUI keeps the full restrictions.
     */
    private boolean isTrustedInventory(org.bukkit.inventory.InventoryHolder holder) {
        return holder instanceof Player || holder instanceof TrustedMenuHolder;
    }
}
