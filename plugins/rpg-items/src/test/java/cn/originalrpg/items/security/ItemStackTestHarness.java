package cn.originalrpg.items.security;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * G-112 行为级测试的 {@link ItemStack} 夹具。
 *
 * <p>paper-api 只提供接口，没有 {@code ItemMeta} / {@code PersistentDataContainer} 实现，
 * 因此 JUnit 进程里 {@code new ItemStack(...)} 的 craftDelegate 为 null，
 * {@code getItemMeta()} 直接抛 NPE（复现见本任务证据 ProveIt）。这里补一个内存实现，
 * 让测试能真正构造物品、写入 PDC、签名、再跑被测代码，而不是只做源码字符串断言。</p>
 *
 * <p>{@code ItemMeta} 有 113 个抽象方法，逐个实现纯属噪音，所以用 {@link Proxy}：
 * 只有 {@code getPersistentDataContainer()} / {@code clone()} / {@code hasCustomName()}
 * 返回真实值，其余按返回类型给默认值。容器本身是真实实现，因此
 * {@code set()} / {@code get()} / {@code getKeys()} 的行为与生产一致。</p>
 */
final class ItemStackTestHarness {
    private ItemStackTestHarness() {
    }

    /** 真实材料：STONE 有 ItemType，能通过 ItemStack 的构造校验。 */
    static final Material SAMPLE_MATERIAL = Material.STONE;

    /**
     * 造一个身份字段填好的装备骨架；种子命名空间由调用方决定。
     * 调用 {@link EquipmentSecurity#sign(ItemStack)} 之后才是「签名正确」的物品。
     */
    static ItemStack equipmentSkeleton(String primaryNamespace) {
        ItemStack stack = new HarnessItemStack(SAMPLE_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(key(primaryNamespace, "item_id"), PersistentDataType.STRING, UUID.randomUUID().toString());
        data.set(key(primaryNamespace, "crafter"), PersistentDataType.STRING, UUID.randomUUID().toString());
        data.set(key(primaryNamespace, "rarity"), PersistentDataType.STRING, "COMMON");
        data.set(key(primaryNamespace, "total_slots"), PersistentDataType.INTEGER, 3);
        data.set(key(primaryNamespace, "schema_version"), PersistentDataType.INTEGER, 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 造一个核心材料：带权威标记 + 归属玩家，外加可选的外来命名空间标记。 */
    static ItemStack coreMaterial(String ownerId, String... extraMarkers) {
        ItemStack stack = new HarnessItemStack(SAMPLE_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(new NamespacedKey("rpgmobs", "adaptive_material"), PersistentDataType.BYTE, (byte) 1);
        data.set(new NamespacedKey("rpgitems", "material_owner"), PersistentDataType.STRING, ownerId);
        for (int index = 0; index + 1 < extraMarkers.length; index += 2) {
            data.set(new NamespacedKey(extraMarkers[index], extraMarkers[index + 1]),
                    PersistentDataType.STRING, "1");
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** 给一个 item 追加一个外来命名空间的 PDC 标记。 */
    static ItemStack withForeignMarker(ItemStack item, String namespace, String marker) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(namespace, marker),
                PersistentDataType.STRING, "1");
        item.setItemMeta(meta);
        return item;
    }

    /** 读取物品上的全部 PDC key（命名空间:key）；没有 meta 时空集合。 */
    static Set<String> keysOf(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return Set.of();
        Set<String> keys = new LinkedHashSet<>();
        meta.getPersistentDataContainer().getKeys()
                .forEach(value -> keys.add(value.getNamespace() + ':' + value.getKey()));
        return keys;
    }

    private static NamespacedKey key(String namespace, String value) {
        return new NamespacedKey(namespace, value);
    }

    private static final class HarnessItemStack extends ItemStack {
        private ItemMeta meta = newMeta(new HarnessDataContainer());

        HarnessItemStack(Material material) {
            super(material, 1);
        }

        @Override
        public ItemMeta getItemMeta() {
            return meta;
        }

        @Override
        public boolean hasItemMeta() {
            return meta != null;
        }

        @Override
        public boolean setItemMeta(ItemMeta value) {
            this.meta = value;
            return true;
        }
    }

    private static ItemMeta newMeta(HarnessDataContainer data) {
        return (ItemMeta) Proxy.newProxyInstance(
                ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> data;
                    case "clone" -> newMeta(new HarnessDataContainer(data));
                    case "hasCustomName", "hasDisplayName", "hasItemName", "hasLore" -> false;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "HarnessItemMeta";
                    default -> defaultValue(method.getReturnType());
                });
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

    /** 内存 PDC：只实现被测代码走到的成员。 */
    private static final class HarnessDataContainer implements PersistentDataContainer {
        private final Map<String, Object> values = new LinkedHashMap<>();

        HarnessDataContainer() {
        }

        HarnessDataContainer(HarnessDataContainer source) {
            this.values.putAll(source.values);
        }

        @Override
        public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
            values.put(id(key), value);
        }

        @Override
        public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
            return values.containsKey(id(key));
        }

        @Override
        public boolean has(NamespacedKey key) {
            return values.containsKey(id(key));
        }

        @Override
        public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
            @SuppressWarnings("unchecked")
            C value = (C) values.get(id(key));
            return value;
        }

        @Override
        public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C fallback) {
            C value = get(key, type);
            return value == null ? fallback : value;
        }

        @Override
        public Set<NamespacedKey> getKeys() {
            Set<NamespacedKey> keys = new LinkedHashSet<>();
            values.keySet().forEach(value -> keys.add(key(value)));
            return keys;
        }

        @Override
        public void remove(NamespacedKey key) {
            values.remove(id(key));
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
        public void readFromBytes(byte[] bytes, boolean clear) throws IOException {
            throw new UnsupportedOperationException("readFromBytes");
        }

        @Override
        public byte[] serializeToBytes() throws IOException {
            throw new UnsupportedOperationException("serializeToBytes");
        }

        @Override
        public PersistentDataAdapterContext getAdapterContext() {
            return (PersistentDataAdapterContext) Proxy.newProxyInstance(
                    PersistentDataAdapterContext.class.getClassLoader(),
                    new Class<?>[]{PersistentDataAdapterContext.class},
                    (proxy, method, args) -> "newPersistentDataContainer".equals(method.getName())
                            ? new HarnessDataContainer() : defaultValue(method.getReturnType()));
        }

        private static String id(NamespacedKey key) {
            return key.getNamespace() + ':' + key.getKey();
        }

        private static NamespacedKey key(String id) {
            int split = id.indexOf(':');
            return new NamespacedKey(id.substring(0, split), id.substring(split + 1));
        }
    }
}
