package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentJourneyPolicyTest {
    @Test
    void zeroSlotBlanksDoNotSoftLockTheOnboardingJourney() {
        assertTrue(EquipmentJourneyPolicy.slotOutcomeConfirmed(0, 0));
        assertTrue(EquipmentJourneyPolicy.slotOutcomeConfirmed(6, 1));
        assertFalse(EquipmentJourneyPolicy.slotOutcomeConfirmed(6, 0));
        assertFalse(EquipmentJourneyPolicy.slotOutcomeConfirmed(null, 0));
    }
}
