package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeasonExpeditionRotationPolicyTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate EPOCH = LocalDate.parse("2026-08-03");

    @Test
    void mondayMidnightIsTheOnlyWeeklyResetBoundary() {
        SeasonExpeditionRotationPolicy policy = policy("2026-08-09T15:59:59Z");
        SeasonExpeditionRotation before = policy.current("launch-1");
        assertEquals("week-2026-08-03", before.rotationId());
        assertTrue(policy.isActive(before, Instant.parse("2026-08-09T15:59:59Z")));
        assertFalse(policy.isActive(before, Instant.parse("2026-08-09T16:00:00Z")));

        SeasonExpeditionRotation after = policy.at("launch-1", Instant.parse("2026-08-09T16:00:00Z"));
        assertEquals("week-2026-08-10", after.rotationId());
        assertEquals(before.endsAt(), after.startsAt());
    }

    @Test
    void eighteenWeeksCoverEveryDungeonAndAffixPairBeforeRepeating() {
        SeasonExpeditionRotationPolicy policy = policy("2026-08-03T00:00:00Z");
        Set<String> combinations = new HashSet<>();
        for (int week = 0; week < 18; week++) {
            Instant instant = EPOCH.plusWeeks(week).atStartOfDay(SHANGHAI).toInstant();
            SeasonExpeditionRotation rotation = policy.at("launch-1", instant);
            combinations.add(rotation.dungeonId() + ":" + rotation.affixes());
        }
        assertEquals(18, combinations.size());
        SeasonExpeditionRotation first = policy.at("launch-1", EPOCH.atStartOfDay(SHANGHAI).toInstant());
        SeasonExpeditionRotation repeated = policy.at("launch-1",
                EPOCH.plusWeeks(18).atStartOfDay(SHANGHAI).toInstant());
        assertEquals(first.dungeonId(), repeated.dungeonId());
        assertEquals(first.affixes(), repeated.affixes());
    }

    @Test
    void epochMustBeMonday() {
        assertThrows(IllegalArgumentException.class, () -> new SeasonExpeditionRotationPolicy(
                SHANGHAI, LocalDate.parse("2026-08-04"), Clock.systemUTC()));
    }

    private static SeasonExpeditionRotationPolicy policy(String instant) {
        return new SeasonExpeditionRotationPolicy(SHANGHAI, EPOCH,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
