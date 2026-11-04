package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable database-issued authority for one player connection on one process.
 * A renewal may change {@code expiresAt}; it never changes the session token or
 * monotonic fence. Keep this value with delayed work instead of looking up a new
 * connection's lease. This snapshot is not proof of current ownership: only a
 * check in the business transaction can authorize a write.
 */
public record PlayerSessionLease(
        UUID playerId, String nodeId, String processToken, UUID sessionToken,
        long fence, Instant expiresAt
) {
    public PlayerSessionLease {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(nodeId, "nodeId");
        UUID.fromString(Objects.requireNonNull(processToken, "processToken"));
        Objects.requireNonNull(sessionToken, "sessionToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!nodeId.matches("[a-z0-9][a-z0-9_-]{0,63}") || fence < 1) {
            throw new IllegalArgumentException("Invalid player session authority");
        }
    }

    /** Compares authority, excluding the expiration snapshot changed by renewal. */
    public boolean sameAuthority(PlayerSessionLease other) {
        return other != null && playerId.equals(other.playerId) && nodeId.equals(other.nodeId)
                && processToken.equals(other.processToken) && sessionToken.equals(other.sessionToken)
                && fence == other.fence;
    }
}
