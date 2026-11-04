package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.MarketDelivery;
import cn.originalrpg.api.model.MarketDeliveryAuthorization;
import cn.originalrpg.api.model.MarketDeliveryClaim;
import cn.originalrpg.api.model.PlayerSessionLease;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Same-row mailbox protocol; all methods run inside the caller's existing JDBC transaction. */
final class MailboxDeliveryLedger {
    private static final Duration RESERVATION_DURATION = Duration.ofSeconds(30);
    private final Clock clock;
    MailboxDeliveryLedger(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    MarketDeliveryClaim claim(Connection connection, UUID deliveryId, UUID playerId, UUID token) throws SQLException {
        Row row = lock(connection, deliveryId, playerId);
        if (row == null || !row.permitted()) return response(MarketDeliveryClaim.Status.REFUSED, deliveryId, token);
        if (row.state().equals("DELIVERED")) return response(MarketDeliveryClaim.Status.DELIVERED, deliveryId, token);
        if (row.state().equals("STAGING")) return response(MarketDeliveryClaim.Status.INDETERMINATE, deliveryId, token);
        Instant now = now(connection);
        if (row.state().equals("CLAIMED")) {
            if (token.toString().equals(row.token())) {
                if (row.expiresAt() != null && row.expiresAt().isAfter(now) && sameActor(row)) {
                    return new MarketDeliveryClaim(MarketDeliveryClaim.Status.CLAIMED, deliveryId, token, row.delivery(), row.expiresAt());
                }
                // A token that expired or crossed authentication boundaries is not revived.
                return response(MarketDeliveryClaim.Status.REFUSED, deliveryId, token);
            }
            if (row.expiresAt() == null || row.expiresAt().isAfter(now)) return response(MarketDeliveryClaim.Status.BUSY, deliveryId, token);
        } else if (!row.state().equals("PENDING") || row.token() != null) {
            return response(MarketDeliveryClaim.Status.REFUSED, deliveryId, token);
        }
        PlayerSessionLease actor = actor();
        Instant expiry = now.plus(RESERVATION_DURATION).truncatedTo(ChronoUnit.MICROS);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_deliveries SET state = 'CLAIMED', claim_token = ?, claim_expires_at = ?,
                    claim_started_at = NULL, claim_node_id = ?, claim_process_token = ?, claim_session_token = ?, claim_fence = ?
                WHERE id = ? AND player_uuid = ?
                """)) {
            update.setString(1, token.toString()); update.setTimestamp(2, Timestamp.from(expiry));
            update.setString(3, actor == null ? null : actor.nodeId());
            update.setString(4, actor == null ? null : actor.processToken());
            update.setString(5, actor == null ? null : actor.sessionToken().toString());
            if (actor == null) update.setNull(6, java.sql.Types.BIGINT); else update.setLong(6, actor.fence());
            update.setString(7, deliveryId.toString()); update.setString(8, playerId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Mailbox delivery disappeared during claim");
        }
        return new MarketDeliveryClaim(MarketDeliveryClaim.Status.CLAIMED, deliveryId, token, row.delivery(), expiry);
    }

    MarketDeliveryAuthorization authorize(Connection connection, UUID deliveryId, UUID playerId, UUID token) throws SQLException {
        Row row = lock(connection, deliveryId, playerId);
        if (row == null || !row.permitted() || !token.toString().equals(row.token())) return MarketDeliveryAuthorization.REFUSED;
        if (row.state().equals("STAGING") || row.state().equals("DELIVERED")) return MarketDeliveryAuthorization.INDETERMINATE;
        if (!row.state().equals("CLAIMED") || !sameActor(row)) return MarketDeliveryAuthorization.REFUSED;
        Instant now = now(connection);
        if (row.expiresAt() == null || !row.expiresAt().isAfter(now)) return MarketDeliveryAuthorization.EXPIRED;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_deliveries SET state = 'STAGING', claim_started_at = ?
                WHERE id = ? AND player_uuid = ? AND state = 'CLAIMED' AND claim_token = ?
                """)) {
            update.setTimestamp(1, Timestamp.from(now)); update.setString(2, deliveryId.toString());
            update.setString(3, playerId.toString()); update.setString(4, token.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Mailbox authorization changed while locked");
        }
        return MarketDeliveryAuthorization.AUTHORIZED;
    }

