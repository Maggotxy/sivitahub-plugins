package cn.originalrpg.ainpc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection-scoped generations for asynchronous AI conversations.
 *
 * <p>A UUID alone is not a player session: an HTTP answer may arrive after the
 * old connection has quit and a new connection with the same UUID has become
 * ready. Every ready event therefore receives a unique generation.</p>
 */
final class AiSessionRegistry {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<UUID, Long> active = new ConcurrentHashMap<>();

    long activate(UUID playerId) {
        long generation = sequence.incrementAndGet();
        active.put(playerId, generation);
        return generation;
    }

    void revoke(UUID playerId) {
        active.remove(playerId);
    }

    long token(UUID playerId) {
        return active.getOrDefault(playerId, -1L);
    }

    boolean valid(UUID playerId, long generation) {
        return generation >= 0L && active.getOrDefault(playerId, -1L) == generation;
    }

    void clear() {
        active.clear();
    }
}
