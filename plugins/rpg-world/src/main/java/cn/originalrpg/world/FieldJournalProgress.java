package cn.originalrpg.world;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Immutable UI projection; no mutable advancement progress is stored or sent to JDBC. */
record FieldJournalProgress(
        boolean available, boolean completed, int totalCriteria, List<String> remaining, Instant recordedAt
) {
    FieldJournalProgress {
        remaining = List.copyOf(remaining);
    }

    static FieldJournalProgress unavailable(Instant recordedAt) {
        return new FieldJournalProgress(false, false, 0, List.of(), recordedAt);
    }

    static FieldJournalProgress observe(
            Collection<String> criteria, Collection<String> awarded, boolean completed, Instant recordedAt
    ) {
        Set<String> known = Set.copyOf(criteria);
        Set<String> achieved = Set.copyOf(awarded);
        if (known.isEmpty() || !known.containsAll(achieved)) return unavailable(recordedAt);
        List<String> remaining = known.stream().filter(criterion -> !achieved.contains(criterion)).sorted().toList();
        return new FieldJournalProgress(true, completed, known.size(), remaining, recordedAt);
    }

    int awardedCriteria() {
        return totalCriteria - remaining.size();
    }

    String stateKey() {
        if (recordedAt != null) return "journal.recorded";
        if (!available) return "journal.unavailable";
        return completed ? "journal.pending" : "journal.in-progress";
    }
}
