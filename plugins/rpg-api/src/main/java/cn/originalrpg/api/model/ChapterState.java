package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authoritative global progression gate shared by every Paper shard. */
public record ChapterState(
        String seasonId,
        String chapterId,
        int sequence,
        int maximumOpenAscension,
        long revision,
        Instant changedAt
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,31}");

    public ChapterState {
        seasonId = normalized(seasonId, "season id");
        chapterId = normalized(chapterId, "chapter id");
        if (sequence < 0 || sequence > 99 || maximumOpenAscension < 0
                || maximumOpenAscension > 3 || revision < 0) {
            throw new IllegalArgumentException("invalid chapter state");
        }
        Objects.requireNonNull(changedAt, "changedAt");
    }

    private static String normalized(String value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!ID.matcher(normalized).matches()) throw new IllegalArgumentException("invalid " + label);
        return normalized;
    }
}
