package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One-use routing reservation issued after the source connection is fenced.
 * It transfers lease authority only; it does not transport an inventory, PDC,
 * world, or cooldown snapshot. A transport adapter must quiesce and durably save
 * its state before offering a handoff, then restore it before admitting gameplay.
 * Possessing this value does not bypass the target node/process check in SQL.
 */
public record PlayerSessionHandoff(
        UUID playerId, UUID handoffId, String sourceNodeId, String sourceProcessToken,
        String targetNodeId, long fence, Instant expiresAt
) {
    public PlayerSessionHandoff {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(handoffId, "handoffId");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        UUID.fromString(Objects.requireNonNull(sourceProcessToken, "sourceProcessToken"));
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!sourceNodeId.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || !targetNodeId.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || sourceNodeId.equals(targetNodeId) || fence < 1) {
            throw new IllegalArgumentException("Invalid player session handoff");
        }
    }
}
