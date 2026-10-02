package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.HiddenPath;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural reachability contract. "Reachable" means: every gate a hidden
 * route names fits inside the route's own ascension and level budget. Gates are
 * authored stage-locally ("minimum-ascension" + "minimum-level" in RpgSkills /
 * RpgDungeons), so they are offset onto the absolute effective-level ladder
 * before comparing. The production facts arrive from those plugins at startup;
 * these tests feed equivalent facts and prove the check rejects a
 * contradiction rather than describing the source text.
 */
class HiddenPathReachabilityTest {
    @Test
    void everyAuthoredRouteIsReachableWithTheShippedGateFacts() {
        Map<HiddenPath, HiddenPathReachability> facts = HiddenPathReachability.authored();
        assertEquals(Set.of(HiddenPath.BLOODPACT, HiddenPath.TALISMAN), facts.keySet());
        for (HiddenPath path : HiddenPath.values()) {
            Set<String> blockers = HiddenPathReachability.unattainable(path, facts.get(path));
            assertTrue(blockers.isEmpty(), path + " must be reachable, blockers=" + blockers);
        }
    }

    @Test
    void bloodpactGateFollowsItsStageThreePrerequisiteSkill() {
        // G-106: BLOODPACT required ascension 2 while vanished-horizon is the
        // stage-3 assassin branch skill, so its own gate was unsatisfiable.
        assertEquals(3, HiddenPathRules.minimumAscension(HiddenPath.BLOODPACT));
        assertEquals(2, HiddenPathRules.minimumAscension(HiddenPath.TALISMAN));
        assertNotEquals(2, HiddenPathRules.minimumAscension(HiddenPath.BLOODPACT));
        // The documented build keeps level 20, Agi 32 / Sta 18, the same hidden
        // dungeon and the same credential book; only the impossible gate moved.
        assertEquals(20, HiddenPathRules.minimumLevel(HiddenPath.BLOODPACT));
        assertEquals("vanished-horizon", HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT));
        assertEquals("storm-vault-hidden", HiddenPathRules.requiredDungeon(HiddenPath.BLOODPACT));
        assertEquals("silent-thunder", HiddenPathRules.requiredSkillBook(HiddenPath.BLOODPACT));
    }

    @Test
    void routeNeedingASkillFromALaterAscensionIsUnattainable() {
        // The G-106 shape, kept as a regression: a stage-2 route whose required
        // skill is authored against the stage-3 branch.
        HiddenPathReachability stageThreeSkill = new HiddenPathReachability(
                "future-skill", 3, Set.of("rune-scholar"), 10,
                "sulfur-depths-hidden", 20, "sulfur-depths", 12, "cinnabar-revelation", 20);
        Set<String> blockers = HiddenPathReachability.unattainable(HiddenPath.TALISMAN, stageThreeSkill);
        assertTrue(blockers.contains(HiddenPathRules.requiredSkill(HiddenPath.TALISMAN)),
                "a route must not open before its own prerequisite skill: " + blockers);

        // The same facts are fine once the route's gate catches up.
        HiddenPathReachability stageThreeRoute = new HiddenPathReachability(
                "vanished-horizon", 3, Set.of("unseen"), 20,
                "storm-vault-hidden", 28, "storm-vault", 22, "silent-thunder", 28);
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.BLOODPACT, stageThreeRoute).isEmpty());
    }

    @Test
    void routeNeedingALaterDungeonHiddenBookOrTicketIsUnattainable() {
        // A gate at effective level 110 cannot be reached before the 3rd
        // ascension, so a stage-2 route can never satisfy it.
        HiddenPathReachability laterDungeon = new HiddenPathReachability(
                "rune-reversal", 2, Set.of("rune-scholar"), 10,
                "storm-vault-hidden", 110, "sulfur-depths", 12, "cinnabar-revelation", 20);
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.TALISMAN, laterDungeon)
                .contains("hidden_dungeon"), laterDungeon.toString());

        HiddenPathReachability laterBook = new HiddenPathReachability(
                "rune-reversal", 2, Set.of("rune-scholar"), 10,
                "sulfur-depths-hidden", 20, "sulfur-depths", 12, "silent-thunder", 110);
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.TALISMAN, laterBook)
                .contains("skill_book"), laterBook.toString());

        HiddenPathReachability laterTicket = new HiddenPathReachability(
                "rune-reversal", 2, Set.of("rune-scholar"), 10,
                "sulfur-depths-hidden", 20, "storm-vault", 100, "cinnabar-revelation", 20);
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.TALISMAN, laterTicket)
                .contains("skill_book"), laterTicket.toString());

        // A stage-3 route reaches effective 120, so the same facts fit there.
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.BLOODPACT, laterDungeon).isEmpty());
    }

    @Test
    void stageLocalGatesAreOffsetByTheAscensionThatOpensThem() {
        // TALISMAN opens at ascension 2, whose top effective level is 90. Its
        // gate authors local levels 12..30 against that same stage, so every
        // shipped stage-2-local gate (12 / 20) fits by construction.
        HiddenPathReachability talismanFacts = HiddenPathReachability.authored(HiddenPath.TALISMAN);
        assertEquals(12, talismanFacts.ticketSourceEffectiveLevel());
        assertEquals(20, talismanFacts.hiddenDungeonEffectiveLevel());
        assertEquals(20, talismanFacts.skillBookEffectiveLevel());
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.TALISMAN, talismanFacts).isEmpty());

        // A gate authored ABOVE the local cap is absolute, so a stage-3-only
        // level can never be satisfied by the stage-2 route.
        HiddenPathReachability absoluteGate = new HiddenPathReachability(
                "rune-reversal", 2, Set.of("rune-scholar"), 10,
                "storm-vault-hidden", 118, "sulfur-depths", 12, "cinnabar-revelation", 20);
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.TALISMAN, absoluteGate)
                .contains("hidden_dungeon"), "effective 118 needs the 3rd ascension");
        // ... and the same absolute gate fits a 3rd-ascension route (cap 120).
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.BLOODPACT, absoluteGate).isEmpty());
    }

    @Test
    void gatesBeyondTheThirdAscensionAreUnreachable() {
        HiddenPathReachability impossible = new HiddenPathReachability(
                "rune-reversal", 4, Set.of("rune-scholar"), 10,
                "sulfur-depths-hidden", 20, "sulfur-depths", 12, "cinnabar-revelation", 20);
        Set<String> blockers = HiddenPathReachability.unattainable(HiddenPath.TALISMAN, impossible);
        assertTrue(blockers.contains(HiddenPathRules.requiredSkill(HiddenPath.TALISMAN)), blockers.toString());
    }

    @Test
    void effectiveLevelGatesAreComparedAcrossStagesNotAsLocalLevels() {
        // A stage-3 local 20 is effective 110; a stage-2 local 20 is only 80.
        assertEquals(110, ProgressionRules.effectiveLevel(3, 20));
        assertEquals(80, ProgressionRules.effectiveLevel(2, 20));
        assertEquals(90, ProgressionRules.effectiveLevel(2, 30));
        assertEquals(120, ProgressionRules.effectiveLevel(3, 30));
        assertEquals(3, HiddenPathReachability.ascensionOf(90));
        assertEquals(3, HiddenPathReachability.ascensionOf(120));
        assertEquals(2, HiddenPathReachability.ascensionOf(61));
        assertEquals(1, HiddenPathReachability.ascensionOf(30));
        assertEquals(0, HiddenPathReachability.ascensionOf(29));
        assertEquals(0, HiddenPathReachability.ascensionOf(1));
        assertFalse(ProgressionRules.effectiveLevel(2, 20) >= ProgressionRules.effectiveLevel(3, 20));
        // The gate must stay inside the legal ladder.
        assertThrows(IllegalArgumentException.class, () -> HiddenPathReachability.ascensionOf(0));
        assertThrows(IllegalArgumentException.class, () -> HiddenPathReachability.ascensionOf(121));
        assertEquals(3, ProgressionRules.MAX_ASCENSION);
        assertEquals(120, ProgressionRules.effectiveLevelCap());
    }

    @Test
    void authoredFactsRefuseToDriftAwayFromTheRouteRules() {
        Map<HiddenPath, HiddenPathReachability> facts = HiddenPathReachability.authored();
        for (HiddenPath path : HiddenPath.values()) {
            HiddenPathReachability fact = facts.get(path);
            assertEquals(HiddenPathRules.requiredSkill(path), fact.requiredSkill(), path.toString());
            assertEquals(HiddenPathRules.requiredDungeon(path), fact.requiredDungeon(), path.toString());
            assertEquals(HiddenPathRules.requiredSkillBook(path), fact.requiredSkillBook(), path.toString());
            assertTrue(fact.requiredSkillAscension() <= ProgressionRules.MAX_ASCENSION, path.toString());
            assertTrue(fact.requiredSkillAscension() <= HiddenPathRules.minimumAscension(path), path.toString());
            assertTrue(fact.hiddenDungeonEffectiveLevel() <= ProgressionRules.effectiveLevelCap(), path.toString());
            assertTrue(fact.ticketSourceEffectiveLevel() <= ProgressionRules.effectiveLevelCap(), path.toString());
        }
    }
}