    boolean release(Connection connection, UUID deliveryId, UUID playerId, UUID token) throws SQLException {
        Row row = lock(connection, deliveryId, playerId);
        if (row == null || !row.permitted() || !row.state().equals("CLAIMED")
                || !token.toString().equals(row.token()) || !sameActor(row)) return false;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_deliveries SET state = 'PENDING', claim_token = NULL, claim_expires_at = NULL,
                    claim_started_at = NULL, claim_node_id = NULL, claim_process_token = NULL,
                    claim_session_token = NULL, claim_fence = NULL
                WHERE id = ? AND player_uuid = ? AND state = 'CLAIMED' AND claim_token = ?
                """)) {
            update.setString(1, deliveryId.toString()); update.setString(2, playerId.toString()); update.setString(3, token.toString());
            return update.executeUpdate() == 1;
        }
    }

    Acknowledgement acknowledge(Connection connection, UUID deliveryId, UUID playerId, UUID token) throws SQLException {
        Row row = lock(connection, deliveryId, playerId);
        if (row == null || !row.permitted() || !token.toString().equals(row.token())) return new Acknowledgement(false, false, null);
        if (row.state().equals("DELIVERED")) return new Acknowledgement(true, false, row.delivery().source());
        if (!row.state().equals("STAGING")) return new Acknowledgement(false, false, null);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_deliveries SET state = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP
                WHERE id = ? AND player_uuid = ? AND state = 'STAGING' AND claim_token = ?
                """)) {
            update.setString(1, deliveryId.toString()); update.setString(2, playerId.toString()); update.setString(3, token.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Mailbox acknowledgement changed while locked");
        }
        // The original token/actor remains as durable delivery evidence. A new
        // authenticated session may acknowledge its physical receipt, never reissue it.
        return new Acknowledgement(true, true, row.delivery().source());
    }

    private Row lock(Connection connection, UUID id, UUID player) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id,player_uuid,item_payload,source,state,created_at,quarantine_reason,
                       claim_token,claim_expires_at,claim_node_id,claim_process_token,claim_session_token,claim_fence
                FROM rpg_market_deliveries WHERE id = ? AND player_uuid = ? FOR UPDATE
                """)) {
            query.setString(1, id.toString()); query.setString(2, player.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                Timestamp expiry = result.getTimestamp("claim_expires_at");
                long fenceValue = result.getLong("claim_fence"); Long fence = result.wasNull() ? null : fenceValue;
                MarketDelivery delivery = new MarketDelivery(id, player, result.getString("item_payload"),
                        result.getString("source"), result.getTimestamp("created_at").toInstant());
                return new Row(delivery, result.getString("state"), result.getString("quarantine_reason"),
                        result.getString("claim_token"), expiry == null ? null : expiry.toInstant(),
                        result.getString("claim_node_id"), result.getString("claim_process_token"),
                        result.getString("claim_session_token"), fence);
            }
        }
    }

    private static PlayerSessionLease actor() {
        SessionWriteContext.Scope scope = SessionWriteContext.current();
        return scope == null ? null : scope.actor();
    }

    private static boolean sameActor(Row row) {
        PlayerSessionLease actor = actor();
        if (actor == null) return row.node() == null && row.process() == null && row.session() == null && row.fence() == null;
        return actor.nodeId().equals(row.node()) && actor.processToken().equals(row.process())
                && actor.sessionToken().toString().equals(row.session()) && Long.valueOf(actor.fence()).equals(row.fence());
    }

    private Instant now(Connection connection) throws SQLException {
        if (connection.getMetaData().getDatabaseProductName().equals("H2")) return clock.instant();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT clock_timestamp()")) {
            result.next(); return result.getTimestamp(1).toInstant();
        }
    }

    private static MarketDeliveryClaim response(MarketDeliveryClaim.Status status, UUID id, UUID token) {
        return new MarketDeliveryClaim(status, id, token, null, null);
    }

    record Acknowledgement(boolean allowed, boolean changed, String source) { }
    private record Row(MarketDelivery delivery, String state, String quarantineReason, String token,
                       Instant expiresAt, String node, String process, String session, Long fence) {
        boolean permitted() { return quarantineReason == null && !delivery.source().startsWith("commerce-reward:") && !state.equals("QUARANTINED"); }
    }
}
