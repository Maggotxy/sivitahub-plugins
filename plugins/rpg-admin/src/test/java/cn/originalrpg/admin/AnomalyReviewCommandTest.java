package cn.originalrpg.admin;

import cn.originalrpg.api.model.ProgressionAnomaly;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProgressionAnomalyService;
import cn.originalrpg.core.persistence.JdbcProgressionAnomalyService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-ANOMALY-ACK 的行为级测试：{@code /rpgadmin anomaly list|ack} 必须真的能读、真的能签。
 *
 * <p>这是**命令层**测试：每一条用例都从 {@link AnomalyReviewCommand} 进
 * （与 {@code RpgAdminPlugin} 用的是同一条 {@code of(...)} 路径），
 * 再断言数据库里的那一格。被测服务是真实的 {@link JdbcProgressionAnomalyService}，
 * 跑在真实 H2 + 真实 JDBC 事务上 —— 用假服务测「入口接上了」会同时放过实现里的署名丢失问题。</p>
 *
 * <p>判据（对应任务要求 ③）：写一条异常 → {@code list} 能看到 → {@code ack} 后不再出现在
 * pending → {@code reviewer}/{@code reviewed_at} 真的落库 → 重复 ack 幂等且保留首个署名。</p>
 */
class AnomalyReviewCommandTest {
    private static final String OPERATOR = "operator-zhang";
    /**
     * 单次异步跳的容忍上限（毫秒）：JDBC 在虚拟线程池上跑，本该在毫秒级完成。
     * 超时即判「异步链未收尾」而不是静默通过。
     */
    private static final long HOP_TIMEOUT_MS = 2_000;
    /** 命令层异步链的硬上限（毫秒），超过就报「异步链未收尾」。 */
    private static final long CHAIN_TIMEOUT_MS = 10_000;
    /** 慢 executor 回归测试里每一跳的人为延迟（毫秒）。 */
    private static final long SLOW_HOP_DELAY_MS = 100;
    /**
     * 命令层「有界收敛」的容忍轮数：等待超过 {@link #CHAIN_TIMEOUT_MS} 后，
     * 再多确认这么多轮没有新增播报才判定完成 —— 覆盖「gate 归零 → 下一次派发」
     * 之间的窗口（本类 ack/list 会再嵌一跳 {@code whenComplete}）。
     */
    private static final int QUIET_SETTLE_ROUNDS = 3;
    /** 每轮静默确认的休眠（毫秒）。 */
    private static final long QUIET_SETTLE_SLEEP_MS = 2;

    private HikariDataSource dataSource;
    private ExecutorService executor;
    /** 跟踪被测服务上「在飞 / 已派发」的异步跳，用于确定性等待。 */
    private TrackingExecutorService trackedExecutor;
    /** 当前用例自定义的观测器（默认 = {@link #trackedExecutor}）。 */
    private TrackingExecutorService observedExecutor;
    private JdbcProgressionAnomalyService service;
    private AnomalyReviewCommand command;
    private final List<String> messages = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:admin_anomaly_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        createSchema();
        // 只看不改：包装器不改变异步语义（任务仍在虚拟线程池上跑），
        // 只是把「引擎提交了多少跳、哪一跳收尾了」变成可观测的事实。
        trackedExecutor = new TrackingExecutorService(executor);
        service = new JdbcProgressionAnomalyService(dataSource, trackedExecutor);
        // 派发器仍是真同步（runOnDispatchLoop = 直接执行）：命令返回时已落库的那一格照旧可见；
        // 区别只是这一次派发被在飞计数覆盖，所以「两跳异步」的收尾可被确定性判定。
        command = AnomalyReviewCommand.of(service, messages(), plugin(),
                trackedExecutor::runOnDispatchLoop).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void writtenAnomalyIsListedThenAcknowledgedWithAStoredSignature() throws Exception {
        UUID player = UUID.randomUUID();
        // 写侧：与生产同一口径（老口径 level=31 无法唯一映射）。
        var recorded = service.inspect(player, 1, 31, 0).toCompletableFuture().join();
        assertEquals(cn.originalrpg.api.model.ProgressionAnomalyResult.Status.RECORDED, recorded.status());

        // 读侧：list 必须把待复核行摆到操作者眼前。
        RpgCommandResult listed = run("anomaly", "list");
        assertTrue(listed.messages.stream().anyMatch(line ->
                line.contains("admin.anomaly-list") && line.contains("count=1") && line.contains("total=1")),
                listed.messages.toString());
        assertTrue(listed.messages.stream().anyMatch(line -> line.contains("admin.anomaly-entry")
                && line.contains(player.toString()) && line.contains("local-level-out-of-range:31")),
                "清单必须带上玩家 UUID 与判定依据：" + listed.messages);

        // 复核：ack 需要 CONFIRM + 结论。
        RpgCommandResult acked = run("anomaly", "ack", player.toString(), "CONFIRM", "dry-run 确认可唯一映射");
        assertTrue(acked.messages.stream().anyMatch(line -> line.contains("admin.anomaly-acked")
                && line.contains(OPERATOR)), acked.messages.toString());

        // 落库：署名 + 结论 + 时间三件都在行上，且该行不再出现在待复核清单。
        assertEquals(OPERATOR, text("SELECT reviewer FROM rpg_progression_anomalies"));
        assertEquals("dry-run 确认可唯一映射", text("SELECT review_note FROM rpg_progression_anomalies"));
        assertNotNull(text("SELECT reviewed_at FROM rpg_progression_anomalies"));
        assertTrue(run("anomaly", "list").messages.stream().anyMatch(line ->
                line.contains("admin.anomaly-list-empty")), "签署后清单必须变空");
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_progression_anomalies WHERE reviewed_at IS NULL"));
    }

