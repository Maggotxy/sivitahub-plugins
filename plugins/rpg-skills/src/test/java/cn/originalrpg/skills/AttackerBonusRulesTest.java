package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G-105：攻方增益的合成口径与「只结算一次」的数值边界。 */
final class AttackerBonusRulesTest {
    @Test
    void noBuffIsExactlyNeutral() {
        assertEquals(1.0, AttackerBonusRules.bonus(false, 0), 1.0E-9);
    }

    @Test
    void standardAndTranceBonusesAreAdditiveWithinOneMultiplier() {
        // 光环 +12%，连击每层 +6%；两个来源相加后合成一个乘数（与原实现一致）
        assertEquals(1.12, AttackerBonusRules.bonus(true, 0), 1.0E-9);
        assertEquals(1.12, AttackerBonusRules.bonus(false, 2), 1.0E-9);
        assertEquals(1.24, AttackerBonusRules.bonus(true, 2), 1.0E-9);
    }

    @Test
    void tranceBonusIsCappedAtFiveStacks() {
        assertEquals(1.30, AttackerBonusRules.bonus(false, 5), 1.0E-9);
        assertEquals(1.30, AttackerBonusRules.bonus(false, 99), 1.0E-9);
    }

    @Test
    void applyIsSingleSettlementNotDoubleAmplification() {
        double bonus = AttackerBonusRules.bonus(true, 1);
        // 单次结算：base × bonus，而不是 base × bonus × bonus
        assertEquals(11.8, AttackerBonusRules.apply(10.0, bonus), 1.0E-9);
        assertTrue(AttackerBonusRules.apply(10.0, bonus) < 10.0 * bonus * bonus);
    }

    @Test
    void applyNeverProducesNegativeOrNonFiniteDamage() {
        assertEquals(0.0, AttackerBonusRules.apply(Double.NaN, 1.2), 1.0E-9);
        assertEquals(0.0, AttackerBonusRules.apply(-5.0, 1.2), 1.0E-9);
        assertEquals(10.0, AttackerBonusRules.apply(10.0, Double.NaN), 1.0E-9);
        assertEquals(10.0, AttackerBonusRules.apply(10.0, 0.5), 1.0E-9);
    }

    @Test
    void stacksRestartAfterTheComboWindow() {
        assertEquals(1, AttackerBonusRules.nextStacks(10_000L, 0L, 4));
        assertEquals(3, AttackerBonusRules.nextStacks(10_000L, 9_000L, 2));
        assertEquals(5, AttackerBonusRules.nextStacks(10_000L, 9_000L, 5));
    }
}
