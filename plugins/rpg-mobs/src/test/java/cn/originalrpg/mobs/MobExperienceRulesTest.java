package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MobExperienceRulesTest {
    @Test
    void rewardsMeaningfulEarlyProgressAndScalesElites() {
        assertEquals(10L, MobExperienceRules.reward(1, false, 10L, 10L, 40L));
        assertEquals(70L, MobExperienceRules.reward(7, false, 10L, 10L, 40L));
        assertEquals(280L, MobExperienceRules.reward(7, true, 10L, 10L, 40L));
    }

    @Test
    void sanitisesNegativeValuesAndSaturatesOverflow() {
        assertEquals(0L, MobExperienceRules.reward(-1, false, -2L, -3L, -4L));
        assertEquals(Long.MAX_VALUE, MobExperienceRules.reward(
                Integer.MAX_VALUE, true, 1L, 1L, Long.MAX_VALUE));
    }
}
