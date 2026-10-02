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
    void bloodpactIsThreeAscensionAndTalismanIsTwo() {
        // G-106 设计裁决：血契要求的三转与前缀技能 vanished-horizon（刺客三转汇合分支
        // unseen）的开放转数一致；符箓的前缀技能 rune-reversal 是二转技术，保持二转。
        assertEquals(3, HiddenPathRules.minimumAscension(HiddenPath.BLOODPACT));
        assertEquals(2, HiddenPathRules.minimumAscension(HiddenPath.TALISMAN));
        assertEquals(ProgressionRules.effectiveLevel(3, 30),
                HiddenPathReachability.routeCeiling(HiddenPath.BLOODPACT));
    }

    @Test
    void aStageThreePrerequisiteIsReachableForTheThreeAscensionRoute() {
        // G-106 修好后：vanished-horizon 的三转门与血契的三转门一致 ⇒ 可达。
        assertEquals(Set.of(),
                HiddenPathReachability.unattainable(HiddenPath.BLOODPACT,
                        List.of(skill("vanished-horizon", 3, 20))));
    }

    @Test
    void aStageThreePrerequisiteIsStillUnreachableForATwoAscensionRoute() {
        // 判定不认具体路线，只认「前置晚于路线」：符箓仍是二转，三转前置对它不可达。
        int gate = ProgressionRules.effectiveLevel(3, 20);
        assertTrue(gate > HiddenPathReachability.routeCeiling(HiddenPath.TALISMAN));
        assertEquals(Set.of(HiddenPathReachability.Requirement.SKILL),
                HiddenPathReachability.unattainable(HiddenPath.TALISMAN,
                        List.of(skill("vanished-horizon", 3, 20))));
    }

    @Test
    void shippedBloodpactPreconditionsAreReachable() {
        // G-106 修好后的 shipped 形态：前置技能是三转 vanished-horizon，
        // 隐藏副本/门票/技能书都在三转范围内 ⇒ 无越界前置。
        List<HiddenPathReachability.Precondition> preconditions = List.of(
                skill("vanished-horizon", 3, 20),
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
    void g106ShapeIsRejectedWheneverAPrerequisiteOutranksItsRoute() {
        // 判据不认具体路线，只认「前置晚于路线」：二转符箓遇到三转前置必须被拒，
        // 三转血契遇到四转前置（越界形态）同样必须被拒。
        List<HiddenPathReachability.Precondition> stageThreeSkill =
                List.of(skill("vanished-horizon", 3, 20));
        assertEquals(Set.of(HiddenPathReachability.Requirement.SKILL),
                HiddenPathReachability.unattainable(HiddenPath.TALISMAN, stageThreeSkill));
        Map<HiddenPathReachability.Requirement, Set<String>> details =
                HiddenPathReachability.unattainableDetails(HiddenPath.TALISMAN, stageThreeSkill);
        assertEquals(Set.of("vanished-horizon"),
                details.get(HiddenPathReachability.Requirement.SKILL));
        assertFalse(details.isEmpty());

        // 血契现在是上限转：只有越界（无更晚一转可用）才会被拒，这里用被钳到 3 的越界前置证明。
        List<HiddenPathReachability.Precondition> beyondMax =
                List.of(skill("vanished-horizon", 3, ProgressionRules.LOCAL_LEVEL_CAP));
        assertTrue(HiddenPathReachability.unattainable(HiddenPath.BLOODPACT, beyondMax).isEmpty());
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
