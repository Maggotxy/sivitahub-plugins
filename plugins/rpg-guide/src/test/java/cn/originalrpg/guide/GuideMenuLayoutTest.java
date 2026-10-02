package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideMenuLayoutTest {
    @Test
    void mainSlotsAreUniqueAndInsideInventory() {
        assertEquals(GuideMenuLayout.mainSlots().size(),
                new HashSet<>(GuideMenuLayout.mainSlots()).size());
        assertTrue(GuideMenuLayout.mainSlots().stream()
                .allMatch(slot -> slot >= 0 && slot < GuideMenuLayout.SIZE));
        assertTrue(!GuideMenuLayout.mainSlots().contains(GuideMenuLayout.BACK));
    }

    @Test
    void onboardingSkillShortcutDoesNotOverlapJourneyOrNavigation() {
        java.util.Set<Integer> journey = new java.util.HashSet<>(GuideMenuLayout.ONBOARDING_STEP_SLOTS);
        journey.add(GuideMenuLayout.ONBOARDING_CURRENT);
        journey.add(GuideMenuLayout.ONBOARDING_STORY);
        journey.add(GuideMenuLayout.ONBOARDING_FULL_GUIDE);
        journey.add(GuideMenuLayout.BACK);

        assertTrue(!journey.contains(GuideMenuLayout.ONBOARDING_SKILLS));
        assertTrue(GuideMenuLayout.ONBOARDING_SKILLS >= 0
                && GuideMenuLayout.ONBOARDING_SKILLS < GuideMenuLayout.SIZE);
    }

    @Test
    void eightJourneyStepsUseTwoInteriorRowsOfFour() {
        assertEquals(java.util.List.of(10, 12, 14, 16, 19, 21, 23, 25),
                GuideMenuLayout.ONBOARDING_STEP_SLOTS);
        assertEquals(8, new java.util.HashSet<>(GuideMenuLayout.ONBOARDING_STEP_SLOTS).size());
        assertTrue(GuideMenuLayout.ONBOARDING_STEP_SLOTS.stream().allMatch(slot -> {
            int row = slot / 9;
            int column = slot % 9;
            return (row == 1 || row == 2) && column > 0 && column < 8;
        }));
    }

    @Test
    void taskCenterKeepsTheLiveObjectiveInTheTopCenterSlot() {
        assertEquals(4, GuideMenuLayout.QUESTS_CURRENT);
        assertTrue(GuideMenuLayout.QUESTS_CURRENT >= 0
                && GuideMenuLayout.QUESTS_CURRENT < GuideMenuLayout.SIZE);
        assertTrue(GuideMenuLayout.QUESTS_CURRENT != GuideMenuLayout.BACK);
    }

    @Test
    void weeklyExpeditionUsesInteriorSemanticSlots() {
        assertEquals(20, GuideMenuLayout.ADVENTURE_SEASON);
        assertEquals(29, GuideMenuLayout.DUNGEON_SEASON);
        assertTrue(GuideMenuLayout.ADVENTURE_SEASON % 9 > 0
                && GuideMenuLayout.ADVENTURE_SEASON % 9 < 8);
        assertTrue(GuideMenuLayout.DUNGEON_SEASON % 9 > 0
                && GuideMenuLayout.DUNGEON_SEASON % 9 < 8);
    }
}
