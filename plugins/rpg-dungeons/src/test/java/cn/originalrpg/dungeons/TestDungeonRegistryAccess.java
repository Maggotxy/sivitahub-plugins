package cn.originalrpg.dungeons;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.Registry;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 测试专用的最小 {@link RegistryAccess}（与 rpg-combat 的 TestRegistryAccess 同范式）。
 *
 * <p>Paper 的 {@code Material#isAir()} 会经 {@code Registry.legacyRegistryFor} 取注册表；
 * 没有服务端实现时静态初始化直接抛
 * {@code IllegalStateException: No RegistryAccess implementation found}，
 * 导致 {@code DungeonManager} 的构造在 {@code resolveRewards()} 处失败。这里给一个
 * 「查不到就给空注册表」的占位实现，不参与任何玩法逻辑。</p>
 */
public final class TestDungeonRegistryAccess implements RegistryAccess {

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
                    case "stream", "keyStream" -> Stream.empty();
                    case "iterator" -> List.of().iterator();
                    case "getTags" -> List.of();
                    case "toString" -> "test-dungeon-registry";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "size" -> 0;
                    default -> {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) yield false;
                        if (type == int.class) yield 0;
                        if (type == long.class) yield 0L;
                        yield null;
                    }
                });
    }
}
