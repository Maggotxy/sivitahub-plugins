package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SkillSessionStateTest {
    @Test
    void onlyFutureConfiguredCooldownsSurviveRoundTrip() {
        long now = 1_000L;
        Map<String, Long> source = new LinkedHashMap<>();
        source.put("fireball", 9_000L);
        source.put("expired", 500L);
        source.put("frost-nova", 4_000L);

        String encoded = SkillSessionState.encodeCooldowns(source, now);
        assertEquals("fireball:9000,frost-nova:4000", encoded);
        assertEquals(Map.of("fireball", 9_000L), SkillSessionState.decodeCooldowns(
                encoded + ",unknown:8000,broken,nope:x", Set.of("fireball"), now));
    }

    @Test
    void manaCannotBeRestoredOutsideTheCurrentAttributeCap() {
        assertEquals(120, SkillSessionState.restoreMana(null, 120));
        assertEquals(0, SkillSessionState.restoreMana(-5, 120));
        assertEquals(120, SkillSessionState.restoreMana(999, 120));
        assertEquals(42, SkillSessionState.restoreMana(42, 120));
    }
}
