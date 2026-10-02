package cn.originalrpg.dungeons;

import cn.originalrpg.api.model.DungeonRewardDelivery;
import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.DungeonTicketCandidate;
import cn.originalrpg.api.model.SeasonExpeditionCompletion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonRewardJournalTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsLargePayloadAndDeletesOnlyAfterAcknowledgement() throws Exception {
        DungeonRewardJournal journal = new DungeonRewardJournal(temporary.resolve("outbox"));
        String payload = "x".repeat(150_000);
        DungeonRewardDelivery material = new DungeonRewardDelivery(
                UUID.randomUUID(), payload, "dungeon:storm-vault:material");
        DungeonRewardDelivery ticket = new DungeonRewardDelivery(
                UUID.randomUUID(), "ticket", "dungeon:storm-vault:hidden-ticket");
        DungeonRewardRequest request = new DungeonRewardRequest(
                UUID.randomUUID(), "storm-vault", UUID.randomUUID(), 700L,
                List.of(material), Optional.of(new DungeonTicketCandidate(
                ticket, "storm-vault-hidden-ticket", 0.2, 5)));

        journal.persist(request);
        journal.persist(request);
        assertEquals(List.of(request), journal.loadAll());
        journal.remove(request);
        assertTrue(journal.loadAll().isEmpty());
    }

    @Test
    void sameRunAndPlayerCannotOverwriteDifferentIntent() throws Exception {
        DungeonRewardJournal journal = new DungeonRewardJournal(temporary.resolve("outbox"));
        UUID run = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        DungeonRewardRequest first = new DungeonRewardRequest(
                run, "ancient-ruins", player, 100L,
                List.of(new DungeonRewardDelivery(UUID.randomUUID(), "one", "source:one")),
                Optional.empty());
        DungeonRewardRequest changed = new DungeonRewardRequest(
                run, "ancient-ruins", player, 101L,
                List.of(new DungeonRewardDelivery(UUID.randomUUID(), "two", "source:two")),
                Optional.empty());

        journal.persist(first);
        assertThrows(java.io.IOException.class, () -> journal.persist(changed));
        assertEquals(List.of(first), journal.loadAll());
    }

    @Test
    void atomicallyRoundTripsOneAllPartyBatch() throws Exception {
        DungeonRewardJournal journal = new DungeonRewardJournal(temporary.resolve("batch-outbox"));
        UUID run = UUID.randomUUID();
        DungeonRewardRequest first = request(run, UUID.randomUUID(), "first");
        DungeonRewardRequest second = request(run, UUID.randomUUID(), "second");
        DungeonRewardBatch batch = new DungeonRewardBatch(run, List.of(first, second));

        journal.persist(batch);
        journal.persist(batch);

        assertEquals(List.of(batch), journal.load());
        assertTrue(journal.loadAll().isEmpty(), "new batches must not expose partial per-player journals");
        journal.remove(batch);
        assertTrue(journal.load().isEmpty());
    }

    @Test
    void sameRunCannotOverwriteDifferentPartyBatch() throws Exception {
        DungeonRewardJournal journal = new DungeonRewardJournal(temporary.resolve("conflicting-batch-outbox"));
        UUID run = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        DungeonRewardBatch original = new DungeonRewardBatch(run,
                List.of(request(run, firstPlayer, "first")));
        DungeonRewardBatch changed = new DungeonRewardBatch(run,
                List.of(request(run, firstPlayer, "first"), request(run, UUID.randomUUID(), "second")));

        journal.persist(original);

        assertThrows(java.io.IOException.class, () -> journal.persist(changed));
        assertEquals(List.of(original), journal.load());
    }

    @Test
    void roundTripsSeasonCompletionWithTheSameCrashRecoveryBatch() throws Exception {
        DungeonRewardJournal journal = new DungeonRewardJournal(temporary.resolve("season-batch-outbox"));
        UUID run = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        DungeonRewardRequest request = request(run, player, "season");
        SeasonExpeditionCompletion completion = new SeasonExpeditionCompletion(
                run, "launch-1", "week-2", "ancient-ruins", 1, 61_250L,
                List.of(player), Instant.parse("2026-08-13T00:00:00Z"));
        DungeonRewardBatch batch = new DungeonRewardBatch(
                run, List.of(request), Optional.of(completion));

        journal.persist(batch);

        assertEquals(List.of(batch), journal.load());
    }

    @Test
    void seasonCompletionCannotDescribeAnotherRunOrParty() {
        UUID run = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        DungeonRewardRequest request = request(run, player, "season");
        SeasonExpeditionCompletion wrongRun = new SeasonExpeditionCompletion(
                UUID.randomUUID(), "launch-1", "week-2", "ancient-ruins", 1,
                61_250L, List.of(player), Instant.parse("2026-08-13T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> new DungeonRewardBatch(
                run, List.of(request), Optional.of(wrongRun)));
    }

    private static DungeonRewardRequest request(UUID run, UUID player, String payload) {
        return new DungeonRewardRequest(run, "ancient-ruins", player, 100L,
                List.of(new DungeonRewardDelivery(UUID.randomUUID(), payload, "source:" + payload)),
                Optional.empty());
    }
}
