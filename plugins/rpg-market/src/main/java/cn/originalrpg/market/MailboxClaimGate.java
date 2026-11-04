package cn.originalrpg.market;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local single-flight gate for one player's durable-mailbox claim.
 *
 * <p>This is a local command throttle, never a cross-node lease. The existing
 * delivery row's claim/authorization/token protocol owns physical admission;
 * the global player session separately fences the initiating actor. Neither
 * mechanism transports or atomically loads a player's inventory between nodes.</p>
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
