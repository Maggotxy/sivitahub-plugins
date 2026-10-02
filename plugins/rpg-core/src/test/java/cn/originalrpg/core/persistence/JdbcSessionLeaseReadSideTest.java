package cn.originalrpg.core.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-102 行为级测试：Session Lease 读侧判定。
 *
 * <p>判定口径来自 docs/SCALE_ARCHITECTURE.md「必须保持的验收不变量」：
 * 同一 UUID 任意时刻只有一个持有效 fencing token 的游戏节点；过期/旧 token
 * 不得被当成有效持有者。</p>
 */
class JdbcSessionLeaseReadSideTest {
    private static final String LOCAL_NODE = "hub-01";
    private static final String LOCAL_TOKEN = "token-local";

    private DataSource dataSource;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:session-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_session_leases (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        node_id VARCHAR(64) NOT NULL,
                        node_process_token VARCHAR(36) NULL,
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void localNodeHoldingALiveLeaseIsAdmitted() throws Exception {
        UUID player = UUID.randomUUID();
        insertLease(player, LOCAL_NODE, LOCAL_TOKEN, clock.instant().plus(Duration.ofSeconds(60)));

        JdbcSessionLease lease = new JdbcSessionLease(dataSource, LOCAL_NODE, LOCAL_TOKEN, clock);

        assertTrue(lease.isLocalNodeHolder(player));
        assertEquals(JdbcSessionLease.Admission.ALLOWED, lease.admit(player));
        assertTrue(lease.admit(player).allowed());
    }

    @Test
    void expiredLeaseIsTreatedAsNoValidHolderEvenOnTheSameNode() throws Exception {
        UUID player = UUID.randomUUID();
        insertLease(player, LOCAL_NODE, LOCAL_TOKEN, clock.instant().minus(Duration.ofSeconds(1)));

        JdbcSessionLease lease = new JdbcSessionLease(dataSource, LOCAL_NODE, LOCAL_TOKEN, clock);

        assertFalse(lease.isLocalNodeHolder(player), "过期租约不得被当成有效持有者");
        assertEquals(JdbcSessionLease.Admission.NO_VALID_LEASE, lease.admit(player));
        assertTrue(lease.holder(player).isEmpty());
    }

    @Test
    void leaseHeldByAnotherNodeRejectsTheLocalNode() throws Exception {
        UUID player = UUID.randomUUID();
        insertLease(player, "realm-a-02", "token-other", clock.instant().plus(Duration.ofSeconds(60)));

        JdbcSessionLease lease = new JdbcSessionLease(dataSource, LOCAL_NODE, LOCAL_TOKEN, clock);

        assertFalse(lease.isLocalNodeHolder(player));
        assertEquals(JdbcSessionLease.Admission.HELD_BY_OTHER_NODE, lease.admit(player));
    }

    @Test
    void staleProcessTokenOnTheSameNodeIsNeverTreatedAsTheSessionHolder() throws Exception {
        UUID player = UUID.randomUUID();
        // 节点 id 相同，但租约属于该节点的上一个进程 token（已被 fencing）
        insertLease(player, LOCAL_NODE, "token-from-old-process", clock.instant().plus(Duration.ofSeconds(60)));

        JdbcSessionLease replacementProcess = new JdbcSessionLease(dataSource, LOCAL_NODE, LOCAL_TOKEN, clock);

        assertFalse(replacementProcess.isLocalNodeHolder(player));
        assertEquals(JdbcSessionLease.Admission.STALE_PROCESS_TOKEN, replacementProcess.admit(player));
    }

    @Test
    void missingLeaseFailsClosed() {
        JdbcSessionLease lease = new JdbcSessionLease(dataSource, LOCAL_NODE, LOCAL_TOKEN, clock);
        UUID player = UUID.randomUUID();
        assertFalse(lease.isLocalNodeHolder(player));
        assertEquals(JdbcSessionLease.Admission.NO_VALID_LEASE, lease.admit(player));
    }

    @Test
    void leaseThatExpiresAfterTheHeartbeatStopsRejectsTheLocalNode() throws Exception {
        UUID player = UUID.randomUUID();
        insertLease(player, LOCAL_NODE, LOCAL_TOKEN, clock.instant().plus(Duration.ofSeconds(30)));
        JdbcSessionLease lease = new JdbcSessionLease(dataSource, LOCAL_NODE, LOCAL_TOKEN, clock);
        assertTrue(lease.isLocalNodeHolder(player));

        // 心跳停止后租约到期：读侧必须转成「不持有」，防止旧节点继续结算。
        clock.advance(Duration.ofSeconds(31));

        assertFalse(lease.isLocalNodeHolder(player));
        assertEquals(JdbcSessionLease.Admission.NO_VALID_LEASE, lease.admit(player));
    }

    private void insertLease(UUID player, String node, String token, Instant expiresAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO rpg_session_leases
                         (player_uuid, node_id, node_process_token, lease_expires_at)
                     VALUES (?, ?, ?, ?)
                     """)) {
            insert.setString(1, player.toString());
            insert.setString(2, node);
            insert.setString(3, token);
            insert.setTimestamp(4, Timestamp.from(expiresAt));
            insert.executeUpdate();
            connection.commit();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneId.of("UTC").equals(zone)) throw new UnsupportedOperationException("test clock is UTC");
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
