package cn.originalrpg.dungeons;

import java.util.Set;
import java.util.UUID;

final class SeasonExpeditionQualificationPolicy {
    private SeasonExpeditionQualificationPolicy() {
    }

    static Status evaluate(
            boolean started,
            String expectedDungeon,
            String completedDungeon,
            Set<UUID> startingParty,
            Set<UUID> finishingParty,
            boolean rotationActive
    ) {
        if (!started || expectedDungeon == null || !expectedDungeon.equals(completedDungeon)
                || startingParty == null || finishingParty == null || !startingParty.equals(finishingParty)) {
            return Status.PARTY_OR_RUN_INVALID;
        }
        return rotationActive ? Status.QUALIFIED : Status.ROTATION_EXPIRED;
    }

    enum Status {
        QUALIFIED,
        PARTY_OR_RUN_INVALID,
        ROTATION_EXPIRED
    }
}
