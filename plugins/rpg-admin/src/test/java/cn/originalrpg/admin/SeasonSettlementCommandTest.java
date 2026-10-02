package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.ChapterTransitionResult;
import cn.originalrpg.api.model.SeasonSettlementResult;
import cn.originalrpg.api.rules.SeasonSettlementState;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.SeasonSettlementService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-101 剩余半条的行为级测试：{@code /rpgadmin season} 必须真的能把九态从 DRAFT 驱动到 COMPLETED。
 *
 * <p>这里是**命令层**测试而不是服务层测试：判据是「管理面入口真的接上了」。
 * 所以每一条用例都从 {@link RpgAdminPlugin#onCommand} 进（或从同一个
 * {@code SeasonSettlementCommand} 例程进），再断言数据库里的那一格。</p>
 *
 * <p>被测服务不是假的：它是 {@code JdbcSeasonSettlementService} 的真实子类
 * （见 {@link Harness#service}），跑在真实 H2 + 真实 JDBC 事务上。</p>
 */
class SeasonSettlementCommandTest {
    private static final String SEASON = "launch-1";
    private static final String NEXT_SEASON = "launch-2";
    private static final String ACTOR = "console";
    private static final String REASON = "criterion accepted";

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private Harness harness;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:admin_season_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        createSchema();
        harness = new Harness(dataSource, Runnable::run, true);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    // ------------------------------------------------------------ 全链路

    @Test
    void adminEntryRunsTheWholeChainOneStepAtATimeAndEachStepIsPersisted() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);

        assertEquals("DRAFT", storedStatus());
        RpgCommandResult status = harness.run("season", "status");
        assertTrue(status.messages.stream().anyMatch(line -> line.contains("admin.season-current")
                && line.contains("state=DRAFT")), status.messages.toString());

        RpgCommandResult preview = harness.run("season", "preview");
        // 预览必须把「会结算掉多少赛季层功勋」摆到操作者眼前，否则无法据此审批。
        assertTrue(preview.messages.stream().anyMatch(line -> line.contains("admin.season-preview")
                && line.contains("season-merit=300") && line.contains("retained=500")),
                "预览应显示赛季层与永久层合计：" + preview.messages);
        // preview 是只读分支：九态仍在 DRAFT，一行都没写。
        assertEquals("DRAFT", storedStatus());
        assertEquals(0L, count("rpg_season_settlements"));
        assertEquals(0L, count("rpg_merit_ledger"));

        // 每一格都通过管理面命令推进，并立刻回读数据库状态。
        advanceThrough("previewed", SeasonSettlementState.PREVIEWED, null);
        advanceThrough("approved", SeasonSettlementState.APPROVED, null);
        advanceThrough("freezing", SeasonSettlementState.FREEZING, null);
        advanceThrough("settling", SeasonSettlementState.SETTLING, NEXT_SEASON);
        advanceThrough("verifying", SeasonSettlementState.VERIFYING, null);
        advanceThrough("completed", SeasonSettlementState.COMPLETED, null);

        assertEquals("COMPLETED", storedStatus());
        // 资金动作真的发生了：赛季层归零、账本入账、钱包打到新赛季。
        assertEquals(0L, number("SELECT COALESCE(SUM(season_merit), 0) FROM rpg_season_merit_wallets"));
        assertEquals(NEXT_SEASON, text("SELECT season_id FROM rpg_season_merit_wallets"));
        assertEquals(300L, number("SELECT COALESCE(SUM(amount), 0) FROM rpg_merit_ledger"));
        // 永久层不受换季影响。
        assertEquals(500L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(6L, count("rpg_season_settlement_transitions"));
    }

    @Test
    void illegalTransitionThroughTheAdminEntryIsRejectedAndWritesNothing() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 200, SEASON, 100);

        // 跳态：DRAFT 直接要求结算（资金动作），状态机必须拒绝。
        RpgCommandResult skipped = harness.run(
                "season", "advance", "settling", AdminConfirmation.LITERAL, NEXT_SEASON, "skip ahead");
        assertEquals(SeasonSettlementResult.Status.ILLEGAL_TRANSITION, harness.lastStatus(skipped));
        assertEquals("DRAFT", storedStatus());
        assertEquals(0L, count("rpg_season_settlements"));
        assertEquals(0L, count("rpg_season_settlement_transitions"));
        assertEquals(100L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        assertEquals(0L, count("rpg_merit_ledger"));

        // 倒着走同样被拒。
        advanceThrough("previewed", SeasonSettlementState.PREVIEWED, null);
        RpgCommandResult backwards = harness.run(
                "season", "advance", "draft", AdminConfirmation.LITERAL, "roll it back");
        assertEquals(SeasonSettlementResult.Status.ILLEGAL_TRANSITION, harness.lastStatus(backwards));
        assertEquals("PREVIEWED", storedStatus());
    }

    @Test
    void settlingToTheSameSeasonIsRejectedBecauseThatIsFakeRotation() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);
        advanceThrough("previewed", SeasonSettlementState.PREVIEWED, null);
        advanceThrough("approved", SeasonSettlementState.APPROVED, null);
        advanceThrough("freezing", SeasonSettlementState.FREEZING, null);

        // 不给下一赛季 = 结算到自己身上，正是 C-010 禁止的「只改 season-id 假装换季」。
        RpgCommandResult same = harness.run(
                "season", "advance", "settling", AdminConfirmation.LITERAL, "no rotation given");
        assertEquals(SeasonSettlementResult.Status.INVALID, harness.lastStatus(same));
        assertEquals("FREEZING", storedStatus());
        assertEquals(300L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        assertEquals(0L, count("rpg_merit_ledger"));
    }

    // ------------------------------------------------------------ 误触拦截

    @Test
    void advanceWithoutTheConfirmLiteralNeverReachesTheLedger() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);

        // 连 PREVIEWED 都不推进：没有 CONFIRM 时命令在本地就停下。
        // 只有 4 个参数：没有 CONFIRM 字面量 ⇒ 命令层直接给用法，不转发给状态机。
        RpgCommandResult refused = harness.run("season", "advance", "previewed", "just do it");
        assertTrue(refused.messages.stream().anyMatch(line -> line.contains("admin.season-usage")),
                refused.messages.toString());
        assertEquals("DRAFT", storedStatus());
        assertEquals(0L, count("rpg_season_settlements"));
        assertEquals(0L, count("rpg_merit_ledger"));
        assertEquals(0L, harness.advanceCalls());
    }

    @Test
    void unknownTargetNameIsRejectedBeforeAnyDatabaseWork() throws Exception {
        RpgCommandResult unknown = harness.run(
                "season", "advance", "finishing", AdminConfirmation.LITERAL, "typo on purpose");
        assertTrue(unknown.messages.stream().anyMatch(line -> line.contains("admin.season-unknown")
                && line.contains("target=finishing")), unknown.messages.toString());
        assertEquals(0L, harness.advanceCalls());
        assertEquals(0L, count("rpg_season_settlements"));
    }

    // ------------------------------------------------------------ 开关 / 熔断

    @Test
    void adminSwitchOffFailsClosedAndTouchesNothing() throws Exception {
        Harness disabled = new Harness(dataSource, Runnable::run, false);
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);

        for (String[] args : List.of(
                new String[]{"season", "status"},
                new String[]{"season", "preview"},
                new String[]{"season", "advance", "previewed", AdminConfirmation.LITERAL, "try anyway"})) {
            RpgCommandResult result = disabled.run(args);
            assertTrue(result.messages.stream().anyMatch(line -> line.contains("admin.season-disabled")),
                    "开关关闭时必须显式拒绝：" + result.messages);
        }
        assertEquals(0L, disabled.advanceCalls());
        assertEquals("DRAFT", storedStatus());
        assertEquals(0L, count("rpg_season_settlements"));
        assertEquals(0L, count("rpg_merit_ledger"));
        assertEquals(300L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
    }

    @Test
    void killSwitchOffBlocksAdvanceThroughTheAdminEntry() throws Exception {
        uuidInfrastructure();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by) "
                    + "VALUES ('season.settlement.enabled', FALSE, 'test')");
            connection.commit();
        }
        Harness blockedHarness = new Harness(dataSource, Runnable::run, true, true);
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);

        RpgCommandResult blocked = blockedHarness.run(
                "season", "advance", "previewed", AdminConfirmation.LITERAL, "kill switch is off");
        assertEquals(SeasonSettlementResult.Status.BLOCKED, blockedHarness.lastStatus(blocked));
        assertEquals("DRAFT", storedStatus());
        assertEquals(0L, count("rpg_season_settlements"));
        assertEquals(0L, count("rpg_season_settlement_transitions"));
        assertEquals(300L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
    }

    // ------------------------------------------------------------ tab 补全

    @Test
    void tabCompletionOffersTheNineStatesAndTheConfirmLiteral() {
        assertEquals(List.of("status", "preview", "advance"), SeasonSettlementCommand.complete(
                new String[]{"season", ""}));
        assertEquals(List.of("settling"), SeasonSettlementCommand.complete(
                new String[]{"season", "advance", "settli"}));
        assertEquals(List.of(), SeasonSettlementCommand.complete(
                new String[]{"season", "advance", "settling", "no"}));
        assertEquals(List.of(AdminConfirmation.LITERAL), SeasonSettlementCommand.complete(
                new String[]{"season", "advance", "settling", "CONF"}));
        assertEquals(9, SettlementTarget.names().size());
    }

    // ------------------------------------------------------------ helpers

    private void advanceThrough(String token, SeasonSettlementState expected, String nextSeason) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "season", "advance", token, AdminConfirmation.LITERAL));
        if (nextSeason != null) args.add(nextSeason);
        args.add(REASON);
        RpgCommandResult result = harness.run(args.toArray(new String[0]));
        assertEquals(SeasonSettlementResult.Status.APPLIED, harness.lastStatus(result),
                "expected " + expected + " but got " + result.messages);
        assertEquals(expected.name(), storedStatus());
    }

    /** 落库状态；尚未建账（没有行）时按契约读作 DRAFT。 */
    private String storedStatus() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT status FROM rpg_season_settlements ORDER BY created_at DESC LIMIT 1")) {
            return result.next() ? result.getString(1) : SeasonSettlementState.DRAFT.name();
        }
    }

    private long count(String table) throws SQLException {
        return number("SELECT COUNT(*) FROM " + table);
    }

    private long count(String table, String where) throws SQLException {
        return number("SELECT COUNT(*) FROM " + table + " WHERE " + where);
    }

    private String text(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void wallet(UUID playerId, long permanent, String seasonId, long seasonal) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit, season_id) VALUES ('"
                        + playerId + "', " + permanent + ", '" + seasonId + "')");
                statement.execute("INSERT INTO rpg_season_merit_wallets (player_uuid, season_id, season_merit) VALUES ('"
                        + playerId + "', '" + seasonId + "', " + seasonal + ")");
            }
            connection.commit();
        }
    }

    private void uuidInfrastructure() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rpg_mechanism_switches (
                        mechanism_id VARCHAR(64) PRIMARY KEY, enabled BOOLEAN NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_by VARCHAR(64) NOT NULL)
                    """);
            connection.commit();
        }
    }

    private void createSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_progression_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, merit BIGINT NOT NULL DEFAULT 0,
                        season_id VARCHAR(32),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_merit_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        season_merit BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_merit_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, amount BIGINT NOT NULL,
                        direction VARCHAR(8) NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlements (
                        id VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        status VARCHAR(24) NOT NULL, preview_hash VARCHAR(64), fact_digest VARCHAR(64),
                        approved_by VARCHAR(64),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_gate (
                        singleton_id INTEGER PRIMARY KEY, settlement_id VARCHAR(36),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_transitions (
                        id VARCHAR(36) PRIMARY KEY, settlement_id VARCHAR(36) NOT NULL,
                        from_state VARCHAR(24) NOT NULL, to_state VARCHAR(24) NOT NULL,
                        actor VARCHAR(64) NOT NULL, reason VARCHAR(128) NOT NULL,
                        fact_digest VARCHAR(64), request_hash VARCHAR(64) NOT NULL,
                        transaction_version BIGINT NOT NULL, committed_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_progress (
                        settlement_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        merit_debited BIGINT NOT NULL, committed_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (settlement_id, player_uuid))
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_player_progress (
                        season_id VARCHAR(32) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        honor BIGINT NOT NULL DEFAULT 0, collection_stamps INTEGER NOT NULL DEFAULT 0,
                        completed_runs INTEGER NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (season_id, player_uuid))
                    """);
            connection.commit();
        }
    }

    /** 管理面例程的测试夹具：真服务 + 假 Bukkit 外壳，只记录「转发了几次」。 */
    private static final class Harness {
        private static final ChapterState CHAPTER =
                new ChapterState(SEASON, "awakening", 0, 0, 1L, java.time.Instant.EPOCH);

        private final SeasonSettlementService service;
        private final CountingService counting;
        private final SeasonSettlementCommand command;
        private final List<String> messages = new ArrayList<>();

        Harness(HikariDataSource dataSource, Executor executor, boolean enabled) {
            this(dataSource, executor, enabled, false);
        }

        Harness(HikariDataSource dataSource, Executor executor, boolean enabled, boolean withKillSwitch) {
            if (withKillSwitch) {
                var registry = new cn.originalrpg.core.persistence.MechanismRegistry(dataSource);
                registry.initialize();
                this.service = new cn.originalrpg.core.persistence.JdbcSeasonSettlementService(
                        dataSource, executor, registry);
            } else {
                this.service = new cn.originalrpg.core.persistence.JdbcSeasonSettlementService(
                        dataSource, executor, null);
            }
            this.counting = new CountingService(this.service);
            this.command = SeasonSettlementCommand.of(
                    plugin(), counting, chapterService(), messages(), enabled).orElseThrow();
        }

        /** args 以子命令名开头（如 {@code season status}），与 onCommand 收到的形状一致。 */
        RpgCommandResult run(String... args) {
            messages.clear();
            command.handle(sender(messages), args);
            return new RpgCommandResult(new ArrayList<>(messages));
        }

        long advanceCalls() {
            return counting.advanceCalls;
        }

        SeasonSettlementResult.Status lastStatus(RpgCommandResult result) {
            return counting.lastStatus;
        }

        private static MessageService messages() {
            return (MessageService) Proxy.newProxyInstance(
                    MessageService.class.getClassLoader(), new Class<?>[]{MessageService.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        // 渲染 {占位符}：断言才有「状态/数字真的到了操作者眼前」这一条。
                        // 语言包不在测试里加载：把键与占位符都渲染进文本，
                        // 这样断言能同时证明「哪条文案」与「数值真的到了操作者眼前」。
                        case "text" -> {
                            String key = (String) args[1];
                            StringBuilder rendered = new StringBuilder(key == null ? "" : key);
                            if (args.length >= 3 && args[2] instanceof java.util.Map<?, ?> values) {
                                rendered.append(' ').append(values);
                            }
                            yield rendered.toString();
                        }
                        case "lines" -> List.of();
                        case "supportedLocales" -> java.util.Set.of("en_us");
                        case "defaultLocale" -> "en_us";
                        case "locale" -> "en_us";
                        case "setLocale" -> false;
                        default -> null;
                    });
        }

        private static ChapterService chapterService() {
            return new ChapterService() {
                @Override
                public ChapterState current() {
                    return CHAPTER;
                }

                @Override
                public List<cn.originalrpg.api.model.ChapterDefinition> definitions() {
                    return List.of();
                }

                @Override
                public java.util.Optional<cn.originalrpg.api.model.ChapterDefinition> next() {
                    return java.util.Optional.empty();
                }

                @Override
                public CompletionStage<ChapterState> refresh() {
                    return java.util.concurrent.CompletableFuture.completedFuture(CHAPTER);
                }

                @Override
                public CompletionStage<ChapterTransitionResult> advance(
                        String targetChapterId, String actor, String reason
                ) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        private Plugin plugin() {
            return (Plugin) Proxy.newProxyInstance(
                    Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getServer" -> server();
                        case "getLogger" -> java.util.logging.Logger.getAnonymousLogger();
                        // 同步执行：测试要断言「命令返回时状态已落库」。
                        default -> null;
                    });
        }

        private org.bukkit.Server server() {
            return (org.bukkit.Server) Proxy.newProxyInstance(
                    org.bukkit.Server.class.getClassLoader(), new Class<?>[]{org.bukkit.Server.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getScheduler" -> scheduler();
                        default -> null;
                    });
        }

        private org.bukkit.scheduler.BukkitScheduler scheduler() {
            return (org.bukkit.scheduler.BukkitScheduler) Proxy.newProxyInstance(
                    org.bukkit.scheduler.BukkitScheduler.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.scheduler.BukkitScheduler.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("runTask") && args != null && args.length >= 2
                                && args[1] instanceof Runnable task) {
                            task.run();
                            return null;
                        }
                        return null;
                    });
        }

    }

    /** 记录播报的极简 CommandSender：只实现命令层真正用到的成员。 */
    private static CommandSender sender(List<String> sink) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        if (args != null && args.length > 0) {
                            Object last = args[args.length - 1];
                            if (last instanceof net.kyori.adventure.text.Component component) {
                                sink.add(net.kyori.adventure.text.serializer.plain
                                        .PlainTextComponentSerializer.plainText().serialize(component));
                            } else if (last instanceof String text) {
                                sink.add(text);
                            }
                        }
                        yield null;
                    }
                    case "getName" -> ACTOR;
                    case "name" -> net.kyori.adventure.text.Component.text(ACTOR);
                    case "hasPermission", "isPermissionSet", "isOp" -> true;
                    default -> null;
                });
    }

    private record RpgCommandResult(List<String> messages) {
    }

    /** 只计数 + 记录最后一次状态：断言「命令有没有真的把动作转发出去」。 */
    private static final class CountingService implements SeasonSettlementService {
        private final SeasonSettlementService delegate;
        private long advanceCalls;
        private SeasonSettlementResult.Status lastStatus;

        CountingService(SeasonSettlementService delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<SeasonSettlementState> state(String seasonId) {
            return delegate.state(seasonId);
        }

        @Override
        public CompletionStage<cn.originalrpg.api.model.SeasonSettlementPreview> preview(String seasonId) {
            return delegate.preview(seasonId);
        }

        @Override
        public CompletionStage<SeasonSettlementResult> advance(
                String seasonId, SeasonSettlementState target, String actor, String reason
        ) {
            return record(delegate.advance(seasonId, target, actor, reason));
        }

        @Override
        public CompletionStage<SeasonSettlementResult> advance(
                String seasonId, String nextSeasonId, SeasonSettlementState target, String actor, String reason
        ) {
            return record(delegate.advance(seasonId, nextSeasonId, target, actor, reason));
        }

        private CompletionStage<SeasonSettlementResult> record(CompletionStage<SeasonSettlementResult> stage) {
            advanceCalls++;
            return stage.thenApply(result -> {
                lastStatus = result.status();
                return result;
            });
        }
    }

}
