package cn.originalrpg.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldEventRulesTest {
    @Test
    void bloodMoonSpecialsRespectTheFirstBuildSafetyWindow() {
        assertFalse(WorldEventRules.bloodMoonSpecialsAllowed(0, 5));
        assertFalse(WorldEventRules.bloodMoonSpecialsAllowed(4, 5));
        assertTrue(WorldEventRules.bloodMoonSpecialsAllowed(5, 5));
    }

    @Test
    void theFourthBloodMoonOfTheSameDayIsRejectedAndTheNextDayResets() {
        // G-207 行为级验收：文档承诺「每日最多 3 场」，这里显式断言第 4 场被拒。
        int cap = 3;
        long day = 7L;
        long counterDay = Long.MIN_VALUE;
        int used = 0;
        for (int event = 1; event <= 3; event++) {
            assertTrue(WorldEventRules.dailyEventAllowed(day, counterDay, used, cap), "第 " + event + " 场");
            counterDay = day;
            used++;
        }
        assertFalse(WorldEventRules.dailyEventAllowed(day, counterDay, used, cap), "第 4 场必须被拒");

        // 跨日重置：同一天连续拒，次日重新放行。
        assertTrue(WorldEventRules.dailyEventAllowed(day + 1, counterDay, used, cap), "次日必须恢复");
    }

    @Test
    void aZeroDailyCapMeansNoEventLimit() {
        assertTrue(WorldEventRules.dailyEventAllowed(1L, 1L, 99L, 0));
        assertTrue(WorldEventRules.dailyEventAllowed(1L, 1L, 99L, -1));
    }

    @Test
    void bloodMoonCountdownMatchesTheLiveSevenNightCycle() {
        assertEquals(7L, WorldEventRules.bloodMoon(0L, 0L, 7).nightsUntil());
        assertEquals(1L, WorldEventRules.bloodMoon(6L * 24_000L, 6_000L, 7).nightsUntil());
        assertEquals(0L, WorldEventRules.bloodMoon(7L * 24_000L, 6_000L, 7).nightsUntil());

        WorldEventRules.BloodMoonTiming active = WorldEventRules.bloodMoon(
                7L * 24_000L + 13_000L, 13_000L, 7);
        assertTrue(active.active());
        assertEquals(500L, active.remainingSeconds());

        WorldEventRules.BloodMoonTiming finalNightTick = WorldEventRules.bloodMoon(
                7L * 24_000L + 22_999L, 22_999L, 7);
        assertTrue(finalNightTick.active());
        assertEquals(1L, finalNightTick.remainingSeconds());
        assertTrue(WorldEventRules.bloodMoonProgress(22_999L) > 0.0F);

        WorldEventRules.BloodMoonTiming ended = WorldEventRules.bloodMoon(
                7L * 24_000L + 23_000L, 23_000L, 7);
        assertFalse(ended.active());
        assertEquals(7L, ended.nightsUntil());
        assertEquals(0.0F, WorldEventRules.bloodMoonProgress(23_000L));
    }

    @Test
    void huntBonusStartsAfterTheFirstKillAndIsCapped() {
        assertEquals(0.0, WorldEventRules.huntBonus(1, 0.002, 0.08), 0.000001);
        assertEquals(0.018, WorldEventRules.huntBonus(10, 0.002, 0.08), 0.000001);
        assertEquals(0.08, WorldEventRules.huntBonus(100, 0.002, 0.08), 0.000001);
    }

    @Test
    void huntContinuationUsesEveryDocumentedResetCondition() {
        assertTrue(WorldEventRules.continuesHunt(
                "ZOMBIE", "ZOMBIE", true, 128.0 * 128.0, 128.0, 300_000L, 300L));
        assertFalse(WorldEventRules.continuesHunt(
                "ZOMBIE", "SKELETON", true, 1.0, 128.0, 1_000L, 300L));
        assertFalse(WorldEventRules.continuesHunt(
                "ZOMBIE", "ZOMBIE", false, 1.0, 128.0, 1_000L, 300L));
        assertFalse(WorldEventRules.continuesHunt(
                "ZOMBIE", "ZOMBIE", true, 129.0 * 129.0, 128.0, 1_000L, 300L));
        assertFalse(WorldEventRules.continuesHunt(
                "ZOMBIE", "ZOMBIE", true, 1.0, 128.0, 300_001L, 300L));
        assertEquals(1L, WorldEventRules.huntSecondsRemaining(299_001L, 300L));
    }

    @Test
    void huntIdentitySeparatesBabyAdultChargedAndSizedVariants() {
        String adult = WorldEventRules.huntIdentity("ZOMBIE", "adult");
        String baby = WorldEventRules.huntIdentity("ZOMBIE", "baby");
        String charged = WorldEventRules.huntIdentity("CREEPER", "charged");
        String normal = WorldEventRules.huntIdentity("CREEPER", "normal");
        String smallSlime = WorldEventRules.huntIdentity("SLIME", "size-1");
        String largeSlime = WorldEventRules.huntIdentity("SLIME", "size-4");

        assertFalse(WorldEventRules.continuesHunt(adult, baby, true, 1.0, 128.0, 1_000L, 300L));
        assertFalse(WorldEventRules.continuesHunt(charged, normal, true, 1.0, 128.0, 1_000L, 300L));
        assertFalse(WorldEventRules.continuesHunt(smallSlime, largeSlime, true, 1.0, 128.0, 1_000L, 300L));
        assertTrue(WorldEventRules.continuesHunt(baby, baby, true, 1.0, 128.0, 1_000L, 300L));
    }
}
