package cn.originalrpg.mobs;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Pure limits and stable identifiers for delayed natural-mob rewards. */
public final class MobRewardReliabilityRules {
    private MobRewardReliabilityRules() {
    }

    static boolean hasCapacity(
            int totalPending, int playerPending, int maximumTotal, int maximumPerPlayer
    ) {
        return totalPending >= 0 && playerPending >= 0
                && maximumTotal > 0 && maximumPerPlayer > 0
                && totalPending < maximumTotal && playerPending < maximumPerPlayer;
    }

    static boolean expired(long createdAtMillis, long nowMillis, long lifetimeMillis) {
        if (lifetimeMillis <= 0L) return true;
        return nowMillis < createdAtMillis || nowMillis - createdAtMillis >= lifetimeMillis;
    }

    static boolean mayRetry(
            int completedAttempts, int maximumAttempts,
            long createdAtMillis, long nowMillis, long lifetimeMillis
    ) {
        return completedAttempts >= 0 && completedAttempts < maximumAttempts
                && !expired(createdAtMillis, nowMillis, lifetimeMillis);
    }

    static long retryDelayMillis(int failedAttempts, long baseMillis, long maximumMillis) {
        if (failedAttempts < 1 || baseMillis < 1L || maximumMillis < baseMillis) {
            throw new IllegalArgumentException("Invalid retry backoff inputs");
        }
        int shifts = Math.min(failedAttempts - 1, 62);
        if (baseMillis > (maximumMillis >> shifts)) return maximumMillis;
        return Math.min(maximumMillis, baseMillis << shifts);
    }

    public static UUID deliveryId(UUID killId, String kind) {
        Objects.requireNonNull(killId, "killId");
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) throw new IllegalArgumentException("kind must not be blank");
        return UUID.nameUUIDFromBytes(
                ("sivita:mob-reward:v1:" + killId + ':' + kind).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID killId(UUID playerId, UUID entityId, long deathMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(entityId, "entityId");
        if (deathMillis < 0L) throw new IllegalArgumentException("deathMillis must not be negative");
        return UUID.nameUUIDFromBytes(("sivita:mob-kill:v1:" + playerId + ':' + entityId + ':' + deathMillis)
                .getBytes(StandardCharsets.UTF_8));
    }
}
