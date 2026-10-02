package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossSkillRulesTest {
    @Test
    void everyDungeonOwnsThreeNamedSkills() {
        for (String id : Set.of("ancient-ruins", "ancient-ruins-hidden", "sulfur-depths",
                "sulfur-depths-hidden", "storm-vault", "storm-vault-hidden")) {
            EnumSet<BossSkillRules.Skill> skills = EnumSet.noneOf(BossSkillRules.Skill.class);
            for (int sequence = 0; sequence < 6; sequence++) {
                skills.add(BossSkillRules.select(id, sequence, 0).skill());
            }
            assertEquals(3, skills.size(), id);
        }
    }

    @Test
    void familyMechanicsKeepTheirOwnShapes() {
        assertEquals(BossAttackRules.Shape.RUIN_DIAGONALS,
                BossSkillRules.select("ancient-ruins", 0, 0).pattern().shape());
        assertEquals(BossAttackRules.Shape.SULFUR_VENTS,
                BossSkillRules.select("sulfur-depths", 0, 0).pattern().shape());
        assertEquals(BossAttackRules.Shape.STORM_SWEEP,
                BossSkillRules.select("storm-vault", 0, 0).pattern().shape());
        assertTrue(BossSkillRules.recordsPositions(BossSkillRules.Skill.ECHO_REPLAY));
        assertTrue(BossSkillRules.leavesHazard(BossSkillRules.Skill.SULFUR_VENTS));
        assertTrue(BossSkillRules.chains(BossSkillRules.Skill.STATIC_CHAIN));
    }
}
