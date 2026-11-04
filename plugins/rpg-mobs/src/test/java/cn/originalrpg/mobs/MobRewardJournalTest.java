package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.originalrpg.mobs.MobRewardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

final class MobRewardJournalTest {
    @TempDir Path directory;

    @Test
    void preservesFrozenItemsRollsAndTheOneWayEligibilityDecision() throws Exception {
        MobRewardIntent candidate = unknown(reward(UUID.randomUUID(), 240L, 2));
        try (MobRewardJournal journal = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            assertEquals(candidate, journal.persist(candidate));
            assertEquals(List.of(candidate), journal.load());
            MobRewardIntent resolved = candidate.resolve(10);
            assertEquals(resolved, journal.persist(resolved));
            assertEquals(resolved, journal.persist(candidate), "Late shutdown retry must not downgrade a resolved decision");
            assertEquals(List.of(resolved), journal.load());
            assertEquals(candidate.experienceGrantId(), resolved.experienceGrantId());
            assertEquals(candidate.pityRolls(), resolved.pityRolls());
            assertEquals(candidate.deliveries(), resolved.deliveries());
        }
    }

    @Test
    void rejectsIdentityReuseAndConflictingResolvedQualification() throws Exception {
        MobRewardIntent original = reward(UUID.randomUUID(), 100L, 2);
        try (MobRewardJournal journal = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            journal.persist(original);
            MobRewardIntent changedBytes = withPayload(original, 12);
            assertThrows(IOException.class, () -> journal.persist(changedBytes));
            MobRewardIntent differentLevel = unknown(original).resolve(15);
            assertThrows(IOException.class, () -> journal.persist(differentLevel));
            assertEquals(List.of(original), journal.load());
        }
    }

    @Test
    void checksumTruncationUnknownVersionAndFilenameMismatchFailClosedWithoutDeletingEvidence() throws Exception {
        for (int fault = 0; fault < 4; fault++) {
            Path folder = directory.resolve("fault-" + fault);
            MobRewardIntent original = reward(UUID.randomUUID(), 10L, 1);
            try (MobRewardJournal journal = new MobRewardJournal(folder, 8, BYTE_LIMIT)) { journal.persist(original); }
            Path file = folder.resolve(original.killId() + ".mob-reward");
            byte[] bytes = Files.readAllBytes(file);
            switch (fault) {
                case 0 -> { bytes[20] ^= 1; Files.write(file, bytes); }
                case 1 -> Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
                case 2 -> { bytes[7] = 2; Files.write(file, bytes); }
                case 3 -> file = Files.move(file, folder.resolve(UUID.randomUUID() + ".mob-reward"));
                default -> throw new AssertionError();
            }
            try (MobRewardJournal journal = new MobRewardJournal(folder, 8, BYTE_LIMIT)) {
                assertThrows(IOException.class, journal::load);
                assertTrue(Files.exists(file), "Corrupt/unknown entries require operator review, never silent discard");
            }
        }
    }

    @Test
    void enforcesRecordAndByteBudgetsAndReleasesCapacityOnlyAfterConfirmedRemoval() throws Exception {
        MobRewardIntent first = reward(UUID.randomUUID(), 10L, 1);
        MobRewardIntent second = reward(UUID.randomUUID(), 10L, 1);
        try (MobRewardJournal journal = new MobRewardJournal(directory.resolve("count"), 1, BYTE_LIMIT)) {
            journal.persist(first);
            assertThrows(IOException.class, () -> journal.persist(second));
            journal.remove(first);
            journal.persist(second);
            assertEquals(List.of(second), journal.load());
        }
        MobRewardIntent large = withPayload(reward(UUID.randomUUID(), 10L, 2), 90_000);
        try (MobRewardJournal journal = new MobRewardJournal(directory.resolve("bytes"), 8, MobRewardJournal.MAX_RECORD_BYTES)) {
            journal.persist(large);
            assertThrows(IOException.class, () -> journal.persist(withPayload(second, 30_000)));
            assertEquals(List.of(large), journal.load());
        }
    }

