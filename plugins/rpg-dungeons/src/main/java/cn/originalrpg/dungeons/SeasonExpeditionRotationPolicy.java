package cn.originalrpg.dungeons;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;

/** Monday-to-Monday deterministic rotation with an 18-week target/affix cycle. */
final class SeasonExpeditionRotationPolicy {
    private static final List<String> TARGETS = List.of(
            "ancient-ruins", "sulfur-depths", "storm-vault");
    private static final List<List<SeasonExpeditionAffix>> AFFIX_PAIRS = List.of(
            List.of(SeasonExpeditionAffix.FORTIFIED, SeasonExpeditionAffix.RELENTLESS),
            List.of(SeasonExpeditionAffix.SWARMING, SeasonExpeditionAffix.BRUTAL),
            List.of(SeasonExpeditionAffix.FORTIFIED, SeasonExpeditionAffix.BRUTAL),
            List.of(SeasonExpeditionAffix.SWARMING, SeasonExpeditionAffix.RELENTLESS),
            List.of(SeasonExpeditionAffix.FORTIFIED, SeasonExpeditionAffix.SWARMING),
            List.of(SeasonExpeditionAffix.BRUTAL, SeasonExpeditionAffix.RELENTLESS));

    private final ZoneId zone;
    private final LocalDate epochMonday;
    private final Clock clock;

    SeasonExpeditionRotationPolicy(ZoneId zone, LocalDate epochMonday, Clock clock) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.epochMonday = Objects.requireNonNull(epochMonday, "epochMonday");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (epochMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("Season expedition epoch must be a Monday");
        }
    }

    SeasonExpeditionRotation current(String seasonId) {
        return at(seasonId, clock.instant());
    }

    SeasonExpeditionRotation at(String seasonId, Instant instant) {
        Objects.requireNonNull(seasonId, "seasonId");
        LocalDate monday = instant.atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long index = ChronoUnit.WEEKS.between(epochMonday, monday);
        int targetIndex = Math.floorMod(index, TARGETS.size());
        long block = Math.floorDiv(index, TARGETS.size());
        int pairIndex = Math.floorMod(targetIndex * 2L + block, AFFIX_PAIRS.size());
        Instant startsAt = monday.atStartOfDay(zone).toInstant();
        Instant endsAt = monday.plusWeeks(1).atStartOfDay(zone).toInstant();
        return new SeasonExpeditionRotation(seasonId, "week-" + monday, index,
                TARGETS.get(targetIndex), AFFIX_PAIRS.get(pairIndex), startsAt, endsAt);
    }

    boolean isActive(SeasonExpeditionRotation rotation, Instant instant) {
        if (rotation == null || instant == null) return false;
        SeasonExpeditionRotation active = at(rotation.seasonId(), instant);
        return active.rotationId().equals(rotation.rotationId())
                && active.dungeonId().equals(rotation.dungeonId())
                && active.affixes().equals(rotation.affixes());
    }

    ZoneId zone() {
        return zone;
    }
}