    @Test
    void repeatedAcknowledgeIsIdempotentAndSaysSo() throws Exception {
        UUID player = UUID.randomUUID();
        service.inspect(player, 1, 31, 0).toCompletableFuture().join();

        run("anomaly", "ack", player.toString(), "CONFIRM", "first");
        RpgCommandResult replay = run("anomaly", "ack", player.toString(), "CONFIRM", "second");

        assertTrue(replay.messages.stream().anyMatch(line -> line.contains("admin.anomaly-not-pending")),
                "重复 ack 必须显式播报「未作改动」而不是报成功：" + replay.messages);
        // 幂等口径：先到者为准，第二次既不覆盖署名也不覆盖结论与时间。
        assertEquals(OPERATOR, text("SELECT reviewer FROM rpg_progression_anomalies"));
        assertEquals("first", text("SELECT review_note FROM rpg_progression_anomalies"));
    }

    @Test
    void acknowledgeWithoutConfirmOrSignatureIsRefusedAndWritesNothing() throws Exception {
        UUID player = UUID.randomUUID();
        service.inspect(player, 1, 31, 0).toCompletableFuture().join();

        // 缺 CONFIRM：只提示，不落库。
        RpgCommandResult noConfirm = run("anomaly", "ack", player.toString(), "looks", "fine");
        assertTrue(noConfirm.messages.stream().anyMatch(line -> line.contains("admin.anomaly-confirm")),
                noConfirm.messages.toString());
        assertNull(text("SELECT reviewer FROM rpg_progression_anomalies"));

        // 结论为空也不是问题（复核本身就是结论），但 CONFIRM 与 UUID 必须在位。
        RpgCommandResult badId = run("anomaly", "ack", "not-a-uuid", "CONFIRM", "ok");
        assertTrue(badId.messages.stream().anyMatch(line -> line.contains("admin.anomaly-invalid-id")),
                badId.messages.toString());
        assertNull(text("SELECT reviewer FROM rpg_progression_anomalies"));

        // 无该玩家待复核行：无改动，且不报成功。
        RpgCommandResult missing = run("anomaly", "ack", UUID.randomUUID().toString(), "CONFIRM", "ok");
        assertTrue(missing.messages.stream().anyMatch(line -> line.contains("admin.anomaly-not-pending")),
                missing.messages.toString());
    }

    @Test
    void listSupportsPagingAndShowsHowManyAreStillPending() throws Exception {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID player = UUID.randomUUID();
            players.add(player);
            service.inspect(player, 0, 31 + i, 0).toCompletableFuture().join();
        }

        RpgCommandResult firstPage = run("anomaly", "list", "0", "2");
        assertTrue(firstPage.messages.stream().anyMatch(line -> line.contains("admin.anomaly-list")
                && line.contains("count=2") && line.contains("total=3") && line.contains("limit=2")),
                "分页必须同时给出本页条数、总数与窗口：" + firstPage.messages);

        RpgCommandResult secondPage = run("anomaly", "list", "2", "2");
        long entries = secondPage.messages.stream().filter(line -> line.contains("admin.anomaly-entry")).count();
        assertEquals(1L, entries, "第二页只应剩 1 条：" + secondPage.messages);

