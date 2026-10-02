package cn.originalrpg.skills;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.SkillProgress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMenuPolicyTest {
    @Test
    void unchosenPlayersSeeTheClassGuideInsteadOfEveryLockedSkill() {
        assertEquals(SkillMenuPolicy.Mode.CHOOSE_CLASS, SkillMenuPolicy.mode(Archetype.UNCHOSEN));
        assertEquals(SkillMenuPolicy.Mode.CHOOSE_CLASS, SkillMenuPolicy.mode(null));
    }

    @Test
    void chosenClassesSeeTheirOwnTree() {
        for (Archetype archetype : Archetype.values()) {
            if (archetype != Archetype.UNCHOSEN) {
                assertEquals(SkillMenuPolicy.Mode.SKILL_TREE, SkillMenuPolicy.mode(archetype));
            }
        }
    }

    @Test
    void availablePointsSubtractPersistedPointCostsAndNeverGoNegative() {
        List<SkillProgress> learned = List.of(
                new SkillProgress("heal", 1, 1, Optional.empty()),
                new SkillProgress("renewal", 1, 2, Optional.empty()));

        assertEquals(2, SkillMenuPolicy.remainingPoints(5, learned));
        assertEquals(0, SkillMenuPolicy.remainingPoints(2, learned));
        assertEquals(0, SkillMenuPolicy.remainingPoints(-1, learned));
        assertEquals(5, SkillMenuPolicy.remainingPoints(5, null));
    }

    @Test
    void lockedSkillsCannotDispatchAndLearningKeepsTheTreeOpen() {
        assertEquals("learn heal", SkillMenuPolicy.action(false, null, "heal"));
        assertEquals("skill heal 1", SkillMenuPolicy.action(true, null, "heal"));
        assertEquals("skill heal 4", SkillMenuPolicy.action(true, false, null, "heal", 4));
        assertEquals("bind heal", SkillMenuPolicy.action(false, true, null, "heal"));
        assertNull(SkillMenuPolicy.action(false, "needs intelligence", "heal"));
        assertTrue(SkillMenuPolicy.keepsMenuOpen("learn heal"));
        assertTrue(SkillMenuPolicy.keepsMenuOpen("page:1"));
        assertTrue(SkillMenuPolicy.keepsMenuOpen("bind heal"));
        assertTrue(SkillMenuPolicy.keepsMenuOpen("skill heal 4"));
        assertFalse(SkillMenuPolicy.keepsMenuOpen("command:menu"));
    }

    @Test
    void lockedObjectivesRouteToTheSystemThatCanResolveThem() {
        assertEquals("command:class", SkillMenuPolicy.routeFor(SkillMenuPolicy.Gate.ATTRIBUTE));
        assertEquals("command:class", SkillMenuPolicy.routeFor(SkillMenuPolicy.Gate.BRANCH));
        assertEquals("command:mobguide", SkillMenuPolicy.routeFor(SkillMenuPolicy.Gate.LEVEL));
        assertEquals("command:mobguide", SkillMenuPolicy.routeFor(SkillMenuPolicy.Gate.POINTS));
        assertNull(SkillMenuPolicy.routeFor(SkillMenuPolicy.Gate.PREREQUISITE));
        assertNull(SkillMenuPolicy.routeFor(null));
    }

    @Test
    void nextObjectivePrefersSomethingActionable() {
        assertTrue(SkillMenuPolicy.objectivePriority(null)
                < SkillMenuPolicy.objectivePriority(SkillMenuPolicy.Gate.ATTRIBUTE));
        assertTrue(SkillMenuPolicy.objectivePriority(SkillMenuPolicy.Gate.PREREQUISITE)
                < SkillMenuPolicy.objectivePriority(SkillMenuPolicy.Gate.LEVEL));
        assertTrue(SkillMenuPolicy.objectivePriority(SkillMenuPolicy.Gate.LEVEL)
                < SkillMenuPolicy.objectivePriority(SkillMenuPolicy.Gate.ASCENSION));
    }
}
