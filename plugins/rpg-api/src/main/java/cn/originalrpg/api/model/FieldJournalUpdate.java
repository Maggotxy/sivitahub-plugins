package cn.originalrpg.api.model;

import java.util.List;
import java.util.Set;

/** Current permanent stamps plus the subset first recorded by this transaction. */
public record FieldJournalUpdate(List<FieldJournalRecord> records, Set<FieldJournalEntry> newlyRecorded) {
    public FieldJournalUpdate {
        records = List.copyOf(records);
        newlyRecorded = Set.copyOf(newlyRecorded);
        Set<FieldJournalEntry> present = records.stream().map(FieldJournalRecord::entry)
                .collect(java.util.stream.Collectors.toSet());
        if (present.size() != records.size() || !present.containsAll(newlyRecorded)) {
            throw new IllegalArgumentException("Journal update contains duplicate or missing receipts");
        }
    }
}