    @Test
    void aSecondWriterCannotOpenTheSameDirectoryUntilTheFirstCloses() throws Exception {
        MobRewardJournal first = new MobRewardJournal(directory, 8, BYTE_LIMIT);
        try (MobRewardJournal second = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            first.load();
            assertThrows(IOException.class, second::load);
            first.close();
            assertEquals(List.of(), second.load());
        } finally { first.close(); }
    }

    @Test
    void ordinaryWriteFailureBeforeRenameDoesNotCreateAnAcceptedIntent() throws Exception {
        MobRewardIntent original = reward(UUID.randomUUID(), 10L, 1);
        try (MobRewardJournal journal = new MobRewardJournal(directory, 8, BYTE_LIMIT, (step, ignored) -> {
            if (step == MobRewardJournal.Step.FILE_FORCED) throw new IOException("Injected failure before rename");
        })) {
            assertThrows(IOException.class, () -> journal.persist(original));
            assertEquals(List.of(), journal.load());
            assertFalse(Files.exists(directory.resolve(original.killId() + ".staging")));
        }
    }

    @Test
    void aRenameWithLostFsyncAcknowledgementRetriesTheExactSameIntent() throws Exception {
        MobRewardIntent original = reward(UUID.randomUUID(), 100L, 2);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        try (MobRewardJournal journal = new MobRewardJournal(directory, 1, BYTE_LIMIT, (step, ignored) -> {
            if (step == MobRewardJournal.Step.RENAMED && failOnce.getAndSet(false)) throw new IOException("Lost directory fsync acknowledgement");
        })) {
            assertThrows(IOException.class, () -> journal.persist(original));
            assertTrue(Files.exists(directory.resolve(original.killId() + ".mob-reward")));
            assertEquals(original, journal.persist(original));
            assertThrows(IOException.class, () -> journal.persist(reward(UUID.randomUUID(), 10L, 0)));
            assertEquals(List.of(original), journal.load());
        }
    }

    @Test
    void deletionFailureDoesNotReleaseAdmissionUntilTheDirectorySyncIsRetried() throws Exception {
        MobRewardIntent original = reward(UUID.randomUUID(), 10L, 1);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        try (MobRewardJournal journal = new MobRewardJournal(directory, 1, BYTE_LIMIT, (step, ignored) -> {
            if (step == MobRewardJournal.Step.DELETED && failOnce.getAndSet(false)) throw new IOException("Delete acknowledgement lost");
        })) {
            journal.persist(original);
            assertThrows(IOException.class, () -> journal.remove(original));
            MobRewardIntent next = reward(UUID.randomUUID(), 10L, 0);
            assertThrows(IOException.class, () -> journal.persist(next), "Unconfirmed cleanup must not free the journal budget");
            journal.remove(original);
            journal.persist(next);
            assertEquals(List.of(next), journal.load());
        }
    }

    @Test
    void abandonedStagingIsBoundedRetainedAndNeverReplayed() throws Exception {
        Path stage = directory.resolve(UUID.randomUUID() + ".staging");
        Files.write(stage, new byte[]{1, 2, 3});
        try (MobRewardJournal journal = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            assertEquals(List.of(), journal.load());
            assertEquals(1, journal.orphanedStagingFiles());
            assertTrue(Files.exists(stage));
        }
    }

    @Test
    void reducedRecoveryLimitsDoNotDiscardTheOldBacklog() throws Exception {
        MobRewardIntent first = reward(UUID.randomUUID(), 10L, 1);
        MobRewardIntent second = reward(UUID.randomUUID(), 20L, 0);
        try (MobRewardJournal journal = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            journal.persist(first); journal.persist(second);
        }
        try (MobRewardJournal tooSmall = new MobRewardJournal(directory, 1, BYTE_LIMIT)) {
            assertThrows(IOException.class, tooSmall::load);
        }
        try (MobRewardJournal restored = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            assertEquals(2, restored.load().size());
        }
    }
}
