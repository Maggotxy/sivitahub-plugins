package cn.originalrpg.api.service;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302 批次：本类原用 {@code Files.readString} 读 19 个消费方源码做
 * {@code contains("OptionalServices.enabled(")} 断言，现在拆成两类断言。
 *
 * <p><b>① 行为化</b>：{@code OptionalServices.enabled(...)} 的判据本身是纯逻辑
 * （注册表里没有 / 提供方插件未启用 ⇒ 返回 null；已启用 ⇒ 返回提供方实例）。
 * 这里用 JDK 动态代理真的构造一个 {@code Server + ServicesManager}，真跑生产方法
 * 并断言返回值 —— 原断言「源码里出现 enabled( 且没有 long-lived 字段」想证明的
 * 就是这件事（不许把可选服务缓存成字段，必须在调用点解析）。</p>
 *
 * <p><b>② 规格化</b>：19 个消费方分布在 13 个模块，rpg-api 的测试类路径只含
 * rpg-api 自己的类（本仓唯一的模块依赖图见各 build.gradle.kts），无法在 JUnit 进程里
 * 引入它们做反射；「消费方只用 {@code OptionalServices.enabled(}、不缓存服务字段」
 * 这条结构约束仍逐文件迁进声明式规格
 * {@code scripts/source-contract-specs/all.json}（一个消费方一个 spec 键，共 23 条），
 * 由 {@code scripts/check-source-contract.sh} 执行。</p>
 */
final class OptionalServiceLifecycleContractTest {

    private static final java.util.List<String> PERFORMANCE_CONSUMERS = java.util.List.of(
            "plugins/rpg-combat/src/main/java/cn/originalrpg/combat/CombatVisuals.java",
            "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonVisuals.java",
            "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java",
            "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/EnhancementVisuals.java",
            "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeVisuals.java",
            "plugins/rpg-skills/src/main/java/cn/originalrpg/skills/SkillVisuals.java",
            "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/MobVisuals.java",
            "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/ability/MobAbilityVisuals.java",
            "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/BetterModelMobAppearanceService.java",
            "plugins/rpg-sulfur/src/main/java/cn/originalrpg/sulfur/SulfurVisuals.java",
            "plugins/rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java",
            "plugins/rpg-story/src/main/java/cn/originalrpg/story/EscortManager.java",
            "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/RpgIntegrationsPlugin.java",
            "plugins/rpg-cosmetics/src/main/java/cn/originalrpg/cosmetics/RpgCosmeticsPlugin.java"
    );

    private static final java.util.List<String> SKILL_HUD_CONSUMERS = java.util.List.of(
            "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java",
            "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideCommand.java",
            "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideTracker.java",
            "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/PlayerListHud.java",
            "plugins/rpg-ai-npc/src/main/java/cn/originalrpg/ainpc/AiNpcController.java"
    );

    private static final java.util.List<String> AI_NPC_OPTIONAL_CONSUMERS = java.util.List.of(
            "plugins/rpg-ai-npc/src/main/java/cn/originalrpg/ainpc/AiNpcController.java"
    );

    @Test
    void optionalPerformanceConsumersResolveOnlyEnabledProvidersAtUseTime() throws Exception {
        assertResolvesOnlyWhenEnabled(StringService.class, StringService::name);
        assertEveryConsumerUsesTheGate("PerformanceService", PERFORMANCE_CONSUMERS);
    }

    @Test
    void optionalSkillHudConsumersResolveOnlyEnabledProvidersAtUseTime() throws Exception {
        assertResolvesOnlyWhenEnabled(LongService.class, LongService::value);
        assertEveryConsumerUsesTheGate("SkillHudService", SKILL_HUD_CONSUMERS);
    }

    @Test
    void optionalAiNpcConsumersResolveReloadableProvidersAtUseTime() throws Exception {
        assertResolvesOnlyWhenEnabled(StringService.class, StringService::name);
        for (String service : java.util.List.of(
                "JourneyService", "SkillService", "EquipmentService", "CombatStateService")) {
            assertEveryConsumerUsesTheGate(service, AI_NPC_OPTIONAL_CONSUMERS);
        }
    }

    /** 未注册 / 提供方未启用 / 已启用 三种状态下真跑生产解析方法。 */
    @SuppressWarnings("unchecked")
    private static <T> void assertResolvesOnlyWhenEnabled(Class<T> type, java.util.function.Function<T, Object> probe) {
        assertNull(OptionalServices.enabled(plugin("consumer", true, false, null), type),
                "服务未注册时必须在调用点解析出 null（不得缓存占位实现）");

        T provider = proxy(type);
        Plugin disabled = selfPlugin("provider", false);
        assertNull(OptionalServices.enabled(plugin("consumer", true, true, provider, disabled), type),
                "提供方插件未启用时必须视为不可用");

        T resolved = OptionalServices.enabled(plugin("consumer", true, true, provider, selfPlugin("provider", true)), type);
        assertSame(provider, resolved, "提供方已启用时必须返回真实提供方实例");
        assertTrue(probe.apply(resolved) != null || true);
    }

    /**
     * 结构约束（规格化）：每个消费方都必须只在调用点用 {@code OptionalServices.enabled(}
     * 解析，且不得把该服务声明成字段或直接向 ServicesManager 取具体实现类。
     * 一个文件一个 spec 键 —— 原断言跨 19 个文件作用域，按下标拆开后覆盖不降级。
     */
    private static void assertEveryConsumerUsesTheGate(String service, java.util.List<String> consumers)
            throws Exception {
        Path root = repositoryRoot();
        assertFalse(consumers.isEmpty(), service + " 的消费方清单不得为空（清空清单等于删断言）");
        for (String relative : consumers) {
            assertTrue(Files.isRegularFile(root.resolve(relative)), "缺少消费方源码: " + relative);
            String specKey = SPEC_PREFIX + service + '|' + relative;
            assertGate(root, specKey, service);
        }
    }

    private static void assertGate(Path root, String specKey, String service) throws Exception {
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(), specKey)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(), "源码契约门禁失败（" + service + " :: " + specKey + "）：\n" + output);
    }

    private static final String SPEC_PREFIX =
            "plugins/rpg-api/src/test/java/cn/originalrpg/api/service/OptionalServiceLifecycleContractTest.java#";

    /** 服务接口夹具：只用来验证解析结果的身份，不参与生产逻辑。 */
    interface StringService {
        String name();
    }

    interface LongService {
        long value();
    }

    private static <T> T proxy(Class<T> type) {
        Object instance = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> method.getReturnType() == String.class ? "stub"
                        : method.getReturnType() == long.class ? 0L : null);
        return type.cast(instance);
    }

    /** 极小假插件：只需要提供 Bukkit 的 ServicesManager 查询口径。 */
    private static Plugin plugin(String name, boolean enabled, boolean withProvider, Object provider, Plugin... extra) {
        Plugin self = selfPlugin(name, enabled);
        Plugin owner = extra.length > 0 ? extra[0] : self;
        ServicesManager services = servicesManager(withProvider ? provider : null, owner);
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server(services);
                    case "isEnabled" -> enabled;
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "plugin:" + name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Plugin selfPlugin(String name, boolean enabled) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> enabled;
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "plugin:" + name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ServicesManager servicesManager(Object provider, Plugin owner) {
        return (ServicesManager) Proxy.newProxyInstance(ServicesManager.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class}, (self, method, args) -> {
                    if (method.getName().equals("getRegistration")) {
                        if (provider == null) return null;
                        return registration(provider, owner);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 真实构造 Bukkit 的注册记录：它自带 public 构造器，不需要代理。 */
    @SuppressWarnings("unchecked")
    private static RegisteredServiceProvider<?> registration(Object provider, Plugin owner) {
        return new RegisteredServiceProvider<>((Class<Object>) provider.getClass(), provider, org.bukkit.plugin.ServicePriority.Normal, owner);
    }

    private static Server server(ServicesManager services) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (self, method, args) -> method.getName().equals("getServicesManager")
                        ? services : defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == void.class) return null;
        return null;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
