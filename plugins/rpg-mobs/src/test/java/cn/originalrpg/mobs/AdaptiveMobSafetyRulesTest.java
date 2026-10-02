package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveMobSafetyRulesTest {
    @Test
    void administratorTestingIsNotANaturalScalingSource() {
        assertFalse(AdaptiveMobSafetyRules.eligibleScalingSource(true, 210, 30));
        assertTrue(AdaptiveMobSafetyRules.eligibleScalingSource(true, 12, 30));
        assertTrue(AdaptiveMobSafetyRules.eligibleScalingSource(false, 210, 30));
        assertThrows(IllegalArgumentException.class,
                () -> AdaptiveMobSafetyRules.eligibleScalingSource(true, 0, 30));
    }

    @Test
    void cleanupBoundaryTracksTheMonotonicContentChapter() {
        assertEquals(36, AdaptiveMobSafetyRules.maximumNaturalLevel(0, 6));
        assertEquals(66, AdaptiveMobSafetyRules.maximumNaturalLevel(1, 6));
        assertEquals(96, AdaptiveMobSafetyRules.maximumNaturalLevel(2, 6));
        assertEquals(126, AdaptiveMobSafetyRules.maximumNaturalLevel(3, 6));
        assertEquals(30, AdaptiveMobSafetyRules.maximumPlayerLevel(0));
        assertEquals(60, AdaptiveMobSafetyRules.maximumPlayerLevel(1));
        // G-111: 终态章节天花板收敛到 ProgressionRules.effectiveLevelCap()，不再手写乘加公式
        assertEquals(cn.originalrpg.api.rules.ProgressionRules.effectiveLevelCap(),
                AdaptiveMobSafetyRules.maximumPlayerLevel(
                        cn.originalrpg.api.rules.ProgressionRules.MAX_ASCENSION));
        assertEquals(90, AdaptiveMobSafetyRules.maximumPlayerLevel(2));
        assertThrows(IllegalArgumentException.class,
                () -> AdaptiveMobSafetyRules.maximumNaturalLevel(4, 6));
    }
}
