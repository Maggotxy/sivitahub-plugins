package cn.originalrpg.ainpc;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.JourneyStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CompanionAdvicePolicyTest {
    @Test
    void incompleteFoundationAlwaysWinsOverLaterContent() {
        assertEquals("class", route(Archetype.UNCHOSEN, 5, false, JourneyStep.DUNGEON, 2, 8, 100));
        assertEquals("attributes", route(Archetype.MAGE, 2, false, JourneyStep.DUNGEON, 2, 8, 100));
        assertEquals("skills", route(Archetype.MAGE, 0, false, JourneyStep.DUNGEON, 2, 8, 100));
    }

    @Test
    void liveJourneyStepBecomesTheConcreteRecommendation() {
        for (JourneyStep step : JourneyStep.values()) {
            assertEquals(step == JourneyStep.ATTRIBUTE ? "attributes" : step.id(),
                    route(Archetype.WARRIOR, 0, true, step, 1, 0, 100));
        }
    }

    @Test
    void freeplayStillHonorsGearPointsAndEnergy() {
        assertEquals("forge", route(Archetype.RANGER, 0, true, null, 0, 0, 100));
        assertEquals("skills", route(Archetype.RANGER, 0, true, null, 1, 2, 100));
        assertEquals("recovery", route(Archetype.RANGER, 0, true, null, 1, 0, 19));
        assertEquals("dungeon", route(Archetype.RANGER, 0, true, null, 1, 0, 20));
    }

    private static String route(
            Archetype archetype, int attributes, boolean skill,
            JourneyStep next, int gear, int points, int energy
    ) {
        return CompanionAdvicePolicy.route(archetype, attributes, skill, next, gear, points, energy);
    }
}
