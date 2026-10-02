package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BossAttackRulesTest {
    @Test
    void pulseHasAReadableSafeExterior() {
        BossAttackRules.Pattern pulse = BossAttackRules.pattern(0);
        assertTrue(BossAttackRules.hits(pulse, 2.0, 2.0));
        assertFalse(BossAttackRules.hits(pulse, 5.1, 0.0));
    }

    @Test
    void crossLeavesDiagonalSafeLanes() {
        BossAttackRules.Pattern cross = BossAttackRules.pattern(1);
        assertTrue(BossAttackRules.hits(cross, 0.5, 7.0));
        assertTrue(BossAttackRules.hits(cross, -8.0, 0.5));
        assertFalse(BossAttackRules.hits(cross, 4.0, 4.0));
        assertFalse(BossAttackRules.hits(cross, 10.0, 0.0));
    }

    @Test
    void ringAllowsEitherTheCenterOrTheExterior() {
        BossAttackRules.Pattern ring = BossAttackRules.pattern(2);
        assertFalse(BossAttackRules.hits(ring, 1.0, 1.0));
        assertTrue(BossAttackRules.hits(ring, 5.0, 0.0));
        assertFalse(BossAttackRules.hits(ring, 8.1, 0.0));
    }

    @Test
    void ruinsUseDiagonalLinesAndAlternatingSectors() {
        BossAttackRules.Pattern lines = BossAttackRules.pattern("ancient-ruins", 0);
        assertEquals(BossAttackRules.Shape.RUIN_DIAGONALS, lines.shape());
        assertTrue(BossAttackRules.hits(lines, 4.0, 4.0));
        assertFalse(BossAttackRules.hits(lines, 4.0, 0.0));

        BossAttackRules.Pattern sectors = BossAttackRules.pattern("ancient-ruins-hidden", 1);
        assertEquals(BossAttackRules.Shape.RUIN_QUADRANTS, sectors.shape());
        assertFalse(BossAttackRules.hits(sectors, 4.0, 0.0));
        assertTrue(BossAttackRules.hits(sectors, 0.0, 4.0));
    }

    @Test
    void sulfurUsesSeparatedVentsAndParallelFissures() {
        BossAttackRules.Pattern vents = BossAttackRules.pattern("sulfur-depths", 0);
        assertEquals(BossAttackRules.Shape.SULFUR_VENTS, vents.shape());
        assertTrue(BossAttackRules.hits(vents, 3.35, 0.0));
        assertFalse(BossAttackRules.hits(vents, 0.0, 0.0));

        BossAttackRules.Pattern fissures = BossAttackRules.pattern("sulfur-depths-hidden", 1);
        assertEquals(BossAttackRules.Shape.SULFUR_FISSURES, fissures.shape());
        assertTrue(BossAttackRules.hits(fissures, 2.0, 2.0));
        assertFalse(BossAttackRules.hits(fissures, 2.0, 0.0));
    }

    @Test
    void stormUsesRotatingHalfArenaAndSafeMiddleBand() {
        BossAttackRules.Pattern sweep = BossAttackRules.pattern("storm-vault", 0);
        assertEquals(BossAttackRules.Shape.STORM_SWEEP, sweep.shape());
        assertTrue(BossAttackRules.hits(sweep, 4.0, 0.0));
        assertFalse(BossAttackRules.hits(sweep, -4.0, 0.0));

        BossAttackRules.Pattern eye = BossAttackRules.pattern("storm-vault-hidden", 1);
        assertEquals(BossAttackRules.Shape.STORM_EYE, eye.shape());
        assertTrue(BossAttackRules.hits(eye, 0.0, 0.0));
        assertFalse(BossAttackRules.hits(eye, 4.0, 0.0));
        assertTrue(BossAttackRules.hits(eye, 7.5, 0.0));
    }

    @Test
    void telegraphWindowsBelowTheReadabilityFloorAreRaisedToIt() {
        // G-209 行为级验收：文档承诺的 0.4–0.8 秒（8–16 tick）在 20 tick 下限下必须被抬到 20。
        assertEquals(20L, BossAttackRules.readableTelegraphTicks(8L));
        assertEquals(20L, BossAttackRules.readableTelegraphTicks(16L));
        assertEquals(20L, BossAttackRules.readableTelegraphTicks(0L));
        assertEquals(20L, BossAttackRules.readableTelegraphTicks(-5L));
        // 已经可读的窗口不被静默改小，上限仍是 80。
        assertEquals(20L, BossAttackRules.readableTelegraphTicks(20L));
        assertEquals(36L, BossAttackRules.readableTelegraphTicks(36L));
        assertEquals(80L, BossAttackRules.readableTelegraphTicks(200L));
        assertEquals(20, BossAttackRules.MINIMUM_READABLE_TELEGRAPH_TICKS);
    }

    @Test
    void theReadabilityFloorGuaranteesAtLeastThreeRedraws() {
        // 下限的量化依据：预警期内重绘次数不足 3 次时，玩家来不及把图案和文案对齐。
        assertEquals(1, BossAttackRules.telegraphRedraws(8L));
        assertEquals(1, BossAttackRules.telegraphRedraws(10L));
        assertEquals(2, BossAttackRules.telegraphRedraws(12L));
        assertEquals(3, BossAttackRules.telegraphRedraws(16L));
        assertTrue(BossAttackRules.telegraphRedraws(
                        BossAttackRules.MINIMUM_READABLE_TELEGRAPH_TICKS) >= 3,
                "表现层保证的窗口必须至少有 3 次重绘");
    }

    @Test
    void unknownDungeonKeepsTheSafeLegacyRotation() {
        assertEquals(BossAttackRules.Shape.PULSE, BossAttackRules.pattern("future-family", 0).shape());
        assertEquals(BossAttackRules.Shape.CROSS, BossAttackRules.pattern("future-family", 1).shape());
        assertEquals(BossAttackRules.Shape.RING, BossAttackRules.pattern("future-family", 2).shape());
    }
}
