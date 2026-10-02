package cn.originalrpg.api.model;

import cn.originalrpg.api.rules.ProgressionRules;

import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable, configured chapter step. Database state may only move through these steps in order. */
public record ChapterDefinition(String id, int sequence, int maximumOpenAscension) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,31}");

    public ChapterDefinition {
        if (id == null) throw new IllegalArgumentException("chapter id is required");
        id = id.toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches() || sequence < 0 || sequence > 99
                || maximumOpenAscension < 0 || maximumOpenAscension > ProgressionRules.MAX_ASCENSION) {
            throw new IllegalArgumentException("invalid chapter definition");
        }
    }
}
