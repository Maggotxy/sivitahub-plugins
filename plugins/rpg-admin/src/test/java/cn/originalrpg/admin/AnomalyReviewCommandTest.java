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
    /** 「静默」判定所需的无新消息轮数（每轮 2ms）。 */
    private static final int QUIET_ROUNDS = 15;

    private HikariDataSource dataSource;
    private ExecutorService executor;
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
        service = new JdbcProgressionAnomalyService(dataSource, executor);
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
     * 等命令的异步链彻底收尾：至少让出 {@link #QUIET_ROUNDS} 轮、且期间没有新的播报。
     *
     * <p>「连续若干轮无新消息」而不是固定 sleep：ack 是「写 → 回读 → 播报」两跳，
     * 只等一轮会在第二跳落地前就断言。
     */
    private void awaitQuiet() {
        int quiet = 0;
        for (int attempt = 0; attempt < 1000 && quiet < QUIET_ROUNDS; attempt++) {
            int before = messages.size();
            Thread.onSpinWait();
            try {
                Thread.sleep(2L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            quiet = messages.size() == before ? quiet + 1 : 0;
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
}
