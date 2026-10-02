package cn.originalrpg.items.security;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Stops vanilla durability from deleting authentic forged progression. */
public final class EquipmentDurabilityListener implements Listener {
    private final EquipmentSecurity security;
    private final boolean enabled;
    private final NamespacedKey starterBaseOwner = NamespacedKey.fromString("rpgguide:starter_base_owner");

    public EquipmentDurabilityListener(EquipmentSecurity security, boolean enabled) {
        this.security = security;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (!enabled || !(security.preserveDurability(event.getItem())
                || preserveStarterBase(event.getItem(), event.getPlayer().getUniqueId().toString()))) return;
        event.setDamage(0);
        event.setCancelled(true);
    }

    private boolean preserveStarterBase(ItemStack item, String playerId) {
        if (starterBaseOwner == null) return false;
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null || !playerId.equals(meta.getPersistentDataContainer().get(
                starterBaseOwner, PersistentDataType.STRING))) return false;
        meta.setUnbreakable(true);
        if (meta instanceof Damageable damageable) damageable.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }
}
