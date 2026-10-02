package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentLedgerAuditService;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.Optional;

/**
 * C-008 终态守卫：已进入终态的装备不能靠死亡掉落重新回到世界。
 *
 * <p>{@code rpg-market} 的 {@code TradeRestrictionListener} 按「绑定归属」决定
 * 死亡时是否保留装备；终态装备在 PDC 里本来就没有持有者，因此会掉回地面被他人
 * 捡走。本监听器只处理「账本已写终态」这一种情况：先读随交付一起写入的墓碑子键，
 * 读不到才回落到数据库权威行（{@code /destroy} 的后台兜底动作只更新数据库、
 * 碰不到玩家数据）。数据库不可用时不猜测、不误伤，交回原有掉落逻辑。</p>
 *
 * <p>同一守卫也必须覆盖 {@code /drop}：丢在地上同样是「让已终结的身份回到世界」，
 * 而且比死亡掉落更容易被反复触发。两条路径共用 {@link #terminal(ItemStack)}
 * 判定，避免出现「死亡拦得住、丢弃拦不住」的第二个出口。</p>
 */
public final class EquipmentTerminalStateListener implements Listener {
    private final Plugin plugin;
    private final EquipmentService equipment;
    private final EquipmentLedgerAuditService ledger;
    private final NamespacedKey terminalKey;

    public EquipmentTerminalStateListener(
            Plugin plugin, EquipmentService equipment, EquipmentLedgerAuditService ledger) {
        this.plugin = plugin;
        this.equipment = equipment;
        this.ledger = ledger;
        terminalKey = new NamespacedKey(plugin, DestroyRules.TERMINAL_KEY);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        Iterator<ItemStack> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack item = drops.next();
            if (terminal(item).isEmpty()) continue;
            drops.remove();
            event.getItemsToKeep().add(item);
            plugin.getLogger().warning("终态装备在死亡掉落时被保留 player="
                    + event.getEntity().getUniqueId() + " state=" + terminal(item).orElse("?"));
        }
    }

    /**
     * C-008 终态守卫（丢弃路径）：账本已写终态的装备不能被玩家丢回世界。
     *
     * <p>丢弃是玩家主动发起的「离开背包」动作，与死亡掉落一样会让终态身份重新
     * 出现在地面被其他人捡走。这里只拦已确认终态的物品；读不出墓碑且数据库查不到
     * 终态的历史装备不受影响。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (terminal(event.getItemDrop().getItemStack()).isEmpty()) return;
        event.setCancelled(true);
        plugin.getLogger().warning("终态装备在丢弃时被拦下 player="
                + event.getPlayer().getUniqueId());
    }

    /** 墓碑子键优先：它是操作提交时随交付副本一起写下的确定性事实。 */
    private Optional<String> terminal(ItemStack item) {
        String ticket = ticket(item);
        if (DestroyRules.blocksFurtherDurabilityMutation(ticket)) return Optional.of(ticket);
        EquipmentSnapshot snapshot = equipment.inspect(item).orElse(null);
        if (snapshot == null || snapshot.itemId() == null) return Optional.empty();
        return ledger.entry(snapshot.itemId()).toCompletableFuture().getNow(Optional.empty())
                .map(entry -> entry.state())
                .filter(DestroyRules::persistedStateIsTerminal);
    }

    private String ticket(ItemStack item) {
        if (terminalKey == null) return null;
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(terminalKey, PersistentDataType.STRING);
    }
}
