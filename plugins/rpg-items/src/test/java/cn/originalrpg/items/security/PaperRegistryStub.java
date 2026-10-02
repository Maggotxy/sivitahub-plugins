package cn.originalrpg.items.security;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 测试专用的最小 {@link RegistryAccess}。
 *
 * <p>paper-api 没有注册表实现，{@code Material.asItemType()} 与伤害事件构造器都会
 * 经 ServiceLoader 找它，缺失时直接抛 {@code No RegistryAccess implementation found}，
 * 任何需要真实 {@code ItemStack} 的行为级测试都跑不起来。这里只提供
 * 「按 key 解析出 ItemType / DamageType 代理」的最小注册表，不参与玩法逻辑。</p>
 */
public final class PaperRegistryStub implements RegistryAccess {
    @Override
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return registry();
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return registry();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Keyed> Registry<T> registry() {
        return (Registry<T>) Proxy.newProxyInstance(
                Registry.class.getClassLoader(),
                new Class<?>[]{Registry.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get", "getOrThrow" -> resolve(args);
                    case "stream", "keyStream" -> Stream.empty();
                    case "iterator" -> List.of().iterator();
                    case "toString" -> "test-registry";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** key -> 同名 Material 的 ItemType；解不出时回退到伤害类型代理。 */
    private static Object resolve(Object[] args) {
        if (args == null || args.length == 0) return null;
        String name = args[0] instanceof NamespacedKey key ? key.getKey() : String.valueOf(args[0]);
        Material material = name == null || name.isBlank()
                ? null : Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return material == null ? damageTypeStub(args) : itemType(material);
    }

    private static ItemType itemType(Material material) {
        return (ItemType) Proxy.newProxyInstance(
                ItemType.class.getClassLoader(), new Class<?>[]{ItemType.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKey", "key" -> material.getKey();
                    case "asMaterial" -> material;
                    case "isAir" -> material.isAir();
                    case "createItemStack" -> new StackFixture(material,
                            args != null && args.length > 0 ? (Integer) args[0] : 1);
                    case "toString" -> material.name();
                    case "hashCode" -> material.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Object damageTypeStub(Object[] args) {
        Object key = args != null && args.length > 0 ? args[0] : null;
        return Proxy.newProxyInstance(
                org.bukkit.damage.DamageType.class.getClassLoader(),
                new Class<?>[]{org.bukkit.damage.DamageType.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getKey")) return key;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == double.class) return 0.0;
                    return null;
                });
    }

    /**
     * ItemType.createItemStack 必须返回 ItemStack。用受保护的无参构造器，
     * 并覆盖 getType/getAmount，从而完全绕开 craftDelegate。
     */
    private static final class StackFixture extends org.bukkit.inventory.ItemStack {
        private final Material material;
        private final int amount;

        StackFixture(Material material, int amount) {
            super();
            this.material = material;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }
    }
}
