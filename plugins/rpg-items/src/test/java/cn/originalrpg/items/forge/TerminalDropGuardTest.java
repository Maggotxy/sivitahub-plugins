package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.service.EquipmentLedgerAuditService;
import cn.originalrpg.api.service.EquipmentService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3-A3 行为级测试：C-008 终态守卫必须覆盖「丢弃」这条第二出口。
 *
 * <p>真实调用 {@link EquipmentTerminalStateListener#onDrop}，读它是否取消事件。
 * 负向对照：把 {@code onDrop} 的 {@code event.setCancelled(true)} 去掉后，
 * {@link #terminalEquipmentCannotBeDroppedBackIntoTheWorld()} 会失败。</p>
 *
 * <p>装备侧全部用内联代理桩，不触网、不依赖服务端：判定只吃
 * 「PDC 墓碑子键」与「账本权威行」两个事实。</p>
 */
class TerminalDropGuardTest {

    @Test
    void terminalEquipmentCannotBeDroppedBackIntoTheWorld() {
        ItemStack tombstoned = itemWithTicket("item-terminal:DESTROYED:" + UUID.randomUUID());
        AtomicBoolean cancelled = new AtomicBoolean();
        PlayerDropItemEvent event = dropEvent(tombstoned, cancelled);

        listener(null).onDrop(event);

        assertTrue(cancelled.get(), "账本已写终态的装备必须拦下丢弃，否则它又回到了世界");
    }

    @Test
    void ledgerBackedTerminalStateIsAlsoBlockedWhenTheTicketIsMissing() {
        // 后台兜底动作只更新数据库、碰不到玩家数据：没有墓碑子键时回落账本权威行。
        UUID itemId = UUID.randomUUID();
        ItemStack plain = equipmentSkeleton(itemId);
        AtomicBoolean cancelled = new AtomicBoolean();
        PlayerDropItemEvent event = dropEvent(plain, cancelled);

        listener(new FixedLedger("DESTROYED")).onDrop(event);

        assertTrue(cancelled.get(), "账本权威行已终态时同样不能丢弃");
    }

    @Test
    void ordinaryGearIsStillDroppable() {
        // 不能误伤：没有墓碑、账本也没写终态的装备必须照常丢弃。
        ItemStack plain = equipmentSkeleton(UUID.randomUUID());
        AtomicBoolean cancelled = new AtomicBoolean();
        PlayerDropItemEvent event = dropEvent(plain, cancelled);

        listener(new FixedLedger("BOUND")).onDrop(event);

        assertFalse(cancelled.get(), "普通未终结装备必须仍可丢弃，守卫不能扩大打击面");
    }

    private static EquipmentTerminalStateListener listener(EquipmentLedgerAuditService ledger) {
        return new EquipmentTerminalStateListener(
                pluginStub(), new UninspectedEquipment(), ledger == null ? new FixedLedger(null) : ledger);
    }

    /** 带墓碑子键的「已终结」物品。 */
    private static ItemStack itemWithTicket(String ticket) {
        ItemStack stack = new StubItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey("rpgitems", DestroyRules.TERMINAL_KEY), PersistentDataType.STRING, ticket);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 不带墓碑的装备骨架；判定只走到账本回落分支。 */
    private static ItemStack equipmentSkeleton(UUID itemId) {
        ItemStack stack = new StubItemStack(Material.STONE);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey("rpgitems", "item_id"),
                PersistentDataType.STRING, itemId.toString());
        stack.setItemMeta(meta);
        return stack;
    }

    private static PlayerDropItemEvent dropEvent(ItemStack dropped, AtomicBoolean cancelled) {
        Item item = (Item) proxy(Item.class, (proxy, method, args) -> switch (method.getName()) {
            case "getItemStack" -> dropped;
            default -> defaultValue(method.getReturnType());
        });
        Player player = (Player) proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> UUID.randomUUID();
            default -> defaultValue(method.getReturnType());
        });
        return new PlayerDropItemEvent(player, item) {
            @Override
            public void setCancelled(boolean value) {
                cancelled.set(value);
                super.setCancelled(value);
            }
        };
    }

    /**
     * 插件桩必须实现 {@code namespace()}：{@code new NamespacedKey(plugin, key)} 会
     * 直接读它作为命名空间，返回 null 会在构造期抛 {@code IllegalArgumentException}。
     */
    private static Plugin pluginStub() {
        return (Plugin) proxy(Plugin.class, (proxy, method, args) -> switch (method.getName()) {
            case "getLogger" -> Logger.getLogger("terminal-drop-guard-test");
            case "isEnabled" -> false;
            case "getName", "namespace" -> "rpgitems";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    /** 账本桩：只回答一个固定状态。 */
    private record FixedLedger(String state) implements EquipmentLedgerAuditService {
        @Override
        public CompletableFuture<Optional<Entry>> entry(UUID itemId) {
            return CompletableFuture.completedFuture(state == null ? Optional.empty()
                    : Optional.of(new Entry(itemId, state, null, "TEST")));
        }

        @Override
        public CompletableFuture<List<Entry>> watchlist(int limit) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<AuditResult> quarantine(UUID itemId, String actor, String auditNote) {
            return CompletableFuture.completedFuture(new AuditResult(AuditResult.Status.RULES_REFUSED, state));
        }

        @Override
        public CompletableFuture<AuditResult> restore(UUID itemId, String actor, String auditNote) {
            return CompletableFuture.completedFuture(new AuditResult(AuditResult.Status.RULES_REFUSED, state));
        }

        @Override
        public CompletableFuture<List<AuditRecord>> auditTrail(UUID itemId, int limit) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /** 装备服务桩：本测试的物品读不出签名快照，判定只走墓碑/账本两条事实。 */
    private static final class UninspectedEquipment implements EquipmentService {
        @Override
        public boolean isCustomEquipment(ItemStack item) {
            return false;
        }

        @Override
        public boolean isAuthentic(ItemStack item) {
            return false;
        }

        @Override
        public boolean isBound(ItemStack item) {
            return false;
        }

        @Override
        public boolean isTradeUnlocked(ItemStack item) {
            return false;
        }

        @Override
        public Optional<UUID> itemId(ItemStack item) {
            ItemMeta meta = item == null ? null : item.getItemMeta();
            if (meta == null) return Optional.empty();
            String value = meta.getPersistentDataContainer().get(
                    new NamespacedKey("rpgitems", "item_id"), PersistentDataType.STRING);
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        }

        @Override
        public Optional<EquipmentSnapshot> inspect(ItemStack item) {
            return itemId(item).map(id -> new EquipmentSnapshot(id, UUID.randomUUID(), Optional.empty(),
                    "COMMON", "WEAPON", "UNIVERSAL", 0, 0, 0, 0, Optional.empty(), Map.of()));
        }

        @Override
        public void sign(ItemStack item) {
        }

        @Override
        public boolean unlockForTrade(ItemStack item, UUID expectedOwner) {
            return false;
        }
    }

    /** 只实现被测代码走到的成员的最小 ItemStack。 */
    private static final class StubItemStack extends ItemStack {
        private ItemMeta meta = (ItemMeta) proxy(ItemMeta.class, new MetaHandler(new DataContainer()));

        StubItemStack(Material material) {
            super(material, 1);
        }

        @Override
        public ItemMeta getItemMeta() {
            return meta;
        }

        @Override
        public boolean setItemMeta(ItemMeta value) {
            this.meta = value;
            return true;
        }
    }

    private record MetaHandler(PersistentDataContainer data) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getPersistentDataContainer" -> data;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "StubItemMeta";
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    /** 内存 PDC：只实现 get/has/set/remove/getKeys。 */
    private static final class DataContainer implements PersistentDataContainer {
        private final Map<String, Object> values = new java.util.LinkedHashMap<>();

        @Override
        public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
            values.put(key.getNamespace() + ':' + key.getKey(), value);
        }

        @Override
        public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
            return values.containsKey(key.getNamespace() + ':' + key.getKey());
        }

        @Override
        public boolean has(NamespacedKey key) {
            return values.containsKey(key.getNamespace() + ':' + key.getKey());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
            return (C) values.get(key.getNamespace() + ':' + key.getKey());
        }

        @Override
        public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C fallback) {
            C value = get(key, type);
            return value == null ? fallback : value;
        }

        @Override
        public java.util.Set<NamespacedKey> getKeys() {
            return java.util.Set.of();
        }

        @Override
        public void remove(NamespacedKey key) {
            values.remove(key.getNamespace() + ':' + key.getKey());
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public int getSize() {
            return values.size();
        }

        @Override
        public void copyTo(PersistentDataContainer other, boolean replace) {
            throw new UnsupportedOperationException("copyTo");
        }

        @Override
        public void readFromBytes(byte[] bytes, boolean clear) {
            throw new UnsupportedOperationException("readFromBytes");
        }

        @Override
        public byte[] serializeToBytes() {
            throw new UnsupportedOperationException("serializeToBytes");
        }

        @Override
        public org.bukkit.persistence.PersistentDataAdapterContext getAdapterContext() {
            return (org.bukkit.persistence.PersistentDataAdapterContext) proxy(
                    org.bukkit.persistence.PersistentDataAdapterContext.class,
                    (proxy, method, args) -> method.getName().equals("newPersistentDataContainer")
                            ? new DataContainer() : defaultValue(method.getReturnType()));
        }
    }
}
