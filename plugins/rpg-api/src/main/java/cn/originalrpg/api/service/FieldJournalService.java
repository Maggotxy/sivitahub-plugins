package cn.originalrpg.api.service;

import cn.originalrpg.api.model.FieldJournalEntry;
import cn.originalrpg.api.model.FieldJournalRecord;
import cn.originalrpg.api.model.FieldJournalUpdate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Durable, display-only discovery receipts. Implementations execute database
 * work on a bounded worker executor and never access Bukkit objects.
 */
public interface FieldJournalService {
    /** Returns the player's permanent journal stamps without changing any progression state. */
    CompletionStage<List<FieldJournalRecord>> records(UUID playerId);

    /**
     * Records a bounded set of independently verified completion facts.
     *
     * <p>The caller must inspect the live advancement registry and the player's
     * completed advancement on the server thread before submitting an immutable
     * set. This method is for trusted completion/recovery adapters, not direct
     * player-supplied IDs. The pair {@code (playerId, entry)} is unique forever;
     * repeated events, reconnect reconciliation and concurrent nodes cannot
     * create extra stamps or alter their first-registration time.
     *
     * <p>These are historical facts, so an accepted write may finish after the
     * connection closes. UI callbacks must separately validate their connection.
     * An empty set performs a read-only refresh. No reward, wallet, attribute or
     * vanilla-advancement state is modified.
     */
    CompletionStage<FieldJournalUpdate> recordCompleted(UUID playerId, Set<FieldJournalEntry> completed);
}
