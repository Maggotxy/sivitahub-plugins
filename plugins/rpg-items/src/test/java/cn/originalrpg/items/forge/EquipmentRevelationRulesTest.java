package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EquipmentRevelationRulesTest {
    private static final List<Integer> DEFAULT_THRESHOLDS =
            List.of(50, 150, 350, 700, 1200, 2000, 3200, 5000);

    @Test
    void revealsSlotsAtConfiguredExperienceThresholds() {
        assertEquals(0, EquipmentRevelationRules.afterExperienceGain(0, 49, 8, DEFAULT_THRESHOLDS));
        assertEquals(1, EquipmentRevelationRules.afterExperienceGain(0, 50, 8, DEFAULT_THRESHOLDS));
        assertEquals(4, EquipmentRevelationRules.afterExperienceGain(0, 700, 8, DEFAULT_THRESHOLDS));
        assertEquals(8, EquipmentRevelationRules.afterExperienceGain(0, 5000, 8, DEFAULT_THRESHOLDS));
    }

    @Test
    void aThresholdChangeCannotClosePersistedSlots() {
        List<Integer> harderOrShorter = List.of(500, 1500);

        assertEquals(6, EquipmentRevelationRules.afterExperienceGain(6, 800, 8, harderOrShorter));
        assertEquals(6, EquipmentRevelationRules.afterExperienceGain(6, 800, 8, List.of()));
    }

    @Test
    void legacyValuesAreBoundedByTheSignedEquipmentSlotCount() {
        assertEquals(3, EquipmentRevelationRules.afterExperienceGain(7, 10_000, 3, DEFAULT_THRESHOLDS));
        assertEquals(0, EquipmentRevelationRules.afterExperienceGain(7, 10_000, -2, DEFAULT_THRESHOLDS));
        assertEquals(8, EquipmentRevelationRules.afterExperienceGain(9, 10_000, 99, DEFAULT_THRESHOLDS));
    }
}
