package cn.originalrpg.core.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link OutboxRegistry} 的 JDBC 实现（G-102）。
 *
 * <p>写入失败按异常冒泡，业务事务整体回滚——这正是「Transactional」的含义：
 * 不存在业务已提交而事件丢失的窗口。同一键且内容相同才属于幂等重放；
 * 复用键却改变收件人/类型/内容会使整个业务事务失败。</p>
 */
public final class JdbcOutboxRegistry implements OutboxRegistry {

    @Override
    public boolean publish(
            Connection connection,
            String aggregateType,
            String aggregateId,
            String idempotencyKey,
            String payload
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        String type = requireNonBlank(aggregateType, "aggregateType");
        String id = requireNonBlank(aggregateId, "aggregateId");
        String key = requireNonBlank(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(payload, "payload");
        if (connection.getAutoCommit()) throw new SQLException("Outbox publication requires the business transaction");
        if (type.length() > 32 || id.length() > 36 || key.length() > 96) {
            throw new IllegalArgumentException("Outbox identity exceeds the persisted schema limits");
        }

        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_outbox
                    (event_id, aggregate_type, aggregate_id, idempotency_key, payload)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, type);
            insert.setString(3, id);
            insert.setString(4, key);
            insert.setString(5, payload);
            if (insert.executeUpdate() == 1) return true;
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT aggregate_type, aggregate_id, payload FROM rpg_outbox WHERE idempotency_key = ?
                """)) {
            query.setString(1, key);
            try (ResultSet existing = query.executeQuery()) {
                if (!existing.next() || !type.equals(existing.getString("aggregate_type"))
                        || !id.equals(existing.getString("aggregate_id"))
                        || !payload.equals(existing.getString("payload"))) {
                    throw new SQLException("Outbox idempotency key was reused with different content");
                }
                return false;
            }
        }
    }

    /**
     * 由业务语义派生确定性幂等键，避免调用方各自拼字符串产生歧义。
     * 形如 {@code market.listing:<uuid>:closed}，总长受 DDL 的 96 字符约束。
     */
    public static String idempotencyKey(String aggregateType, String aggregateId, String action) {
        String raw = requireNonBlank(aggregateType, "aggregateType") + ":"
                + requireNonBlank(aggregateId, "aggregateId") + ":"
                + requireNonBlank(action, "action");
        return raw.length() <= 96 ? raw : raw.substring(0, 64) + ":" + sha256Hex(raw).substring(0, 24);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireNonBlank(String value, String field) {
        String trimmed = Objects.requireNonNull(value, field).trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return trimmed;
    }
}
