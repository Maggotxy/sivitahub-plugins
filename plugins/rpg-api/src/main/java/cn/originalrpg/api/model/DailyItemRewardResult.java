package cn.originalrpg.api.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of one durable, daily-limited item reward settlement.
 *
 * <p>The quota receipt and mailbox delivery are committed by the persistence
 * layer in one transaction. Callers must treat {@link Status#DUPLICATE} as an
 * already-settled replay and must never add the item directly to an inventory.</p>
 */
public record DailyItemRewardResult(
        Status status,
        UUID playerId,
        String rewardId,
        LocalDate rewardDate,
        int granted,
        int used,
        int dailyCap
) {
    public DailyItemRewardResult {
        Objects.requireNonNull(status, "status");
        if (status != Status.INVALID) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(rewardId, "rewardId");
            Objects.requireNonNull(rewardDate, "rewardDate");
        }
        // used may legitimately be above a newly lowered cap. The settlement
        // must preserve that authoritative history and simply grant zero.
        if (granted < 0 || used < 0 || dailyCap < 0) {
            throw new IllegalArgumentException("Invalid daily item reward totals");
        }
    }

    public static DailyItemRewardResult invalid() {
        return new DailyItemRewardResult(Status.INVALID, null, null, null, 0, 0, 0);
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        DAILY_LIMIT,
        INVALID
    }
}
