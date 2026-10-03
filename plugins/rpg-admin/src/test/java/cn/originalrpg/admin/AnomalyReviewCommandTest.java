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
     * 等待异步链收尾的硬上限（毫秒）。超时即断言失败，绝不静默通过。
     *
     * <p>原实现用「连续 15 轮 × 2ms 无新消息 = 30ms」当收尾判据（时间启发式）。
     * 实测（W21-A1 证据）在 CI 高负载下，两跳异步链某一跳 >30ms 才落地，
     * {@code awaitQuiet()} 就会在播报到达前返回，得到空消息列表：
     * {@code AssertionFailedError: [] ==> expected: <true> but was: <false>}
     * （{@code ack} 的 {@code missing} 分支，命令第 3 条断言）。
     * 同一 commit 两次门禁先红后绿，正是「十门全绿」不可复现的来源。</p>
     */
    private static final long AWAIT_QUIET_TIMEOUT_MILLIS = 10_000L;
    /** 「在飞任务归零」需连续成立的检查次数：跨过回调线程与断言线程之间的调度间隙。 */
    private static final int QUIET_CONFIRMATIONS = 3;
    /** 每次检查之间的让出间隔（毫秒）。只影响轮询粒度，不再是收尾判据本身。 */
    private static final long QUIET_POLL_MILLIS = 2L;

    private HikariDataSource dataSource;
    private ExecutorService executor;
    /**
     * 在飞任务计数：{@code awaitQuiet()} 的确定性收尾判据。
     * 递增在提交线程上、{@code execute()} 之前；递减在任务体（含该跳
     * {@code whenComplete} 回调）之后。因此 {@code inFlight==0} 严格意味着
     * 「每一跳都已连同其收尾回调执行完」，即播报已落地。
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
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
        // 注入可跟踪执行器：被测服务仍跑真实虚拟线程 + 真实 JDBC 事务，
        // 只在提交/收尾处裹一层计数 —— 不改异步路径本身（改成同步就等于改掉被测行为）。
        service = new JdbcProgressionAnomalyService(dataSource, tracked(executor));
        // Runnable::run = 真同步：命令返回时事务已提交，断言读到的就是数据库里的那一格。
        command = AnomalyReviewCommand.of(service, messages(), plugin(), Runnable::run).orElseThrow();
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
     * 「等稳定」而不是固定 sleep —— 不引入时序 flake，也不会因为池慢了而漏断言。
     */
    private RpgCommandResult run(String... args) {
        messages.clear();
        command.handle(sender(), args);
        awaitQuiet();
        return new RpgCommandResult(new ArrayList<>(messages));
    }

    /**
     * 等命令的异步链彻底收尾：**确定性**等待在飞任务归零，而不是等「静默若干毫秒」。
     *
     * <p>为什么这样是确定性的，而不是把 30ms 换成更大的数：</p>
     * <ol>
     *   <li>计数在 {@link #tracked} 里于**提交线程**上、{@code execute()} 之前递增，
     *       在任务体（含该跳 {@code whenComplete} 回调）之后递减。若在任务体内递增，
     *       主线程提交完立刻查计数就可能读到「虚拟线程还没启动」的 0 ——
     *       那就是把原缺陷换成另一个竞态（W21-A1 实测：这样改仍会复现空消息红）。</li>
     *   <li>{@code whenComplete} 回调与下一跳 {@code supplyAsync} 的提交都发生在该跳
     *       任务体**之内**，所以「归零」天然晚于「播报落地」，两跳之间不会被误判为完成。</li>
     *   <li>{@link #QUIET_CONFIRMATIONS} 次连续归零进一步覆盖回调线程与断言线程的调度间隙。</li>
     * </ol>
     *
     * <p>超时（{@link #AWAIT_QUIET_TIMEOUT_MILLIS}）抛错并报出「异步链未收尾」，
     * 绝不静默通过 —— 原实现最危险之处正是在链未收尾时返回空列表，
     * 把「异步真失败」与「等得不够久」混成同一种红。</p>
     */
    private void awaitQuiet() {
        long deadline = System.nanoTime() + AWAIT_QUIET_TIMEOUT_MILLIS * 1_000_000L;
        int confirmed = 0;
        while (confirmed < QUIET_CONFIRMATIONS) {
            if (inFlight.get() == 0) {
                confirmed++;
            } else {
                confirmed = 0;
            }
            if (confirmed >= QUIET_CONFIRMATIONS) break;
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("异步链未收尾：在飞任务=" + inFlight.get()
                        + " 已等 " + AWAIT_QUIET_TIMEOUT_MILLIS + "ms，messages=" + List.copyOf(messages));
            }
            Thread.onSpinWait();
            try {
                Thread.sleep(QUIET_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("异步链等待被中断：在飞任务=" + inFlight.get(), exception);
            }
        }
    }

    /**
     * 可跟踪执行器：真实执行交给虚拟线程池，只在提交/收尾处维护在飞计数。
     * 递增必须在提交线程上（见 {@link #awaitQuiet()} 第 1 条理由）。
     */
    private java.util.concurrent.Executor tracked(ExecutorService delegate) {
        return task -> {
            inFlight.incrementAndGet();
            delegate.execute(() -> {
                try {
                    task.run();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        };
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
}
