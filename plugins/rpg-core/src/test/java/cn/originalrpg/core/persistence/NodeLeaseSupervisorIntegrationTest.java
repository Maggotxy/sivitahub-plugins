package cn.originalrpg.core.persistence;

import cn.originalrpg.core.NodeLeaseSupervisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2(PostgreSQL 模式) 行为级测试：真跑 {@link JdbcNodeLease#renewAsync()}，
 * 断言「租约有效」与「租约失效」两条路径对消费方的可观察差异。
 */
final class NodeLeaseSupervisorIntegrationTest {
    private static final Logger LOGGER =
            Logger.getLogger(NodeLeaseSupervisorIntegrationTest.class.getName());

    private HikariDataSource dataSource;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:rpg_lease_test_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_node_leases (
                        node_id VARCHAR(64) PRIMARY KEY,
                        process_token VARCHAR(36) NOT NULL,
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.close();
        if (dataSource != null) dataSource.close();
    }

    @Test
    void renewingLeaseKeepsTheNodeServingWithoutShutdown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        JdbcNodeLease lease = new JdbcNodeLease(
                dataSource, executor, "realm-live-01", UUID.randomUUID(),
                Duration.ofSeconds(30), clock);
        assertTrue(lease.acquire());
        RecordingConsumer consumer = new RecordingConsumer();

        assertTrue(consumer.supervise(lease, clock).renewOnce().toCompletableFuture().join());
        assertTrue(lease.mayStillBeValid());
        assertTrue(consumer.reasons.isEmpty());
    }

    @Test
    void replacementProcessFencesTheOldNodeThroughTheRealRenewalPath() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        JdbcNodeLease oldProcess = new JdbcNodeLease(
                dataSource, executor, "realm-live-02", UUID.randomUUID(),
                Duration.ofSeconds(30), clock);
        assertTrue(oldProcess.acquire());
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.supervise(oldProcess, clock);

        // 让旧 token 过期，再由新进程接管同一 node id。
        clock.advance(Duration.ofSeconds(31));
        JdbcNodeLease replacement = new JdbcNodeLease(
                dataSource, executor, "realm-live-02", UUID.randomUUID(),
                Duration.ofSeconds(30), clock);
        assertTrue(replacement.acquire());

        // 旧进程的真实续期必须失败，并触发一次失败关闭。
        assertFalse(consumer.supervise(oldProcess, clock)
                .renewOnce().toCompletableFuture().join());
        assertEquals(1, consumer.reasons.size());
        assertTrue(consumer.reasons.get(0).contains("fenced"));
        assertTrue(consumer.supervisor.stopped());
    }

    @Test
    void unreachableDatabaseBeyondRetryBudgetFailsTheNodeClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        JdbcNodeLease lease = new JdbcNodeLease(
                dataSource, executor, "realm-live-03", UUID.randomUUID(),
                Duration.ofSeconds(30), clock);
        assertTrue(lease.acquire());
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.supervise(lease, clock);

        // 关池模拟数据库不可用，并把租约推到已过期，使重试预算无法覆盖。
        dataSource.close();
        clock.advance(Duration.ofSeconds(31));

        assertFalse(consumer.supervise(lease, clock).renewOnce().toCompletableFuture().join());
        assertEquals(1, consumer.reasons.size());
        assertTrue(consumer.reasons.get(0).contains("database was unavailable"));
    }

    private final class RecordingConsumer {
        private final List<String> reasons = new ArrayList<>();
        private NodeLeaseSupervisor supervisor;

        private NodeLeaseSupervisor supervise(JdbcNodeLease lease, MutableClock clock) {
            if (supervisor == null) {
                supervisor = new NodeLeaseSupervisor(
                        lease, Duration.ofSeconds(10), LOGGER,
                        (reason, error) -> reasons.add(reason));
            }
            return supervisor;
        }
    }

    private static final class MutableClock extends java.time.Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneId.of("UTC");
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            if (!java.time.ZoneId.of("UTC").equals(zone)) {
                throw new UnsupportedOperationException("test clock is UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
