package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideFreeplayMenuPolicyTest {
    @Test
    void completedCardsOpenTheLiveRecommendationWhileReviewRemainsExplicit() {
        assertEquals("open:current", GuideFreeplayMenuPolicy.completedJourneyAction());
        assertEquals("open:onboarding", GuideFreeplayMenuPolicy.reviewJourneyAction());
        assertEquals("open:freeplay-routes", GuideFreeplayMenuPolicy.openAlternativesAction());
    }

    @Test
    void secondScreenContainsExactlyFiveBoundedAlternativeIntents() {
        Map<String, String> expected = Map.of(
                "tasks", "help:tasks",
                "level", "help:mobs",
                "build", "help:classes",
                "equipment", "help:equipment",
                "economy", "help:economy");
        Map<String, String> actual = GuideFreeplayMenuPolicy.alternatives().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        GuideFreeplayMenuPolicy.Alternative::id,
                        GuideFreeplayMenuPolicy.Alternative::action));

        assertEquals(expected, actual);
        assertEquals(Set.copyOf(expected.keySet()), actual.keySet());
        actual.values().forEach(action -> assertTrue(
                GuideFreeplayMenuPolicy.isTrustedAlternativeAction(action)));
    }

    @Test
    void arbitraryCommandsCannotEnterTheAlternativeDispatcher() {
        assertFalse(GuideFreeplayMenuPolicy.isTrustedAlternativeAction("command:op lamb"));
        assertFalse(GuideFreeplayMenuPolicy.isTrustedAlternativeAction("open:main"));
        assertFalse(GuideFreeplayMenuPolicy.isTrustedAlternativeAction(""));
        assertFalse(GuideFreeplayMenuPolicy.isTrustedAlternativeAction(null));
    }
}
