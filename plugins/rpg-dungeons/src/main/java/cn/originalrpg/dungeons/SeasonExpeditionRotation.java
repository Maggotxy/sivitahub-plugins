package cn.originalrpg.dungeons;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

record SeasonExpeditionRotation(
        String seasonId,
        String rotationId,
        long rotationIndex,
        String dungeonId,
        List<SeasonExpeditionAffix> affixes,
        Instant startsAt,
        Instant endsAt
) {
    SeasonExpeditionRotation {
        Objects.requireNonNull(seasonId, "seasonId");
        Objects.requireNonNull(rotationId, "rotationId");
        Objects.requireNonNull(dungeonId, "dungeonId");
        affixes = List.copyOf(Objects.requireNonNull(affixes, "affixes"));
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (affixes.size() != 2 || affixes.get(0) == affixes.get(1) || !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("A season rotation needs two distinct affixes and a valid window");
        }
    }
}
