package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WaveSkillRulesTest {
    @Test
    void exposesSixDistinctReadableWaveSkills() {
        assertEquals(Set.of("ruin-lunge", "echo-seal", "fume-vent", "slag-mortar", "static-link", "gust-flank"),
                WaveSkillRules.keys());
        for (String key : WaveSkillRules.keys()) {
            WaveSkillRules.Skill skill = WaveSkillRules.find(key).orElseThrow();
            assertTrue(skill.warningTicks() >= 10 && skill.warningTicks() <= 40, key);
            assertTrue(skill.intervalTicks() >= 100L, key);
        }
        assertTrue(WaveSkillRules.find("unknown").isEmpty());
    }
}
