package cn.originalrpg.market;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local single-flight gate for one player's durable-mailbox claim.
 *
 * <p>A Paper player can only be connected to one shard at a time. The database
 * remains the authority for the delivery itself; this gate closes the smaller
 * window in which repeated commands on that shard could render the same
 * PENDING row twice before the first asynchronous acknowledgement completes.</p>
 */
final class MailboxClaimGate {
    private final Map<UUID, UUID> inFlight = new ConcurrentHashMap<>();

    Lease begin(UUID playerId) {
        if (playerId == null) return null;
        UUID token = UUID.randomUUID();
        return inFlight.putIfAbsent(playerId, token) == null ? new Lease(playerId, token) : null;
    }

    boolean finish(Lease lease) {
        return lease != null && inFlight.remove(lease.playerId(), lease.token());
    }

    boolean active(UUID playerId) {
        return playerId != null && inFlight.containsKey(playerId);
    }

    void clear() {
        inFlight.clear();
    }

    record Lease(UUID playerId, UUID token) {
        Lease {
            if (playerId == null || token == null) throw new IllegalArgumentException("lease fields are required");
        }
    }
}
