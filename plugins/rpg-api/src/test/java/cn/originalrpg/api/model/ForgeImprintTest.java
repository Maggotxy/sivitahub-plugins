package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForgeImprintTest {
    @Test
    void reportsActualBiasedStatProbability() {
        ForgeImprint normal = new ForgeImprint(UUID.randomUUID(), UUID.randomUUID(), "agility", "oak_log", 2);
        ForgeImprint potent = new ForgeImprint(UUID.randomUUID(), UUID.randomUUID(), "skill-haste", "potent_sulfur", 3);

        assertEquals(250.0 / 950.0, normal.selectedStatChance(), 0.000001);
        assertEquals(325.0 / 1025.0, potent.selectedStatChance(), 0.000001);
    }

    @Test
    void rejectsOutOfRangeStrength() {
        assertThrows(IllegalArgumentException.class,
                () -> new ForgeImprint(UUID.randomUUID(), UUID.randomUUID(), "agility", "oak_log", 0));
    }
}
