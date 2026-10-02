package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static cn.originalrpg.skills.EquipmentBorrowingRules.CastMode.BORROWED;
import static cn.originalrpg.skills.EquipmentBorrowingRules.CastMode.LEARNED;
import static cn.originalrpg.skills.EquipmentBorrowingRules.CastMode.MASTERED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EquipmentBorrowingRulesTest {
    @Test
    void runeSelectsExactlyOneCastModeAndNeverCreatesASecondSkill() {
        assertEquals(LEARNED, EquipmentBorrowingRules.mode(true, false));
        assertEquals(BORROWED, EquipmentBorrowingRules.mode(false, true));
        assertEquals(MASTERED, EquipmentBorrowingRules.mode(true, true));
        assertEquals(LEARNED, EquipmentBorrowingRules.mode(false, false));
    }

    @Test
    void borrowedAndMasteredCostsPowerAndDurationAreBounded() {
        assertEquals(18, EquipmentBorrowingRules.manaCost(12, BORROWED, 1.5));
        assertEquals(12, EquipmentBorrowingRules.manaCost(12, MASTERED, 9.0));
        assertEquals(12_000L, EquipmentBorrowingRules.cooldownMillis(8, BORROWED, 1.5, 0.85, 0));
        assertEquals(6_800L, EquipmentBorrowingRules.cooldownMillis(8, MASTERED, 1.5, 0.85, 0));
        assertEquals(5_712L, EquipmentBorrowingRules.cooldownFromBaseMillis(
                6_720L, MASTERED, 1.5, 0.85, 0));
        assertEquals(0.65, EquipmentBorrowingRules.powerMultiplier(BORROWED, 0.65, 1.15), 1.0E-9);
        assertEquals(1.15, EquipmentBorrowingRules.powerMultiplier(MASTERED, 0.65, 1.15), 1.0E-9);
        assertEquals(130, EquipmentBorrowingRules.durationTicks(200, BORROWED, 0.65, 1.15));
        assertEquals(230, EquipmentBorrowingRules.durationTicks(200, MASTERED, 0.65, 1.15));
    }

    @Test
    void unsafeOperatorValuesCannotCreateFreeCastsOrUnboundedMastery() {
        assertEquals(12, EquipmentBorrowingRules.manaCost(12, BORROWED, -10.0));
        assertEquals(12_000L, EquipmentBorrowingRules.cooldownMillis(
                8, BORROWED, Double.POSITIVE_INFINITY, 0.0, 0));
        assertEquals(0.25, EquipmentBorrowingRules.powerMultiplier(BORROWED, -9.0, 99.0), 1.0E-9);
        assertEquals(1.25, EquipmentBorrowingRules.powerMultiplier(MASTERED, -9.0, 99.0), 1.0E-9);
        assertEquals(120, EquipmentBorrowingRules.strainTicks(999));
        assertEquals(Long.MAX_VALUE, EquipmentBorrowingRules.cooldownMillis(
                Long.MAX_VALUE, LEARNED, 1.5, 0.85, 0));
    }
}
