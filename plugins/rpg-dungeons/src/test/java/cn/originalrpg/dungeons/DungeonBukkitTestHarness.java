package cn.originalrpg.dungeons;

import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.DungeonRewardService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SeasonExpeditionService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 让 {@code DungeonManager} 在 JUnit 进程里真跑起来的接缝（G-302-RESIDUAL-2 的行为化底座）。
 *
 * <p>本仓既无 MockBukkit 也无 Mockito，但 Paper 的 {@code Bukkit} 门面允许
 * {@code setServer(Server)} 注入动态代理的 {@code Server}；再补两个 ServiceLoader 缝
 * （{@code ServerBuildInfo} / {@code RegistryAccess}，见同包下的测试实现），
 * {@code DungeonManager} 的构造与会话/超时路径就能真实执行，从而把「超时调用顺序」
 * 从源码字符串断言换成真跑断言。</p>
 *
 * <p>{@code Bukkit.setServer} 只能调用一次（单例不可重定义），因此假 Server 在类初始化时
 * 装一次，每个测试只新造 scheduler 与 manager。本类只提供接缝，不含业务判据；
 * 代理对未声明返回值统一返回类型零值/空集合，让被测代码走「没有世界、没有玩家」的干净分支。</p>
 */
final class DungeonBukkitTestHarness {

    private static final Object SERVER_LOCK = new Object();
    private static volatile boolean installed;
    /** 当前测试的调度器：假 Server 装一次，但每个用例要拿到自己的调度记录。 */
    private static volatile Scheduler activeScheduler;

    private DungeonBukkitTestHarness() {
    }

    /** 可记录调用的通用代理。 */
    static final class Recorder implements InvocationHandler {
        final Map<String, Object> fixed = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (fixed.containsKey(name)) return fixed.get(name);
            if (name.equals("toString")) return "proxy:" + method.getDeclaringClass().getSimpleName();
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
            calls.add(method.getDeclaringClass().getSimpleName() + "." + name);
            return zero(method.getReturnType());
        }

