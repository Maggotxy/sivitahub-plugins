package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;

/** An immutable first-registration receipt, not a second advancement-progress counter. */
public record FieldJournalRecord(FieldJournalEntry entry, Instant recordedAt) {
    public FieldJournalRecord {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
