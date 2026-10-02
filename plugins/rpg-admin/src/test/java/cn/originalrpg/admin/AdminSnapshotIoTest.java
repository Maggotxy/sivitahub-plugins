package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.EconomyFlow;
import cn.originalrpg.api.model.EconomyReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdminSnapshotIoTest {
    @Test
    void fileOperationsRunOnlyOnTheDedicatedSerializedWorker() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch written = new CountDownLatch(2);
        AdminSnapshotIo io = new AdminSnapshotIo(new StubStore() {
            @Override
            public AdminSnapshotIo.EconomyExport writeEconomy(EconomyReport report) {
                observeWorker(active, maximum, written);
                return new AdminSnapshotIo.EconomyExport(null, null);
            }

            @Override
            public void writeChapter(ChapterState state) {
                observeWorker(active, maximum, written);
            }
        }, 2);

        io.economy(report());
        io.chapter(chapter());

        assertTrue(written.await(2, TimeUnit.SECONDS));
        assertTrue(io.close(Duration.ofSeconds(2)));
        assertTrue(maximum.get() == 1, "snapshot file operations must never overlap");
    }

    @Test
    void closeRejectsNewWorkAndCancelsQueuedWorkAfterBoundedDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AdminSnapshotIo io = new AdminSnapshotIo(new StubStore() {
            @Override
            public void writeChapter(ChapterState state) throws IOException {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
            }
        }, 2);

        var running = io.chapter(chapter());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var queued = io.economy(report());
        assertFalse(io.close(Duration.ofMillis(20)));
        release.countDown();

        assertTrue(queued.isCompletedExceptionally());
        assertTrue(io.chapter(chapter()).isCompletedExceptionally());
        try {
            running.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Interruption is the required bounded-close behavior.
        }
    }

    private static void observeWorker(AtomicInteger active, AtomicInteger maximum, CountDownLatch written) {
        assertTrue(Thread.currentThread().getName().equals("rpg-admin-snapshot-io"));
        int now = active.incrementAndGet();
        maximum.accumulateAndGet(now, Math::max);
        active.decrementAndGet();
        written.countDown();
    }

    private static ChapterState chapter() {
        return new ChapterState("season_1", "chapter_1", 1, 1, 1L, Instant.EPOCH);
    }

    private static EconomyReport report() {
        EconomyFlow day = new EconomyFlow(1, money("100"), money("70"), money("5"), money("240"),
                4, 3, 2, 1);
        return new EconomyReport(LocalDate.of(2026, 8, 13), 10, money("1000"), 20, 5,
                money("800"), money("20"), money("100"), money("500"),
                new BigDecimal("0.4000"), new BigDecimal("0.2500"),
                3, 2, 1, money("12"), money("48"), money("120"), 9, 2, day,
                new EconomyFlow(7, money("700"), money("500"), money("25"), money("1400"),
                        28, 21, 14, 7),
                new EconomyFlow(30, money("3000"), money("2300"), money("100"), money("6000"),
                        120, 90, 60, 30),
                List.of(), List.of());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static class StubStore implements AdminSnapshotIo.Store {
        @Override
        public AdminSnapshotIo.EconomyExport writeEconomy(EconomyReport report) throws IOException {
            return new AdminSnapshotIo.EconomyExport(null, null);
        }

        @Override
        public void writeChapter(ChapterState state) throws IOException {
        }
    }
}
