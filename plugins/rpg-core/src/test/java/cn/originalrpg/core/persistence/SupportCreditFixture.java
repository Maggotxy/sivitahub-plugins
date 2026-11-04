package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SignedSupportReceipt;
import cn.originalrpg.api.model.SupportCosmetic;
import cn.originalrpg.api.model.SupportReceipt;
import cn.originalrpg.api.support.SupportReceiptCodec;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises production migrations in H2 or a new, explicitly selected PostgreSQL test schema. */
final class SupportCreditFixture implements AutoCloseable {
    static final String AUDIENCE = "sivita-test-world";
    final Database database;
    final DataSource dataSource;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final SupportCreditPolicy policy;
    final JdbcSupportCreditService service;
    private final String postgresUrl;
    private final String postgresUser;
    private final String postgresPassword;
    private String schema;

    SupportCreditFixture() throws Exception { this(1_000, 2_000, 4_000); }
    SupportCreditFixture(long maximumPerOrder, long playerLimit, long globalLimit) throws Exception {
        if (System.getenv("RPG_DATABASE_URL") != null && !System.getenv("RPG_DATABASE_URL").isBlank()) {
            throw new IllegalStateException("Support tests refuse RPG_DATABASE_URL; use SIVITA_SUPPORT_TEST_POSTGRES_URL");
        }
        postgresUrl = System.getenv("SIVITA_SUPPORT_TEST_POSTGRES_URL");
        postgresUser = System.getenv().getOrDefault("SIVITA_SUPPORT_TEST_POSTGRES_USER", "postgres");
        postgresPassword = System.getenv().getOrDefault("SIVITA_SUPPORT_TEST_POSTGRES_PASSWORD", "");
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", "jdbc:h2:mem:support_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        if (postgresUrl != null && !postgresUrl.isBlank()) {
            if (!postgresUrl.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("PostgreSQL test URL required");
            schema = "sivita_support_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection connection = DriverManager.getConnection(postgresUrl, postgresUser, postgresPassword);
                 Statement statement = connection.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
            config.set("database.jdbc-url", postgresUrl + (postgresUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            config.set("database.username", postgresUser);
            config.set("database.password", postgresPassword);
        }
        config.set("database.maximum-pool-size", 8);
        Database opened = null;
        try {
            opened = Database.open(config);
            opened.migrate();
            database = opened;
            dataSource = database.dataSource();
        } catch (Exception error) {
            if (opened != null) opened.close();
            executor.close();
            dropSchema();
            throw error;
        }
        policy = new SupportCreditPolicy(true, AUDIENCE, Map.of("test-key", keyPair.getPublic()),
                maximumPerOrder, playerLimit, globalLimit, Duration.ofHours(1), Duration.ofSeconds(30),
                Map.of(SupportCosmetic.PRISMATIC_AURA, 120L, SupportCosmetic.ELYTRA_WINGS, 200L,
                        SupportCosmetic.ELYTRA_COMET, 300L));
        service = service(dataSource);
    }

    JdbcSupportCreditService service(DataSource source) {
        return new JdbcSupportCreditService(source, executor, policy, enabled::get, clock);
    }

    SupportReceipt receipt(UUID player, long amount, String order) {
        return new SupportReceipt(1, "test-key", AUDIENCE, order, player, amount,
                clock.instant(), clock.instant().plusSeconds(600), SupportReceipt.CURRENCY);
    }
    SignedSupportReceipt signed(UUID player, long amount, String order) throws Exception { return sign(receipt(player, amount, order)); }
    SignedSupportReceipt sign(SupportReceipt receipt) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(SupportReceiptCodec.bytes(receipt));
        return new SignedSupportReceipt(SupportReceiptCodec.encode(receipt), Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
    }

    long count(String table) throws SQLException {
        if (!table.matches("rpg_[a-z_]+")) throw new IllegalArgumentException("Invalid table name");
        return scalar("SELECT COUNT(*) FROM " + table);
    }
    long scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
    }
    void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql); connection.commit();
        }
    }

    /** Faults occur around real JDBC actions; rollback and replay use the real durable rows. */
    static DataSource fault(DataSource source, String point, Runnable afterAccountLock) {
        return fault(source, point, afterAccountLock, null);
    }
    static DataSource fault(DataSource source, String point, Runnable afterAccountLock, Runnable beforeCommit) {
        AtomicBoolean fired = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            Object value;
            try { value = method.invoke(source, args); } catch (InvocationTargetException error) { throw error.getCause(); }
            if (!(value instanceof Connection connection)) return value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (cp, cm, ca) -> {
                if (cm.getName().equals("commit") && point.equals("hold-commit") && fired.compareAndSet(false, true)) beforeCommit.run();
                if (cm.getName().equals("commit") && point.equals("before-commit") && fired.compareAndSet(false, true)) throw new SQLException("Injected before commit");
                Object result;
                try { result = cm.invoke(connection, ca); } catch (InvocationTargetException error) { throw error.getCause(); }
                if (cm.getName().equals("commit") && point.equals("after-commit") && fired.compareAndSet(false, true)) throw new SQLException("Injected lost commit response");
                if (!(result instanceof PreparedStatement statement) || ca == null || !(ca[0] instanceof String sql)) return result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                    if (sm.getName().equals("executeUpdate") && point.equals("gate-update")
                            && sql.contains("rpg_mechanism_switches") && fired.compareAndSet(false, true)) afterAccountLock.run();
                    if (sm.getName().equals("executeUpdate") && point.equals("entitlement")
                            && sql.contains("INSERT INTO rpg_support_cosmetic_entitlements") && fired.compareAndSet(false, true)) {
                        throw new SQLException("Injected entitlement write failure");
                    }
                    if (sm.getName().equals("executeUpdate") && point.equals("ledger")
                            && sql.contains("INSERT INTO rpg_support_credit_ledger") && fired.compareAndSet(false, true)) {
                        throw new SQLException("Injected audit ledger write failure");
                    }
                    Object answer;
                    try { answer = sm.invoke(statement, sa); } catch (InvocationTargetException error) { throw error.getCause(); }
                    if (sm.getName().equals("executeQuery") && sql.contains("SELECT balance FROM rpg_support_credit_accounts")
                            && sql.contains("FOR UPDATE") && afterAccountLock != null && fired.compareAndSet(false, true)) afterAccountLock.run();
                    return answer;
                });
            });
        });
    }
    private void dropSchema() throws SQLException {
        if (schema == null) return;
        try (Connection connection = DriverManager.getConnection(postgresUrl, postgresUser, postgresPassword);
             Statement statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        schema = null;
    }
    @Override public void close() throws Exception { executor.close(); database.close(); dropSchema(); }
    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant next) { now = next; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
