package cn.originalrpg.integrations;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.WealthBalance;
import cn.originalrpg.api.rules.ProgressionRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** G-111：成长占位符必须落在 ProgressionRules 的口径上，且不含零头的累计语义要钉住。 */
class ProgressionPlaceholdersTest {
    private static RpgSnapshot snapshot(int ascension, int localLevel, long experience) {
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), Archetype.WARRIOR,
                ascension, localLevel, experience, 100, 100, 0, 0,
                Map.of(CoreAttribute.STRENGTH, 0));
        return RpgSnapshot.from(profile, new WealthBalance(new BigDecimal("1"), 2L, 3L),
                Map.of(ascension, "branch"), Instant.EPOCH);
    }

    @Test
    void effectiveLevelAndCapComeFromProgressionRules() {
        RpgSnapshot snapshot = snapshot(2, 17, 1234L);
        assertEquals(Integer.toString(ProgressionRules.effectiveLevel(2, 17)),
                ProgressionPlaceholders.value(snapshot, "effective_level"));
        assertEquals(Integer.toString(ProgressionRules.effectiveLevelCap()),
                ProgressionPlaceholders.value(snapshot, "effective_level_cap"));
        assertEquals("77", ProgressionPlaceholders.value(snapshot, "effective_level"));
        assertEquals("120", ProgressionPlaceholders.value(snapshot, "effective_level_cap"));
    }

    @Test
    void stageBudgetAndSpentTrackProgressionRules() {
        RpgSnapshot snapshot = snapshot(1, 5, 777L);
        assertEquals(Long.toString(ProgressionRules.totalExperienceForAscension(1)),
                ProgressionPlaceholders.value(snapshot, "experience_stage_budget"));
        assertEquals(Long.toString(ProgressionRules.stageExperienceSpent(1, 5, 777L)),
                ProgressionPlaceholders.value(snapshot, "experience_stage_spent"));
        assertEquals(Long.toString(ProgressionRules.stageExperienceRemaining(1, 5, 777L)),
                ProgressionPlaceholders.value(snapshot, "experience_stage_remaining"));
    }

    @Test
    void lifetimeExperienceBoundaries() {
        // 0 经验、起点等级
        RpgSnapshot fresh = snapshot(0, 1, 0L);
        assertEquals("0", ProgressionPlaceholders.value(fresh, "experience_total"));
        assertEquals("0", ProgressionPlaceholders.value(fresh, "experience_stage_spent"));

        // 刚好到上限：累计 = 全阶段实走和，剩余 0
        RpgSnapshot capped = snapshot(0, 30, 0L);
        long walked = ProgressionRules.totalExperienceToEffective(30);
        assertEquals(Long.toString(walked), ProgressionPlaceholders.value(capped, "experience_total"));
        assertEquals("0", ProgressionPlaceholders.value(capped, "experience_stage_remaining"));

        // 超过上限（等级已满仍有零头）：已投入继续增长，累计阈值不变（口径不含零头）
        RpgSnapshot overflow = snapshot(0, 30, 900_000L);
        assertEquals(Long.toString(walked + 900_000L),
                ProgressionPlaceholders.value(overflow, "experience_stage_spent"));
        assertEquals(Long.toString(walked), ProgressionPlaceholders.value(overflow, "experience_total"));

        // 终态有效等级上限的累计经验
        RpgSnapshot terminal = snapshot(ProgressionRules.MAX_ASCENSION, ProgressionRules.LOCAL_LEVEL_CAP, 0L);
        assertEquals(Long.toString(ProgressionRules.totalExperienceToEffective(
                        ProgressionRules.effectiveLevelCap())),
                ProgressionPlaceholders.value(terminal, "experience_total"));
    }

    @Test
    void unknownParameterFallsBackToNull() {
        assertNull(ProgressionPlaceholders.value(snapshot(0, 1, 0L), "not_a_placeholder"));
    }
}