        // limit 上限收敛到 500：非法/过大值不会打穿内存。
        RpgCommandResult huge = run("anomaly", "list", "0", "9999");
        assertTrue(huge.messages.stream().anyMatch(line -> line.contains("admin.anomaly-list")
                && line.contains("limit=500")), huge.messages.toString());
    }

    @Test
    void tabCompletionOffersTheTwoSubcommandsAndConfirm() {
        assertEquals(List.of("list", "ack"), AnomalyReviewCommand.complete(new String[]{"anomaly", ""}));
        assertEquals(List.of("CONFIRM"), AnomalyReviewCommand.complete(
                new String[]{"anomaly", "ack", UUID.randomUUID().toString(), "CON"}));
    }

    /**
     * 跑一次子命令并等它真的收尾：JDBC 在虚拟线程池上完成，播报回调随之在主线程派发器上落地。
     * 等待是**确定性**的（见 {@link #awaitChainSettled()}），不是「睡够 30ms 就算完」。
     */
    private RpgCommandResult run(String... args) {
        messages.clear();
        command.handle(sender(), args);
        awaitChainSettled();
        return new RpgCommandResult(List.copyOf(messages));
    }

    /**
     * 等命令的异步链彻底收尾 —— 这是本类唯一允许的等待方式，且必须并发安全地取快照。
     *
     *       计数在<b>提交线程</b>上、{@code execute()} 之前递增，在任务体（含该跳
     *       {@code whenComplete} 回调）之后递减。若在任务体内递增，主线程提交完
     *       立刻查计数就可能读到「虚拟线程还没启动」的 0，等于把原缺陷换成另一个竞态。
     * <p><b>为什么旧版会假红</b>：旧实现是「连续 15 轮 × 2ms（= 30ms）无新消息即返回」。
     * 那是**时间启发式**：只要某一跳的延迟超过静默窗口（CI 上真实发生过），等待就会在
     * 播报落地前返回空清单。被测命令的 ack 路径本身是两跳
     * （{@code acknowledge} → {@code whenComplete} → 回调里再提交 {@code pending} 与
     * {@code runMain}），list 路径同样两跳（{@code pending} → 嵌套 {@code pendingCount}），
     * 所以任何「固定静默窗口」都在赌调度。</p>
     *
     * <p><b>现在的判据</b>：命令层凡有异步收尾，都是
     * {@code runMain(...)} → {@code scheduler.accept(task)} → {@link TrackingExecutorService#runOnDispatchLoop}
     * 这一条唯一路径。因此
     * <ol>
     *   <li>「异步链收尾」= 引擎已提交的每一跳都收尾（{@link TrackingExecutorService#onIdle()}）
     *       <b>且</b> 下一次派发也已被安全完成（{@link TrackingExecutorService#trackDispatch}）；</li>
     *   <li>再等一个**有界**的收敛窗口（{@link #QUIET_SETTLE_ROUNDS} 轮无新增播报）：</li>
     * </ol>
     * 第 2 步不是时间启发式的回归 —— 它只用来吸收「gate 归零 → 之后才发生的那一次派发」
     * 之间的窗口，不是完整的完成条件；超时上限 {@link #CHAIN_TIMEOUT_MS} 明确，
     * 且超时抛「异步链未收尾」，绝不静默通过。</p>
     */
    private void awaitChainSettled() {
        TrackingExecutorService observer = observedExecutor != null ? observedExecutor : trackedExecutor;
        try {
            if (!observer.onIdle().await(HOP_TIMEOUT_MS * 5, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("异步链未收尾：命令提交的 JDBC 跳在 "
                        + (HOP_TIMEOUT_MS * 5) + "ms 内未全部返回");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等异步链收尾时被中断", exception);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHAIN_TIMEOUT_MS);
        int quietRounds = 0;
        long lastSize = -1;
        while (System.nanoTime() < deadline) {
            if (observer.isBusy()) {
                // 还有在飞 / 待派发的跳：清零静默计数，继续等它收尾。
                quietRounds = 0;
                lastSize = -1;
                Thread.onSpinWait();
                continue;
            }
            int size = messages.size();
            if (size != lastSize) {
                lastSize = size;
                quietRounds = 1;
            } else if (++quietRounds > QUIET_SETTLE_ROUNDS) {
                return;
            }
            try {
                Thread.sleep(QUIET_SETTLE_SLEEP_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等命令异步链收尾时被中断", exception);
            }
        }
        throw new AssertionError("异步链未收尾：" + CHAIN_TIMEOUT_MS
                + "ms 内仍未收敛（在飞=" + observer.inFlight() + "）");
    }

    /**
     * 永久回归测试（W21-A1）：把「时间启发式等待」这类假红钉死在这里。
     *
     * <p>构造：注入给 {@link JdbcProgressionAnomalyService} 的 executor 每跳先延迟
     * {@link #SLOW_HOP_DELAY_MS} 再执行。旧版 {@code awaitQuiet()}（连续 15 轮 × 2ms
     * = 30ms 无新消息即返回）在这种延迟下**必红**（已实测，见
     * {@code .cnb/agent-out/W21-A1-fix-flaky-test-gate.json}）：命令的
     * {@code acknowledge → whenComplete → pending} 两跳各自都比 30ms 长，
     * 等待窗口在播报落地前就返回了空清单。</p>
     *
     * <p>断言仍然是「操作者真的看到了 {@code admin.anomaly-not-pending}」——
     * 没有放宽、没有删断言、也没有把异步路径改成同步；改的只是「等多久」。因此这条用例
     * 既能在旧版等待下复现假红，也能在 {@link #awaitChainSettled()} 下稳定变绿。</p>
     */
    @Test
    void slowAsyncHopStillYieldsTheExpectedBroadcast() throws Exception {
        ExecutorService slow = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // 每一跳 JDBC 任务先延迟 100ms：放大「静默窗口」假红的触发条件。
            var delayed = (java.util.concurrent.Executor) task ->
                    slow.execute(() -> {
                        LockSupport.parkNanos(SLOW_HOP_DELAY_MS * 1_000_000L);
                        task.run();
                    });
            // 延迟在底层池上做，跟踪包装只负责计数 —— 等待判据仍是「所有跳都收尾」。
            TrackingExecutorService slowObserver = new TrackingExecutorService(
                    new WrappingExecutorService(delayed));
            observedExecutor = slowObserver;
            JdbcProgressionAnomalyService slowService = new JdbcProgressionAnomalyService(dataSource, slowObserver);
            AnomalyReviewCommand slowCommand = AnomalyReviewCommand
                    .of(slowService, messages(), plugin(), slowObserver::runOnDispatchLoop).orElseThrow();

            messages.clear();
            slowCommand.handle(sender(),
                    new String[]{"anomaly", "ack", UUID.randomUUID().toString(), "CONFIRM", "slow-hop"});
            awaitChainSettled();

            List<String> observed = List.copyOf(messages);
            assertTrue(observed.stream().anyMatch(line -> line.contains("admin.anomaly-not-pending")),
                    "慢异步跳（100ms）也必须等到 not-pending 播报：" + observed);
            assertFalse(observed.stream().anyMatch(line -> line.contains("admin.anomaly-failed")),
                    "不得把「等晚了」误报成失败：" + observed);
        } finally {
            observedExecutor = null;
            slow.close();
        }
    }

    private void createSchema() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // 夹具与 Database.java 的 CREATE TABLE 同列（含 [H] 复核三列）。
            statement.execute("""
                    CREATE TABLE rpg_progression_anomalies (
                        player_uuid VARCHAR(36) PRIMARY KEY, ascension INTEGER NOT NULL,
                        level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        reason VARCHAR(128) NOT NULL,
                        detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        reviewer VARCHAR(64), review_note VARCHAR(200), reviewed_at TIMESTAMP)
                    """);
            connection.commit();
        }
    }

    private String text(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private long number(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private CommandSender sender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        if (args != null && args.length > 0) {
                            Object last = args[args.length - 1];
                            if (last instanceof net.kyori.adventure.text.Component component) {
                                messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            } else if (last instanceof String text) {
                                messages.add(text);
                            }
                        }
                        yield null;
                    }
                    case "getName" -> OPERATOR;
                    case "name" -> net.kyori.adventure.text.Component.text(OPERATOR);
                    case "hasPermission", "isPermissionSet", "isOp" -> true;
                    default -> null;
                });
    }

    /** 只渲染键与占位符：断言能同时证明「哪条文案」与「数值真的到了操作者眼前」。 */
    private static MessageService messages() {
        return (MessageService) Proxy.newProxyInstance(
                MessageService.class.getClassLoader(), new Class<?>[]{MessageService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "text" -> {
                        StringBuilder rendered = new StringBuilder(args[1] == null ? "" : (String) args[1]);
                        if (args.length >= 3 && args[2] instanceof java.util.Map<?, ?> values) {
                            rendered.append(' ').append(values);
                        }
                        yield rendered.toString();
                    }
                    case "lines" -> List.of();
                    case "supportedLocales" -> java.util.Set.of("en_us");
                    case "defaultLocale", "locale" -> "en_us";
                    case "setLocale" -> false;
                    default -> null;
                });
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> java.util.logging.Logger.getAnonymousLogger();
                    default -> null;
                });
    }

    private record RpgCommandResult(List<String> messages) {
    }

    /** 把裸 {@link Executor}（慢委托）适配成只读的 {@link ExecutorService}：任务仍由原 executor 跑。 */
    private static final class WrappingExecutorService extends java.util.concurrent.AbstractExecutorService {
        private final java.util.concurrent.Executor delegate;

        WrappingExecutorService(java.util.concurrent.Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }

    /**
     * 只观测、不改语义的 {@link ExecutorService} 包装：把「命令提交的每一跳」变成可观测量。
     *
     * <p>它是本类确定性等待的基础。三条约束：</p>
     * <ol>
     *   <li>{@link #execute(Runnable)} <b>同步包装</b>：先在委托线程上登记在飞、再提交底层池，
     *       保证「任务已排队」在 {@code execute} 返回前就可见，消灭
     *       「gate 归零后才登记」的窗口；</li>
     *   <li>提交被拒时在委托线程上结算在飞，既不泄漏计数也不吞掉拒绝；</li>
     *   <li>命令层的派发（{@code runMain}）走 {@link #runOnDispatchLoop(Runnable)}，
     *       同样被在飞计数覆盖 —— 这正是两跳异步（回调里再提交一跳）的收尾判据。</li>
     * </ol>
     */
    private static final class TrackingExecutorService implements ExecutorService {
        private final ExecutorService delegate;
        private final AtomicInteger dispatched = new AtomicInteger();
        private final AtomicInteger settled = new AtomicInteger();
        /** 每次计数变化都重建：{@link #onIdle()} 的等待者据此被唤醒复查。 */
        private volatile CountDownLatch idleLatch = new CountDownLatch(1);

        TrackingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        /** 命令层的真同步派发：直接跑，但同样被在飞计数覆盖。 */
        void runOnDispatchLoop(Runnable task) {
            trackDispatch(task).run();
        }

        int inFlight() {
            return Math.max(0, dispatched.get() - settled.get());
        }

        /** 命令层派发的「最后一步」是否还没被安全完成。 */
        private boolean dispatching() {
            return dispatched.get() > settled.get();
        }

        boolean isBusy() {
            return inFlight() > 0 || dispatching();
        }

        /** 追踪一次命令层派发，返回真正在派发器上执行收尾的 Runnable。 */
        Runnable trackDispatch(Runnable task) {
            dispatched.incrementAndGet();
            wakeWaiters();
            return () -> {
                try {
                    task.run();
                } finally {
                    settled.incrementAndGet();
                    wakeWaiters();
                }
            };
        }

        @Override
        public void execute(Runnable task) {
            dispatched.incrementAndGet();
            wakeWaiters();
            Runnable settledTask = () -> {
                try {
                    task.run();
                } finally {
                    settled.incrementAndGet();
                    wakeWaiters();
                }
            };
            try {
                delegate.execute(settledTask);
            } catch (RuntimeException | Error rejected) {
                settled.incrementAndGet();
                wakeWaiters();
                throw rejected;
            }
        }

        /** 等「引擎提交的每一跳 + 命令层每一次派发」都收尾。 */
        CountDownLatch onIdle() {
            while (isBusy()) {
                CountDownLatch latch = idleLatch;
                if (!isBusy()) return new CountDownLatch(0);
                try {
                    latch.await(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new CountDownLatch(0);
                }
            }
            return new CountDownLatch(0);
        }

        private void wakeWaiters() {
            idleLatch = new CountDownLatch(1);
            idleLatch.countDown();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            return delegate.submit(track(task));
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            return delegate.submit(track(task), result);
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            return delegate.submit(track(task));
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) throws InterruptedException {
            return delegate.invokeAll(tasks);
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks)
                throws InterruptedException, java.util.concurrent.ExecutionException {
            return delegate.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                               long timeout, TimeUnit unit)
                throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
            return delegate.invokeAny(tasks, timeout, unit);
        }

        private <T> java.util.concurrent.Callable<T> track(java.util.concurrent.Callable<T> task) {
            return () -> {
                dispatched.incrementAndGet();
                wakeWaiters();
                try {
                    return task.call();
                } finally {
                    settled.incrementAndGet();
                    wakeWaiters();
                }
            };
        }

        private Runnable track(Runnable task) {
            return new Runnable() {
                @Override
                public void run() {
                    dispatched.incrementAndGet();
                    wakeWaiters();
                    try {
                        task.run();
                    } finally {
                        settled.incrementAndGet();
                        wakeWaiters();
                    }
                }
            };
        }
    }
}
