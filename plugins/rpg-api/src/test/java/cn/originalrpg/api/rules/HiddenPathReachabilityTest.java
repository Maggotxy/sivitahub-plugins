package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.HiddenPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-106 行为级回归：隐藏路线的每个前置都必须能在路线自身开放之前满足。
 * 这里真的调用 {@link HiddenPathReachability#unattainable} 并断言结果，
 * 不是对源码字符串做 indexOf。
 */
class HiddenPathReachabilityTest {

    private static HiddenPathReachability.Precondition skill(String id, int ascension, int local) {
        return new HiddenPathReachability.Precondition(
                id, ascension, local, HiddenPathReachability.Requirement.SKILL);
    }

    private static HiddenPathReachability.Precondition dungeon(String id, int effective, HiddenPathReachability.Requirement requirement) {
        return new HiddenPathReachability.Precondition(
                id,
                HiddenPathReachability.ascensionOf(effective),
                effective - HiddenPathReachability.ascensionOf(effective) * ProgressionRules.LEVELS_PER_ASCENSION,
                requirement);
    }

    @Test
    void bothRoutesRequireAscensionTwo() {
        // 规则门按 docs/GAME_DESIGN.md:106 与另 4 份文档的「二转」。
        assertEquals(2, HiddenPathRules.minimumAscension(HiddenPath.BLOODPACT));
        assertEquals(2, HiddenPathRules.minimumAscension(HiddenPath.TALISMAN));
        assertEquals(ProgressionRules.effectiveLevel(2, 30),
                HiddenPathReachability.routeCeiling(HiddenPath.BLOODPACT));
    }

    @Test
    void aStageThreePrerequisiteIsSeenAsUnreachableForATwoAscensionRoute() {
        // G-106 的判据：前置技能要到 3 转 → 该前置永远晚于 2 转路线开放。
        int gate = ProgressionRules.effectiveLevel(3, 20);
        assertTrue(gate > HiddenPathReachability.routeCeiling(HiddenPath.BLOODPACT));
        assertEquals(Set.of(HiddenPathReachability.Requirement.SKILL),
                HiddenPathReachability.unattainable(HiddenPath.BLOODPACT,
                        List.of(skill("vanished-horizon", 3, 20))));
    }

    @Test
    void aTwoAscensionPrerequisiteMakesBloodpactReachable() {
        // 判定的「配置错」修法（已落地在 config.yml）：技能门降到与路线一致。
        List<HiddenPathReachability.Precondition> preconditions = List.of(
                skill("vanished-horizon", 2, 20),
                dungeon("storm-vault-hidden", 28, HiddenPathReachability.Requirement.HIDDEN_DUNGEON),
                dungeon("storm-vault", 22, HiddenPathReachability.Requirement.TICKET_SOURCE),
                dungeon("silent-thunder", 28, HiddenPathReachability.Requirement.SKILL_BOOK));
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.BLOODPACT, preconditions).isEmpty());
        assertTrue(HiddenPathReachability.unattainableDetails(HiddenPath.BLOODPACT, preconditions).isEmpty());
    }

    @Test
    void shippedTalismanPreconditionsAreReachable() {
        List<HiddenPathReachability.Precondition> preconditions = List.of(
                skill("rune-reversal", 2, 10),
                dungeon("sulfur-depths-hidden", 20, HiddenPathReachability.Requirement.HIDDEN_DUNGEON),
                dungeon("sulfur-depths", 12, HiddenPathReachability.Requirement.TICKET_SOURCE),
                dungeon("cinnabar-revelation", 20, HiddenPathReachability.Requirement.SKILL_BOOK));
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.TALISMAN, preconditions).isEmpty());
    }

    @Test
    void g106ShapeIsRejectedByBothRoutes() {
        // G-106 原始形态：路线门 2 转，但前置技能要到 3 转才能学。
        // 同一形态对两条路线都必须成立 —— 判据不认具体路线，只认「前置晚于路线」。
        for (HiddenPath path : HiddenPath.values()) {
            assertEquals(2, HiddenPathRules.minimumAscension(path));
            List<HiddenPathReachability.Precondition> stageThreeSkill =
                    List.of(skill("vanished-horizon", 3, 20));
            Set<HiddenPathReachability.Requirement> blockers =
                    HiddenPathReachability.unattainable(path, stageThreeSkill);
            assertEquals(Set.of(HiddenPathReachability.Requirement.SKILL), blockers, path.name());

            Map<HiddenPathReachability.Requirement, Set<String>> details =
                    HiddenPathReachability.unattainableDetails(path, stageThreeSkill);
            assertEquals(Set.of("vanished-horizon"),
                    details.get(HiddenPathReachability.Requirement.SKILL), path.name());
            assertFalse(details.isEmpty());
        }
    }

    @Test
    void lateDungeonAndTicketAndBookAreEachNamed() {
        List<HiddenPathReachability.Precondition> preconditions = List.of(
                skill("rune-reversal", 2, 10),
                dungeon("sulfur-depths-hidden", 120, HiddenPathReachability.Requirement.HIDDEN_DUNGEON),
                dungeon("sulfur-depths", 120, HiddenPathReachability.Requirement.TICKET_SOURCE),
                dungeon("cinnabar-revelation", 120, HiddenPathReachability.Requirement.SKILL_BOOK));
        Map<HiddenPathReachability.Requirement, Set<String>> details =
                HiddenPathReachability.unattainableDetails(HiddenPath.TALISMAN, preconditions);
        assertEquals(Set.of(HiddenPathReachability.Requirement.HIDDEN_DUNGEON,
                        HiddenPathReachability.Requirement.TICKET_SOURCE,
                        HiddenPathReachability.Requirement.SKILL_BOOK),
                Set.copyOf(details.keySet()));
    }

    @Test
    void describeNamesTheRouteAndTheBlockingPrecondition() {
        Map<HiddenPathReachability.Requirement, Set<String>> details =
                HiddenPathReachability.unattainableDetails(HiddenPath.TALISMAN,
                        List.of(skill("vanished-horizon", 3, 20)));
        String text = HiddenPathReachability.describe(HiddenPath.TALISMAN, details);
        assertTrue(text.contains("TALISMAN"), text);
        assertTrue(text.contains("vanished-horizon"), text);
        assertTrue(text.contains("SKILL"), text);
    }

    @Test
    void preconditionValidatesItsOwnBounds() {
        // 本地等级必须落在 1..30，转数必须落在 0..3 —— 防人工写错门。
        assertThrows(IllegalArgumentException.class, () -> skill("x", 0, 31));
        assertThrows(IllegalArgumentException.class, () -> skill("x", 4, 1));
        assertThrows(IllegalArgumentException.class, () -> skill("x", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> skill("x", 1, 0));
    }

    @Test
    void ascensionOfMapsTheEffectiveLadderOntoStages() {
        assertEquals(0, HiddenPathReachability.ascensionOf(1));
        assertEquals(0, HiddenPathReachability.ascensionOf(30));
        assertEquals(1, HiddenPathReachability.ascensionOf(31));
        assertEquals(1, HiddenPathReachability.ascensionOf(60));
        assertEquals(2, HiddenPathReachability.ascensionOf(61));
        assertEquals(3, HiddenPathReachability.ascensionOf(91));
        assertEquals(3, HiddenPathReachability.ascensionOf(120));
        assertThrows(IllegalArgumentException.class, () -> HiddenPathReachability.ascensionOf(0));
        assertThrows(IllegalArgumentException.class, () -> HiddenPathReachability.ascensionOf(121));
    }
}
