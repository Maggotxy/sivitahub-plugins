package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualModeTest {
    @Test
    void parsesEnglishAndChineseModes() {
        assertEquals(VisualMode.FULL, VisualMode.parse("full").orElseThrow());
        assertEquals(VisualMode.REDUCED, VisualMode.parse("精简").orElseThrow());
        assertEquals(VisualMode.MINIMAL, VisualMode.parse("极简").orElseThrow());
        assertEquals(VisualMode.OFF, VisualMode.parse("关闭").orElseThrow());
        assertTrue(VisualMode.parse("unknown").isEmpty());
    }

    @Test
    void scalesPacketsAndSoundWithoutLosingSmallReducedEffects() {
        assertEquals(20, VisualMode.FULL.particles(20));
        assertEquals(9, VisualMode.REDUCED.particles(20));
        assertEquals(1, VisualMode.REDUCED.particles(1));
        assertEquals(0, VisualMode.MINIMAL.particles(20));
        assertEquals(0.0F, VisualMode.OFF.volume(0.8));
    }
}
