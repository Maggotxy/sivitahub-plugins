package cn.originalrpg.hub;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection-scoped authentication generations for asynchronous hub work.
 *
 * <p>A token is valid only for the exact authenticated generation in which it
 * was issued. Revocation advances the generation and marks it inactive, so an
 * old chunk-load or teleport completion cannot act on a subsequent login that
 * happens on the same Bukkit connection.</p>
 */
final class AuthenticationSession {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<UUID, Generation> generations = new ConcurrentHashMap<>();

    long activate(UUID playerId) {
        long value = sequence.incrementAndGet();
        generations.put(playerId, new Generation(value, true));
        return value;
    }

    void revoke(UUID playerId) {
        long value = sequence.incrementAndGet();
        generations.put(playerId, new Generation(value, false));
    }

    void forget(UUID playerId) {
        generations.remove(playerId);
    }

    long token(UUID playerId) {
        Generation generation = generations.get(playerId);
        return generation != null && generation.authenticated() ? generation.value() : -1L;
    }

    boolean valid(UUID playerId, long token) {
        Generation generation = generations.get(playerId);
        return token >= 0L && generation != null && generation.authenticated()
                && generation.value() == token;
    }

    boolean authenticated(UUID playerId) {
        return token(playerId) >= 0L;
    }

    void clear() {
        generations.clear();
    }

    private record Generation(long value, boolean authenticated) { }
}
