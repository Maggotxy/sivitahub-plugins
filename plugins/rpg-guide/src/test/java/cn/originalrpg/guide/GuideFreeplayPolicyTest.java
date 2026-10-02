package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideFreeplayPolicyTest {
    @Test
    void executableAscensionIsRecommendedFirst() {
        assertRoute(GuideFreeplayPolicy.Route.ASCENSION,
                state(true, 10, 0, 0, 0, true));
    }

    @Test
    void chapterLockedOrTerminalCapFallsThroughToAnExecutableRoute() {
        assertRoute(GuideFreeplayPolicy.Route.DUNGEON,
                state(false, 10, 1, 1, 1, true));
        assertRoute(GuideFreeplayPolicy.Route.HUNT,
                state(false, 10, 1, 1, 1, false));
    }

    @Test
    void firstLearnableSkillIsRecommendedButSavedPointsDoNotBecomeAPermanentBlocker() {
        assertRoute(GuideFreeplayPolicy.Route.SKILLS,
                state(false, 1, 0, 2, 3, true));

        assertRoute(GuideFreeplayPolicy.Route.DUNGEON,
                state(false, 99, 1, 2, 3, true));
        assertRoute(GuideFreeplayPolicy.Route.HUNT,
                state(false, 0, 0, 2, 3, false));
    }

    @Test
    void playerWithoutCraftedEquipmentIsSentToForge() {
        assertRoute(GuideFreeplayPolicy.Route.FORGE,
                state(false, 0, 1, 0, 0, true));
    }

    @Test
    void craftedEquipmentWithoutAnyVisibleSlotIsSentToRevealThroughCombat() {
        GuideFreeplayPolicy.Recommendation result = recommend(
                state(false, 0, 1, 1, 0, true));

        assertEquals(GuideFreeplayPolicy.Route.REVEAL, result.route());
        assertEquals("command:mobguide", result.action());
    }

    @Test
    void readyDungeonWinsAfterCoreBuildAndEquipmentNeedsAreMet() {
        assertRoute(GuideFreeplayPolicy.Route.DUNGEON,
                state(false, 4, 2, 1, 1, true));
    }

    @Test
    void suitableWildernessHuntIsTheBoundedFallback() {
        assertRoute(GuideFreeplayPolicy.Route.HUNT,
                state(false, 4, 2, 1, 1, false));
    }

    @Test
    void everyRouteCarriesExactTrustedPresentationMetadata() {
        record Metadata(String action, String helpTopic, String itemModel, String vanillaIcon) {
        }
        Map<GuideFreeplayPolicy.Route, Metadata> expected = Map.of(
                GuideFreeplayPolicy.Route.ASCENSION,
                new Metadata("open:class", "classes", "journey_class", "NETHER_STAR"),
                GuideFreeplayPolicy.Route.SKILLS,
                new Metadata("command:skills", "skills", "common_insight_skill_book", "ENCHANTED_BOOK"),
                GuideFreeplayPolicy.Route.FORGE,
                new Metadata("command:forge", "equipment", "journey_forge", "ANVIL"),
                GuideFreeplayPolicy.Route.REVEAL,
                new Metadata("command:mobguide", "equipment", "journey_reveal", "EXPERIENCE_BOTTLE"),
                GuideFreeplayPolicy.Route.DUNGEON,
                new Metadata("open:dungeon", "dungeons", "journey_dungeon", "TRIAL_KEY"),
                GuideFreeplayPolicy.Route.HUNT,
                new Metadata("command:mobguide", "mobs", "journey_hunt", "ZOMBIE_HEAD"));

        assertEquals(Set.copyOf(expected.keySet()), Set.of(GuideFreeplayPolicy.Route.values()));
        expected.forEach((route, metadata) -> {
            GuideFreeplayPolicy.Recommendation result = new GuideFreeplayPolicy.Recommendation(route);
            assertEquals(metadata.action(), result.action());
            assertEquals(metadata.helpTopic(), result.helpTopic());
            assertEquals(metadata.itemModel(), result.itemModel());
            assertEquals(metadata.vanillaIcon(), result.vanillaIcon());
        });
    }

    @Test
    void actionWhitelistAllowsOnlyCodeOwnedDispatcherActions() {
        Set<String> expected = Set.of(
                "open:class", "command:skills", "command:forge", "command:mobguide", "open:dungeon");
        Set<String> actual = java.util.Arrays.stream(GuideFreeplayPolicy.Route.values())
                .map(GuideFreeplayPolicy.Route::action)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(expected, actual);
        actual.forEach(action -> assertTrue(GuideFreeplayPolicy.isTrustedAction(action)));
        assertFalse(GuideFreeplayPolicy.isTrustedAction("command:op lamb"));
        assertFalse(GuideFreeplayPolicy.isTrustedAction("help:start"));
        assertFalse(GuideFreeplayPolicy.isTrustedAction(""));
        assertFalse(GuideFreeplayPolicy.isTrustedAction(null));
    }

    @Test
    void invalidSnapshotsAndRecommendationAreRejected() {
        assertThrows(NullPointerException.class, () -> GuideFreeplayPolicy.recommend(null));
        assertThrows(NullPointerException.class, () -> new GuideFreeplayPolicy.Recommendation(null));
        assertThrows(IllegalArgumentException.class, () -> state(false, -1, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> state(false, 0, -1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> state(false, 0, 0, -1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> state(false, 0, 0, 0, -1, false));
        assertThrows(IllegalArgumentException.class, () -> state(false, 0, 0, 0, 1, false));
    }

    private static GuideFreeplayPolicy.PlayerState state(
            boolean canAscendNow,
            int points,
            int learned,
            int equipment,
            int revealed,
            boolean dungeon) {
        return new GuideFreeplayPolicy.PlayerState(
                canAscendNow, points, learned, equipment, revealed, dungeon);
    }

    private static GuideFreeplayPolicy.Recommendation recommend(GuideFreeplayPolicy.PlayerState state) {
        return GuideFreeplayPolicy.recommend(state);
    }

    private static void assertRoute(
            GuideFreeplayPolicy.Route expected,
            GuideFreeplayPolicy.PlayerState state) {
        assertEquals(expected, recommend(state).route());
    }
}
