package cn.originalrpg.hub;

import cn.originalrpg.api.model.JourneyStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubJourneyPolicyTest {
    @Test
    void everyFirstHourGoalRoutesToARealEntry() {
        assertEquals(HubJourneyPolicy.Target.MAIN_MENU, HubJourneyPolicy.target(JourneyStep.HUB));
        assertEquals(HubJourneyPolicy.Target.CLASS_MENU, HubJourneyPolicy.target(JourneyStep.CLASS));
        assertEquals(HubJourneyPolicy.Target.CLASS_MENU, HubJourneyPolicy.target(JourneyStep.ATTRIBUTE));
        assertEquals(HubJourneyPolicy.Target.WILDERNESS, HubJourneyPolicy.target(JourneyStep.EXPLORE));
        assertEquals(HubJourneyPolicy.Target.COMBAT_GUIDE, HubJourneyPolicy.target(JourneyStep.HUNT));
        assertEquals(HubJourneyPolicy.Target.FORGE, HubJourneyPolicy.target(JourneyStep.FORGE));
        assertEquals(HubJourneyPolicy.Target.COMBAT_GUIDE, HubJourneyPolicy.target(JourneyStep.REVEAL));
        assertEquals(HubJourneyPolicy.Target.DUNGEON_MENU, HubJourneyPolicy.target(JourneyStep.DUNGEON));
        assertEquals(HubJourneyPolicy.Target.QUEST_CENTER, HubJourneyPolicy.target(null));
    }

    @Test
    void duplicateServiceButtonIsSuppressedOnlyForMatchingNpc() {
        assertTrue(HubJourneyPolicy.roleAlreadyServes(HubRole.CLASS, JourneyStep.CLASS));
        assertTrue(HubJourneyPolicy.roleAlreadyServes(HubRole.CLASS, JourneyStep.ATTRIBUTE));
        assertFalse(HubJourneyPolicy.roleAlreadyServes(HubRole.SKILLS, JourneyStep.CLASS));
        assertFalse(HubJourneyPolicy.roleAlreadyServes(HubRole.WILDERNESS, JourneyStep.HUNT));
        assertFalse(HubJourneyPolicy.roleAlreadyServes(HubRole.ENHANCEMENT, JourneyStep.REVEAL));
    }
}
