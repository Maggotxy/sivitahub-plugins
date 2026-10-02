package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedSlotValueTest {
    @Test
    void parsesPositiveNegativeAndHyphenatedStats() {
        assertEquals(new RefinedSlotValue("strength", 12),
                RefinedSlotValue.parse("strength:12").orElseThrow());
        assertEquals(new RefinedSlotValue("defense", -5),
                RefinedSlotValue.parse("defense:-5").orElseThrow());
        assertEquals(new RefinedSlotValue("skill-haste", 7),
                RefinedSlotValue.parse("skill-haste:7").orElseThrow());
        assertEquals("+7", RefinedSlotValue.parse("skill-haste:7").orElseThrow().signedValue());
        assertEquals("-5", RefinedSlotValue.parse("defense:-5").orElseThrow().signedValue());
    }

    @Test
    void rejectsMissingUnknownAndMalformedValuesWithoutInventingData() {
        for (String encoded : new String[]{"", "strength", ":4", "strength:",
                "strength:not-a-number", "strength:2147483648", "unknown:4", "skill:haste:4"}) {
            assertTrue(RefinedSlotValue.parse(encoded).isEmpty(), encoded);
        }
        assertTrue(RefinedSlotValue.parse(null).isEmpty());
    }

    @Test
    void signedPdcValueStillProjectsAfterRepeatedLoreRebuilds() {
        String storedPdcValue = "critical:18";
        for (int equipmentExperienceUpdate = 0; equipmentExperienceUpdate < 20; equipmentExperienceUpdate++) {
            RefinedSlotValue projected = RefinedSlotValue.parse(storedPdcValue).orElseThrow();
            assertEquals("critical", projected.stat());
            assertEquals("+18", projected.signedValue());
        }
    }
}
