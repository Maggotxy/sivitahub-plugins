package cn.originalrpg.items.security;

import cn.originalrpg.items.forge.DestroyRules;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Stops vanilla durability from deleting authentic forged progression.
 *
 * <p>终态装备（C-008 的 SALVAGED / DESTROYED）不再属于玩家操作面：它们只可能
 * 在数据库权威路径（邮箱交付、审计快照）里被再次读到，因此任何来源的耐久伤害
 * 都必须被取消，不能把已进入终态的身份改回可使用的成品。</p>
 */
public final class EquipmentDurabilityListener implements Listener {
    private final EquipmentSecurity security;
    private final boolean enabled;
    private final NamespacedKey starterBaseOwner = NamespacedKey.fromString("rpgguide:starter_base_owner");
    private final NamespacedKey terminalKey = NamespacedKey.fromString("rpgitems:" + DestroyRules.TERMINAL_KEY);

    public EquipmentDurabilityListener(EquipmentSecurity security, boolean enabled) {
        this.security = security;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (DestroyRules.blocksFurtherDurabilityMutation(terminalState(event.getItem()))
                || !enabled || !(security.preserveDurability(event.getItem())
                || preserveStarterBase(event.getItem(), event.getPlayer().getUniqueId().toString()))) return;
        event.setDamage(0);
        event.setCancelled(true);
    }

    /** 终态墓碑只认本插件写入的子键；读不出或非终态一律交回原有保护逻辑。 */
    private String terminalState(ItemStack item) {
        if (terminalKey == null) return null;
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(terminalKey, PersistentDataType.STRING);
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
