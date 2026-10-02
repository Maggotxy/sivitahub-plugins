package cn.originalrpg.api.model;

import java.util.Objects;

/** Durable result returned for both a first settlement and an idempotent replay. */
public record DungeonRewardSettlement(
        Status status,
        PlayerProfile profile,
        int queuedDeliveries,
        boolean ticketDropped,
        int ticketFailures
) {
    public DungeonRewardSettlement {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(profile, "profile");
        if (queuedDeliveries < 0 || ticketFailures < 0) {
            throw new IllegalArgumentException("Invalid dungeon reward settlement counters");
        }
    }

    public enum Status {
        SETTLED,
        REPLAYED
    }
}
