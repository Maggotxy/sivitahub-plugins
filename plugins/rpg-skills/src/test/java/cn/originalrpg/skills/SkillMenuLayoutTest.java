package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMenuLayoutTest {
    @Test
    void contentNeverOverlapsHeaderOrControls() {
        assertEquals(SkillMenuLayout.CONTENT.size(), new HashSet<>(SkillMenuLayout.CONTENT).size());
        Set<Integer> reserved = Set.of(SkillMenuLayout.HEADER, SkillMenuLayout.MAIN_MENU,
                SkillMenuLayout.NEXT_OBJECTIVE, SkillMenuLayout.MENTOR, SkillMenuLayout.PREVIOUS,
                SkillMenuLayout.CLASS_AND_ATTRIBUTES, SkillMenuLayout.CONTROLS, SkillMenuLayout.HUD,
                SkillMenuLayout.HELP, SkillMenuLayout.JOURNEY, SkillMenuLayout.NEXT);
        assertTrue(SkillMenuLayout.CONTENT.stream().noneMatch(reserved::contains));
        assertTrue(SkillMenuLayout.CONTENT.stream().allMatch(slot -> slot >= 0 && slot < 54));
    }
}
