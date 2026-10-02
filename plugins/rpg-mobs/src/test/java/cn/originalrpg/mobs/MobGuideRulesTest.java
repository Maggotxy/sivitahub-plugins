package cn.originalrpg.mobs;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.PlayerProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobGuideRulesTest {
    private static final List<MobGuideRules.DungeonOption> DUNGEONS = List.of(
            new MobGuideRules.DungeonOption("ancient-ruins", 5, 800, false),
            new MobGuideRules.DungeonOption("sulfur-depths", 12, 3300, false),
            new MobGuideRules.DungeonOption("ancient-ruins-hidden", 15, 6000, true),
            new MobGuideRules.DungeonOption("storm-vault", 22, 10_000, false));

    @Test
    void adaptiveRangeMatchesTheMobListener() {
        assertEquals(1, MobGuideRules.minimumAdaptiveLevel(2));
        assertEquals(5, MobGuideRules.maximumAdaptiveLevel(2));
    }

    @Test
    void recommendationsUseTheHighestUnlockedNormalAndTicketedRoutes() {
        assertNull(MobGuideRules.recommendedNormal(4, DUNGEONS));
        assertEquals("sulfur-depths", MobGuideRules.recommendedNormal(15, DUNGEONS).id());
        assertEquals("ancient-ruins-hidden", MobGuideRules.recommendedHidden(15, DUNGEONS).id());
        assertEquals("storm-vault", MobGuideRules.nextUnlock(15, DUNGEONS).id());
    }

    @Test
    void guidePlanContainsTheSameProgressionAndCombatNumbersShownToPlayers() {
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), Archetype.WARRIOR,
                0, 4, 500L, 100, 100, 0, 0, Map.of(CoreAttribute.STRENGTH, 0));

        MobGuideRules.GuidePlan plan = MobGuideRules.guidePlan(profile, 10L, 10L, 40L, DUNGEONS);

        assertEquals(4, plan.effectiveLevel());
        assertEquals(829L, plan.neededExperience());
        assertEquals(329L, plan.remainingExperience());
        assertFalse(plan.capped());
        assertEquals(1, plan.minimumMobLevel());
        assertEquals(7, plan.maximumMobLevel());
        assertEquals(10L, plan.minimumNormalExperience());
        assertEquals(70L, plan.maximumNormalExperience());
        assertEquals(40L, plan.minimumEliteExperience());
        assertEquals(280L, plan.maximumEliteExperience());
        assertNull(plan.recommendedNormal());
        assertEquals("ancient-ruins", plan.nextUnlock().id());
    }

    @Test
    void cappedPlanDoesNotAdvertiseAnotherLevelExperienceTarget() {
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), Archetype.WARRIOR,
                0, 30, 900_000L, 100, 100, 0, 0, Map.of(CoreAttribute.STRENGTH, 0));

        MobGuideRules.GuidePlan plan = MobGuideRules.guidePlan(profile, 10L, 10L, 40L, DUNGEONS);

        assertTrue(plan.capped());
        assertEquals(0L, plan.neededExperience());
        assertEquals(0L, plan.remainingExperience());
    }

    @Test
    void huntButtonRoutesByConfirmedPlayerLocationWithoutReturningToHelp() {
        MobGuideRules.HuntRoute hub = MobGuideRules.huntRoute(MobGuideRules.HuntLocation.HUB);
        MobGuideRules.HuntRoute frontier = MobGuideRules.huntRoute(
                MobGuideRules.HuntLocation.OVERWORLD_FRONTIER);
        MobGuideRules.HuntRoute other = MobGuideRules.huntRoute(MobGuideRules.HuntLocation.OTHER);

        assertEquals("hub", hub.id());
        assertEquals("/wild", hub.command());
        assertFalse(hub.alreadyThere());
        assertEquals("frontier", frontier.id());
        assertNull(frontier.command());
        assertTrue(frontier.alreadyThere());
        assertEquals("other", other.id());
        assertEquals("/hub", other.command());
        assertFalse(other.alreadyThere());
        for (MobGuideRules.HuntLocation location : MobGuideRules.HuntLocation.values()) {
            assertFalse("/rpghelp current".equals(MobGuideRules.huntRoute(location).command()),
                    location + " must not loop back into the journey briefing");
        }
    }

    @Test
    void hubCheckMatchesTheCircularProtectionBoundaryAndHandlesExtremeCoordinates() {
        assertTrue(MobGuideRules.insideHub(100, -100, 60, 160, -100));
        assertFalse(MobGuideRules.insideHub(100, -100, 60, 160, -99));
        assertFalse(MobGuideRules.insideHub(Integer.MIN_VALUE, Integer.MIN_VALUE, 128,
                Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
