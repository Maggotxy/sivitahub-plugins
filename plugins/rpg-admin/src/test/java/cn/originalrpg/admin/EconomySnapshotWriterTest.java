package cn.originalrpg.admin;

import cn.originalrpg.api.model.EconomyFlow;
import cn.originalrpg.api.model.EconomyReasonTotal;
import cn.originalrpg.api.model.EconomyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySnapshotWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsCompleteEscapedSnapshotWithoutNonFiniteNumbers() {
        EconomyFlow day = new EconomyFlow(1, money("100"), money("70"), money("5"), money("240"),
                4, 3, 2, 1);
        EconomyReport report = new EconomyReport(LocalDate.of(2026, 8, 7), 10, money("1000"), 20, 5,
                money("800"), money("20"), money("100"), money("500"), ratio("0.4000"), ratio("0.2500"),
                3, 2, 1, money("12"), money("48"), money("120"), 9, 2, day,
                new EconomyFlow(7, money("700"), money("500"), money("25"), money("1400"), 28, 21, 14, 7),
                new EconomyFlow(30, money("3000"), money("2300"), money("100"), money("6000"), 120, 90, 60, 30),
                List.of(new EconomyReasonTotal("quest\"reward", money("600"))),
                List.of(new EconomyReasonTotal("market-fees", money("25"))));

        String json = EconomySnapshotWriter.json(report, Instant.parse("2026-08-07T12:00:00Z"));

        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"activeListings\":3"));
        assertTrue(json.contains("\"p90ActiveListingAgeHours\":\"48.00\""));
        assertTrue(json.contains("\"soldListings7d\":9"));
        assertTrue(json.contains("quest\\\"reward"));
        assertTrue(json.contains("\"sinkRatio\":\"0.75\""));
        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("Infinity"));
    }

    @Test
    void persistsConsecutiveDailyBaselinesAndComputesWeekOverWeekOnlyAfterSevenDays() throws Exception {
        Instant sampledAt = Instant.parse("2026-08-08T12:00:00Z");
        EconomyWeekComparison comparison = null;
        for (int day = 1; day <= 7; day++) {
            comparison = EconomyHistoryStore.update(temporaryDirectory,
                    report(LocalDate.of(2026, 8, day), "1000.00", "800.00"), sampledAt);
            assertEquals(EconomyWeekComparison.Status.INSUFFICIENT_HISTORY, comparison.status());
        }

        EconomyReport current = report(LocalDate.of(2026, 8, 8), "1100.00", "880.00");
        comparison = EconomyHistoryStore.update(temporaryDirectory, current, sampledAt);

        assertEquals(EconomyWeekComparison.Status.AVAILABLE, comparison.status());
        assertEquals(7, comparison.historicalDays());
        assertEquals(LocalDate.of(2026, 8, 1), comparison.baselineDate());
        assertEquals(new BigDecimal("80.00"), comparison.activeGoldDelta());
        assertEquals(new BigDecimal("0.1000"), comparison.activeGoldChangeRatio());
        assertEquals(new BigDecimal("100.00"), comparison.totalGoldDelta());
        assertEquals(new BigDecimal("0.1000"), comparison.totalGoldChangeRatio());
        assertEquals(8L, historyFileCount(temporaryDirectory));

        EconomyWeekComparison replacement = EconomyHistoryStore.update(temporaryDirectory,
                report(LocalDate.of(2026, 8, 8), "1200.00", "960.00"), sampledAt.plusSeconds(60));
        assertEquals(new BigDecimal("0.2000"), replacement.activeGoldChangeRatio());
        assertEquals(8L, historyFileCount(temporaryDirectory));
        String json = EconomySnapshotWriter.json(current, sampledAt, comparison);
        assertTrue(json.contains("\"weekOverWeek\":{"));
        assertTrue(json.contains("\"status\":\"AVAILABLE\""));
        assertTrue(json.contains("\"activeGoldChangeRatio\":\"0.1000\""));
    }

    @Test
    void missingOrZeroBaselineNeverProducesAFalseGrowthPercentage() throws Exception {
        Path missing = temporaryDirectory.resolve("missing");
        Instant sampledAt = Instant.parse("2026-08-08T12:00:00Z");
        for (int day = 1; day <= 7; day++) {
            if (day != 4) EconomyHistoryStore.update(missing,
                    report(LocalDate.of(2026, 8, day), "1000.00", "800.00"), sampledAt);
        }
        EconomyWeekComparison incomplete = EconomyHistoryStore.update(missing,
                report(LocalDate.of(2026, 8, 8), "1100.00", "880.00"), sampledAt);
        assertEquals(EconomyWeekComparison.Status.INSUFFICIENT_HISTORY, incomplete.status());
        assertEquals(6, incomplete.historicalDays());

        Path zero = temporaryDirectory.resolve("zero");
        for (int day = 1; day <= 7; day++) EconomyHistoryStore.update(zero,
                report(LocalDate.of(2026, 8, day), "0.00", "0.00"), sampledAt);
        EconomyWeekComparison zeroBaseline = EconomyHistoryStore.update(zero,
                report(LocalDate.of(2026, 8, 8), "100.00", "80.00"), sampledAt);
        assertEquals(EconomyWeekComparison.Status.ZERO_BASELINE, zeroBaseline.status());
        assertFalse(zeroBaseline.available());
    }

    private static EconomyReport report(LocalDate date, String totalGold, String activeGold) {
        EconomyFlow day = new EconomyFlow(1, money("100"), money("70"), money("5"), money("240"),
                4, 3, 2, 1);
        return new EconomyReport(date, 10, money(totalGold), 20, 5,
                money(activeGold), money("20"), money("100"), money("500"), ratio("0.4000"), ratio("0.2500"),
                3, 2, 1, money("12"), money("48"), money("120"), 9, 2, day,
                new EconomyFlow(7, money("700"), money("500"), money("25"), money("1400"), 28, 21, 14, 7),
                new EconomyFlow(30, money("3000"), money("2300"), money("100"), money("6000"), 120, 90, 60, 30),
                List.of(), List.of());
    }

    private static long historyFileCount(Path folder) throws java.io.IOException {
        try (var files = Files.list(folder.resolve("economy-history"))) {
            return files.count();
        }
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
    private static BigDecimal ratio(String value) { return new BigDecimal(value).setScale(4); }
}
