package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SeasonExpeditionQualificationPolicyTest {
    @Test
    void onlyTheEntireStartingPartyCanSettleTheCapturedRotation() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> party = Set.of(first, second);
        assertEquals(SeasonExpeditionQualificationPolicy.Status.QUALIFIED,
                SeasonExpeditionQualificationPolicy.evaluate(true, "ancient-ruins", "ancient-ruins",
                        party, party, true));
        assertEquals(SeasonExpeditionQualificationPolicy.Status.PARTY_OR_RUN_INVALID,
                SeasonExpeditionQualificationPolicy.evaluate(true, "ancient-ruins", "ancient-ruins",
                        party, Set.of(first), true));
        assertEquals(SeasonExpeditionQualificationPolicy.Status.PARTY_OR_RUN_INVALID,
                SeasonExpeditionQualificationPolicy.evaluate(true, "ancient-ruins", "storm-vault",
                        party, party, true));
        assertEquals(SeasonExpeditionQualificationPolicy.Status.PARTY_OR_RUN_INVALID,
                SeasonExpeditionQualificationPolicy.evaluate(false, "ancient-ruins", "ancient-ruins",
                        party, party, true));
    }

    @Test
    void crossingTheWeeklyBoundaryKeepsNormalRewardsButRejectsSeasonSettlement() {
        Set<UUID> party = Set.of(UUID.randomUUID());
        assertEquals(SeasonExpeditionQualificationPolicy.Status.ROTATION_EXPIRED,
                SeasonExpeditionQualificationPolicy.evaluate(true, "ancient-ruins", "ancient-ruins",
                        party, party, false));
    }
}
