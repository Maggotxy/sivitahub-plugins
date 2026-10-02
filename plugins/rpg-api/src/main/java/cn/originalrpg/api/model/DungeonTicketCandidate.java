package cn.originalrpg.api.model;

import java.util.Objects;

/** A pity-backed hidden-ticket candidate evaluated inside reward settlement. */
public record DungeonTicketCandidate(
        DungeonRewardDelivery delivery,
        String pityGroup,
        double chance,
        int guarantee
) {
    public DungeonTicketCandidate {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(pityGroup, "pityGroup");
        if (pityGroup.isBlank() || !Double.isFinite(chance) || chance < 0.0 || chance > 1.0
                || guarantee < 1 || guarantee > 10_000) {
            throw new IllegalArgumentException("Invalid dungeon ticket candidate");
        }
    }
}
