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
    void unknownDungeonKeepsTheSafeLegacyRotation() {
        assertEquals(BossAttackRules.Shape.PULSE, BossAttackRules.pattern("future-family", 0).shape());
        assertEquals(BossAttackRules.Shape.CROSS, BossAttackRules.pattern("future-family", 1).shape());
        assertEquals(BossAttackRules.Shape.RING, BossAttackRules.pattern("future-family", 2).shape());
    }
}