        Recorder fixed(String name, Object value) {
            fixed.put(name, value);
            return this;
        }
    }

    private static Object zero(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == void.class) return null;
        if (type == List.class) return new ArrayList<>();
        if (type == Set.class) return new LinkedHashSet<>();
        if (type == Map.class) return new HashMap<>();
        if (type == Collection.class) return new ArrayList<>();
        return null;
    }

    static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** 记录被取消的任务，并按调用顺序保存调度出去的 Runnable（即超时回调）。 */
    static final class Scheduler {
        final List<String> cancelled = new ArrayList<>();
        final List<Runnable> callbacks = new ArrayList<>();
        final List<BukkitTask> tasks = new ArrayList<>();

        Object schedulerProxy() {
            return proxy(BukkitScheduler.class, (p, method, args) -> {
                if (method.getName().startsWith("runTaskLater")) {
                    BukkitTask task = taskProxy();
                    tasks.add(task);
                    callbacks.add((Runnable) args[1]);
                    return task;
                }
                if (method.getName().equals("runTask")) {
                    ((Runnable) args[1]).run();
                    return null;
                }
                if (method.getName().equals("toString")) return "scheduler";
                if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                return zero(method.getReturnType());
            });
        }

        private BukkitTask taskProxy() {
            return (BukkitTask) proxy(BukkitTask.class, (p, method, args) -> {
                if (method.getName().equals("cancel")) {
                    cancelled.add("cancel");
                    return null;
                }
                if (method.getName().equals("isCancelled")) return false;
                if (method.getName().equals("toString")) return "task#" + System.identityHashCode(p);
                if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                return zero(method.getReturnType());
            });
        }
    }

    /** 安装假 Bukkit 门面（JVM 内只装一次），并返回已就绪的 {@code DungeonManager}。 */
    static DungeonManager newManager(Scheduler scheduler) throws Exception {
        synchronized (SERVER_LOCK) {
            activeScheduler = scheduler;
            if (!installed) {
                installFakeBukkit();
                installed = true;
            }
        }
        return constructManager();
    }

    private static void installFakeBukkit() throws Exception {
        Recorder serverRecorder = new Recorder();
        new File("/tmp/dungeon-harness/server").mkdirs();
        new File("/tmp/dungeon-harness/data").mkdirs();
        serverRecorder.fixed("getLogger", Logger.getLogger("dungeon-harness"));
        serverRecorder.fixed("getName", "DungeonHarness");
        serverRecorder.fixed("getBukkitVersion", "26.2-R0.1-SNAPSHOT");
        serverRecorder.fixed("getVersion", "DungeonHarness 26.2");
        serverRecorder.fixed("getServicesManager", proxy(ServicesManager.class, new Recorder()));
        // 调度器按「当前用例的 Scheduler」动态转发：假 Server 只装一次，记录器却要每个用例各一份。
        serverRecorder.fixed("getScheduler", proxy(BukkitScheduler.class,
                (p, method, args) -> ((BukkitScheduler) activeScheduler.schedulerProxy())
                        .getClass().getMethod(method.getName(), method.getParameterTypes())
                        .invoke(activeScheduler.schedulerProxy(), args)));
        serverRecorder.fixed("getWorlds", new ArrayList<World>());
        serverRecorder.fixed("getWorldContainer", new File("/tmp/dungeon-harness/server"));
        Bukkit.setServer((Server) proxy(Server.class, serverRecorder));
    }

    private static DungeonManager constructManager() throws Exception {
        Recorder pluginRecorder = new Recorder();
        pluginRecorder.fixed("getName", "RpgDungeons");
        pluginRecorder.fixed("namespace", "rpgdungeons");
        pluginRecorder.fixed("getLogger", Logger.getLogger("dungeon-harness.plugin"));
        pluginRecorder.fixed("getDataFolder", new File("/tmp/dungeon-harness/data"));
        pluginRecorder.fixed("getConfig", new YamlConfiguration());
        Plugin plugin = (Plugin) proxy(Plugin.class, pluginRecorder);

        MessageService messages = (MessageService) proxy(MessageService.class, new Recorder());
        Constructor<SeasonExpeditionController> seasonCtor = SeasonExpeditionController.class.getDeclaredConstructor(
                Plugin.class, SeasonExpeditionService.class, ChapterService.class, MessageService.class);
        seasonCtor.setAccessible(true);
        SeasonExpeditionController seasonExpeditions = seasonCtor.newInstance(plugin,
                proxy(SeasonExpeditionService.class, new Recorder()),
                proxy(ChapterService.class, new Recorder()), messages);

        Constructor<DungeonManager> ctor = DungeonManager.class.getDeclaredConstructor(
                Plugin.class, ProfileService.class, MessageService.class, DungeonRewardService.class,
                SeasonExpeditionController.class);
        ctor.setAccessible(true);
        return ctor.newInstance(plugin,
                proxy(ProfileService.class, new Recorder()), messages,
                proxy(DungeonRewardService.class, new Recorder()), seasonExpeditions);
    }

    /** 直接触发 {@code assignInstance}（会话绑定入口）。 */
    static void assignInstance(DungeonManager manager, String id, Set<UUID> members, World world)
            throws Exception {
        Method assign = DungeonManager.class.getDeclaredMethod(
                "assignInstance", String.class, Set.class, World.class, SeasonExpeditionRotation.class);
        assign.setAccessible(true);
        assign.invoke(manager, id, members, world, null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> field(DungeonManager manager, String name) throws Exception {
        Field field = DungeonManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(manager);
    }

    static World world(String name) {
        UUID uid = UUID.nameUUIDFromBytes(name.getBytes());
        return (World) proxy(World.class, new Recorder()
                .fixed("getName", name)
                .fixed("getUID", uid));
    }
}
