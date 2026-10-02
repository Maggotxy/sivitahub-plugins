package cn.originalrpg.guide;

import cn.originalrpg.api.model.JourneyStep;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideJourneyPolicyTest {
    @Test
    void firstForgePrecedesWildernessCombatInTheExecutableJourney() {
        assertEquals(java.util.List.of(
                        JourneyStep.HUB,
                        JourneyStep.CLASS,
                        JourneyStep.ATTRIBUTE,
                        JourneyStep.FORGE,
                        JourneyStep.EXPLORE,
                        JourneyStep.HUNT,
                        JourneyStep.REVEAL,
                        JourneyStep.DUNGEON),
                GuideTracker.STEPS);
    }

    @Test
    void npcDestinationsExistOnlyWhenAnNpcCanAdvanceTheStep() {
        Map<JourneyStep, String> expected = Map.of(
                JourneyStep.HUB, "guide",
                JourneyStep.CLASS, "class",
                JourneyStep.ATTRIBUTE, "class",
                JourneyStep.EXPLORE, "wilderness",
                JourneyStep.FORGE, "forge",
                JourneyStep.DUNGEON, "dungeon");

        expected.forEach((step, role) -> assertEquals(role,
                GuideJourneyPolicy.npcRole(step).orElseThrow()));
        assertTrue(GuideJourneyPolicy.npcRole(JourneyStep.HUNT).isEmpty());
        assertTrue(GuideJourneyPolicy.npcRole(JourneyStep.REVEAL).isEmpty());
        assertTrue(GuideJourneyPolicy.npcRole(null).isEmpty());
    }

    @Test
    void revealUsesTheRealCombatEntryAndEquipmentExplanation() {
        assertEquals("command:mobguide", GuideJourneyPolicy.primaryAction(JourneyStep.REVEAL));
        assertTrue(GuideJourneyPolicy.systemAction(JourneyStep.REVEAL).isEmpty());
        assertEquals("equipment", GuideJourneyPolicy.helpTopic(JourneyStep.REVEAL));
    }

    @Test
    void huntingOpensTheLocalMonsterGuideInsteadOfReturningAFrontierPlayerToHub() {
        assertEquals("command:mobguide", GuideJourneyPolicy.primaryAction(JourneyStep.HUNT));
        assertTrue(GuideJourneyPolicy.npcRole(JourneyStep.HUNT).isEmpty());
        assertTrue(GuideJourneyPolicy.systemAction(JourneyStep.HUNT).isEmpty());
    }

    @Test
    void everyStepRoutesToItsExpectedPrimaryAction() {
        Map<JourneyStep, String> expected = Map.of(
                JourneyStep.HUB, "command:hubfind guide",
                JourneyStep.CLASS, "command:hubfind class",
                JourneyStep.ATTRIBUTE, "command:hubfind class",
                JourneyStep.EXPLORE, "command:hubfind wilderness",
                JourneyStep.HUNT, "command:mobguide",
                JourneyStep.FORGE, "command:hubfind forge",
                JourneyStep.REVEAL, "command:mobguide",
                JourneyStep.DUNGEON, "command:hubfind dungeon");

        expected.forEach((step, action) -> assertEquals(action,
                GuideJourneyPolicy.primaryAction(step)));
    }

    @Test
    void experiencedPlayersRetainAnOptionalNoTravelSystemShortcut() {
        Map<JourneyStep, String> expected = Map.of(
                JourneyStep.CLASS, "open:class",
                JourneyStep.ATTRIBUTE, "open:class",
                JourneyStep.EXPLORE, "command:wild",
                JourneyStep.FORGE, "command:forge",
                JourneyStep.DUNGEON, "open:dungeon");

        expected.forEach((step, action) -> assertEquals(action,
                GuideJourneyPolicy.systemAction(step).orElseThrow()));
        assertTrue(GuideJourneyPolicy.systemAction(JourneyStep.HUB).isEmpty());
        assertTrue(GuideJourneyPolicy.systemAction(JourneyStep.HUNT).isEmpty());
        assertTrue(GuideJourneyPolicy.systemAction(JourneyStep.REVEAL).isEmpty());
        assertTrue(GuideJourneyPolicy.systemAction(null).isEmpty());
    }

    @Test
    void detailsPageHasAtMostOneAlternativeAlongsideBackOverviewAndEncyclopedia() {
        Map<JourneyStep, String> expected = Map.of(
                JourneyStep.CLASS, "open:class",
                JourneyStep.ATTRIBUTE, "command:skills",
                JourneyStep.EXPLORE, "command:wild",
                JourneyStep.HUNT, "command:wild",
                JourneyStep.FORGE, "command:forge",
                JourneyStep.REVEAL, "command:wild");
        for (JourneyStep step : JourneyStep.values()) {
            int actionCount = 3 + (GuideJourneyPolicy.detailsAlternative(step).isPresent() ? 1 : 0);
            assertTrue(actionCount <= 4, step + " rebuilt a menu inside the rules page");
            if (expected.containsKey(step)) {
                assertEquals(expected.get(step),
                        GuideJourneyPolicy.detailsAlternative(step).orElseThrow().action());
            } else {
                assertTrue(GuideJourneyPolicy.detailsAlternative(step).isEmpty());
            }
        }
    }

    @Test
    void dungeonPrimaryRouteUsesLiveLevelAndEnergyGates() {
        assertEquals(GuideJourneyPolicy.DungeonRoute.NEEDS_LEVEL,
                GuideJourneyPolicy.dungeonRoute(4, 100, 5, 20));
        assertEquals(GuideJourneyPolicy.DungeonRoute.NEEDS_LEVEL,
                GuideJourneyPolicy.dungeonRoute(4, 0, 5, 20),
                "leveling also lets Energy regenerate and therefore takes precedence");
        assertEquals(GuideJourneyPolicy.DungeonRoute.NEEDS_ENERGY,
                GuideJourneyPolicy.dungeonRoute(5, 19, 5, 20));
        assertEquals(GuideJourneyPolicy.DungeonRoute.READY,
                GuideJourneyPolicy.dungeonRoute(5, 20, 5, 20));
        assertEquals("command:mobguide", GuideJourneyPolicy.DungeonRoute.NEEDS_LEVEL.action());
        assertEquals("command:mobguide", GuideJourneyPolicy.DungeonRoute.NEEDS_ENERGY.action());
        assertEquals("open:dungeon", GuideJourneyPolicy.DungeonRoute.READY.action());
        assertThrows(IllegalArgumentException.class,
                () -> GuideJourneyPolicy.dungeonRoute(-1, 20, 5, 20));
        assertThrows(IllegalArgumentException.class,
                () -> GuideJourneyPolicy.dungeonRoute(5, 20, 0, 20));
    }

    @Test
    void everyJourneyStepUsesItsOwnDedicatedOriginalModel() {
        java.util.Set<String> supported = java.util.Set.of(
                "journey_hub", "journey_class", "journey_attribute", "journey_explore",
                "journey_hunt", "journey_forge", "journey_reveal", "journey_dungeon");
        assertEquals(supported, java.util.Arrays.stream(JourneyStep.values())
                .map(GuideJourneyPolicy::itemModel)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(JourneyStep.values().length, supported.size());
    }
}
