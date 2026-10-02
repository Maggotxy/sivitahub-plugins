package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLevelRulesTest {
    private static final List<Integer> CAPS = List.of(2, 3, 4, 5);
    private static final List<Integer> COSTS = List.of(2, 4, 6, 8);

    @Test
    void everyAscensionUnlocksExactlyOneAdditionalBoundedLevel() {
        assertEquals(2, SkillLevelRules.maximumLevel(CAPS, 0));
        assertEquals(3, SkillLevelRules.maximumLevel(CAPS, 1));
        assertEquals(4, SkillLevelRules.maximumLevel(CAPS, 2));
        assertEquals(5, SkillLevelRules.maximumLevel(CAPS, 3));
        assertEquals(2, SkillLevelRules.maximumLevel(CAPS, -5));
        assertEquals(5, SkillLevelRules.maximumLevel(CAPS, 99));
        assertEquals(5, SkillLevelRules.absoluteMaximumLevel(CAPS));
    }

    @Test
    void upgradeCostsAddressTheCurrentLevelAndStayPositive() {
        assertEquals(2, SkillLevelRules.upgradeCost(COSTS, 1));
        assertEquals(4, SkillLevelRules.upgradeCost(COSTS, 2));
        assertEquals(6, SkillLevelRules.upgradeCost(COSTS, 3));
        assertEquals(8, SkillLevelRules.upgradeCost(COSTS, 4));
        assertEquals(0, SkillLevelRules.upgradeCost(COSTS, 0));
    }

    @Test
    void allFiveLevelsHaveStrictlyBetterButBoundedCooldowns() {
        long previous = Long.MAX_VALUE;
        for (int level = 1; level <= 5; level++) {
            long current = SkillLevelRules.cooldownMillis(20, level, 0.04, 0.84);
            assertTrue(current < previous, "level " + level);
            previous = current;
        }
        assertEquals(20_000L, SkillLevelRules.cooldownMillis(20, 1, 0.04, 0.84));
        assertEquals(16_800L, SkillLevelRules.cooldownMillis(20, 5, 0.04, 0.84));
        assertEquals(10_000L, SkillLevelRules.cooldownMillis(20, 99, 9.0, 0.1));
    }
}
