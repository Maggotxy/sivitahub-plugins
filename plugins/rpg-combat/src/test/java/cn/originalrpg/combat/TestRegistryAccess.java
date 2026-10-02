package cn.originalrpg.combat;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.Registry;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.Stream;

/**
 * 测试专用的最小 {@link RegistryAccess}。
 *
 * Paper 25+ 的伤害事件构造器会通过 ServiceLoader 找注册表以解析
 * {@code org.bukkit.damage.DamageType}；没有服务端实现时事件类会在初始化阶段抛
 * {@code No RegistryAccess implementation found}，事件级测试就跑不起来。
 * 这里只提供「查得到、查不到都给一个代理对象」的空注册表，不参与任何玩法逻辑。
 */
public final class TestRegistryAccess implements RegistryAccess {
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
                    case "get", "getOrThrow" -> damageTypeStub(args);
                    case "stream", "keyStream" -> Stream.empty();
                    case "iterator" -> List.of().iterator();
                    case "toString" -> "test-registry";
                    case "hashCode" -> System.identityHashCode(proxy);
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
                    if (type == void.class) return null;
                    return null;
                });
    }
}

/** 见 {@link #TestRegistryAccess}：伤害事件构造器还会经 ServiceLoader 找 InternalAPIBridge。 */
