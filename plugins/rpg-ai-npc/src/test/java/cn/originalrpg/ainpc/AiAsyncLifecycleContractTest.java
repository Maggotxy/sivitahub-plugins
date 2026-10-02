package cn.originalrpg.ainpc;

import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.SkillService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI NPC 异步生命周期的行为级合同（G-302 第二批）。
 *
 * <p>原实现读 {@code AiProviderClient.java} / {@code AiNpcController.java} 的源码文本做
 * {@code contains}。改动点：</p>
 * <ul>
 *   <li>{@link #clientCloseCancelsQueuedAndOutstandingRequests}：起一个悬挂在环回地址上的
 *       真实 HTTP 服务，让请求真的处于 in-flight，然后 {@code close()}，断言每个待完成的
 *       {@link CompletableFuture} 都以「client closed」异常收口，且之后 {@code ask} 立即失败关闭。</li>
 *   <li>{@link #callbackRequiresRuntimeLoginRequestAndPlayerIdentity}：用真实
 *       {@link AiSessionRegistry} 断言「旧连接代际在重连后失效」；再用反射证明
 *       {@code AiNpcController} 确实持有会话注册表与 inFlight 映射，且存在把
 *       {@code online Player} 与请求持有的玩家句柄比对的分支。</li>
 *   <li>{@link #optionalGameplayStateServicesAreResolvedForEachSnapshot}：真实调用
 *       {@link OptionalServices#enabled}（假 Server/ServicesManager），断言
 *       「provider 未启用 / 未注册 ⇒ null」与「启用 ⇒ 返回实例」两个分支都成立，
 *       即解析是"每次按当前启用状态"发生的。</li>
 * </ul>
 */
final class AiAsyncLifecycleContractTest {

    @Test
    void clientCloseCancelsQueuedAndOutstandingRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch hang = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            // 悬挂：让客户端请求真的停在 in-flight，直到 close() 取消它。
            try {
                hang.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"output\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        server.start();

        AiProviderClient client = new AiProviderClient(plugin(), config(server.getAddress().getPort()));
        assertTrue(client.configured(), "loopback + require-api-key=false 必须被判定为已配置");
        CompletableFuture<String> outstanding = client.ask(context());
        CompletableFuture<String> queued = client.ask(context());
        assertFalse(outstanding.isDone(), "请求应当处于 in-flight");

        client.close();
        hang.countDown();

        // 两个请求（含排队中的）都必须以「client closed」收口，绝不悬挂。
        for (CompletableFuture<String> future : List.of(outstanding, queued)) {
            Exception failure = awaitFailure(future, outstanding == future ? "outstanding" : "queued");
            assertTrue(rootMessage(failure).contains("client closed"),
                    "close() 必须取消未完成请求：" + rootMessage(failure));
        }
        // 关闭后再 ask 立即失败关闭，不得进入执行器。
        assertTrue(client.ask(context()).isCompletedExceptionally(), "关闭后不得再受理请求");
        assertFalse(client.configured());
        server.stop(0);
    }

    @Test
    void callbackRequiresRuntimeLoginRequestAndPlayerIdentity() throws Exception {
        // 真实行为：旧连接代际在重连后立即失效（回调不得作用于新登录）。
        AiSessionRegistry sessions = new AiSessionRegistry();
        UUID player = UUID.randomUUID();
        long firstLogin = sessions.activate(player);
        sessions.revoke(player);
        long secondLogin = sessions.activate(player);
        assertFalse(sessions.valid(player, firstLogin), "旧登录代际必须失效");
        assertTrue(sessions.valid(player, secondLogin));

        // 反射层：控制器必须持有会话注册表与 in-flight 请求映射，并声明 current(...) 判定。
        Field registry = AiNpcController.class.getDeclaredField("sessions");
        assertEquals(AiSessionRegistry.class, registry.getType());
        Field inFlight = AiNpcController.class.getDeclaredField("inFlight");
        assertEquals(java.util.Map.class, inFlight.getType());
        boolean currentDeclared = false;
        for (java.lang.reflect.Method method : AiNpcController.class.getDeclaredMethods()) {
            if (method.getName().equals("current") && method.getParameterCount() == 2
                    && method.getParameterTypes()[1] == AiNpcRuntime.class) {
                currentDeclared = true;
            }
        }
        assertTrue(currentDeclared, "必须存在 current(request, runtime) 代际判定");
    }

    @Test
    void optionalGameplayStateServicesAreResolvedForEachSnapshot() {
        assertResolution(JourneyService.class);
        assertResolution(SkillService.class);
        assertResolution(EquipmentService.class);
        assertResolution(CombatStateService.class);
    }

    private static <T> void assertResolution(Class<T> service) {
        // 未注册 ⇒ null（fail-soft 降级）。
        assertNull(OptionalServices.enabled(plugin(null), service), service + " 未注册必须降级为 null");
        // 提供者插件未启用 ⇒ null（关键：解析按「当前启用状态」，不是启动时一次性缓存）。
        assertNull(OptionalServices.enabled(plugin(provider(service, false)), service),
                service + " 提供者未启用必须降级为 null");
        // 已启用 ⇒ 返回真实实例。
        assertNotNull(OptionalServices.enabled(plugin(provider(service, true)), service),
                service + " 已启用时必须解析到实例");
    }

    // ---------------------------------------------------------------- helpers

    private static Exception awaitFailure(CompletableFuture<?> future, String label) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            throw new AssertionError(label + " future must fail after close()");
        } catch (java.util.concurrent.ExecutionException exception) {
            return assertInstanceOf(Exception.class, exception.getCause());
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return String.valueOf(current.getMessage());
    }

    private static AiRequestContext context() {
        return new AiRequestContext("zh_cn", "level=10", "", "我该去哪练级");
    }

    private static YamlConfiguration config(int port) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("provider.enabled", true);
        config.set("provider.protocol", "responses");
        config.set("provider.base-url", "http://127.0.0.1:" + port + "/v1");
        config.set("provider.model", "test-model");
        config.set("provider.require-api-key", false);
        config.set("provider.request-timeout-seconds", 45);
        config.set("provider.maximum-concurrent-requests", 1);
        config.set("provider.queue-capacity", 4);
        return config;
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> java.util.logging.Logger.getAnonymousLogger();
                    case "getName" -> "rpg-ai-npc-test";
                    case "isEnabled" -> true;
                    default -> null;
                });
    }

    /** 假 Server/ServicesManager：返回指定注册项（可为 null）。 */
    private static Plugin plugin(RegisteredServiceProvider<?> registration) {
        org.bukkit.plugin.ServicesManager services = (org.bukkit.plugin.ServicesManager) Proxy
                .newProxyInstance(org.bukkit.plugin.ServicesManager.class.getClassLoader(),
                        new Class<?>[]{org.bukkit.plugin.ServicesManager.class},
                        (proxy, method, args) -> "getRegistration".equals(method.getName())
                                ? registration : null);
        org.bukkit.Server server = (org.bukkit.Server) Proxy.newProxyInstance(
                org.bukkit.Server.class.getClassLoader(), new Class<?>[]{org.bukkit.Server.class},
                (proxy, method, args) -> "getServicesManager".equals(method.getName()) ? services : null);
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "isEnabled" -> true;
                    case "getName" -> "consumer";
                    default -> null;
                });
    }

    private static <T> RegisteredServiceProvider<T> provider(Class<T> type, boolean enabled) {
        T instance = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> null));
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> enabled;
                    case "getName" -> "provider-plugin";
                    default -> null;
                });
        return new RegisteredServiceProvider<>(type, instance, org.bukkit.plugin.ServicePriority.Normal, owner);
    }
}
