package cn.originalrpg.admin;

import cn.originalrpg.api.model.EconomyReport;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Keeps one atomic end-of-latest-sample record per calendar day. A week-over-
 * week result is considered usable only when every one of the preceding seven
 * dates exists; a convenient but incomplete pair of screenshots is not enough
 * evidence for automatic economic tuning.
 */
final class EconomyHistoryStore {
    private static final Pattern DATE = Pattern.compile("\\\"reportDate\\\":\\\"(\\d{4}-\\d{2}-\\d{2})\\\"");
    private static final Pattern TOTAL = Pattern.compile("\\\"totalGold\\\":\\\"(\\d+(?:\\.\\d{1,2})?)\\\"");
    private static final Pattern ACTIVE = Pattern.compile("\\\"activeGold30d\\\":\\\"(\\d+(?:\\.\\d{1,2})?)\\\"");

    private EconomyHistoryStore() {}

    static EconomyWeekComparison update(Path dataFolder, EconomyReport report, Instant sampledAt) throws IOException {
        return update(dataFolder, report, sampledAt, () -> true);
    }

    static EconomyWeekComparison update(
            Path dataFolder, EconomyReport report, Instant sampledAt, BooleanSupplier publishAllowed
    ) throws IOException {
        Objects.requireNonNull(publishAllowed, "publishAllowed");
        requirePublication(publishAllowed);
        Path history = dataFolder.resolve("economy-history");
        Files.createDirectories(history);
        writeAtomically(history.resolve(report.date() + ".json"),
                EconomySnapshotWriter.json(report, sampledAt), publishAllowed);

        List<DailyPoint> points = new ArrayList<>(7);
        for (int daysAgo = 7; daysAgo >= 1; daysAgo--) {
            requirePublication(publishAllowed);
            LocalDate date = report.date().minusDays(daysAgo);
            DailyPoint point = read(history.resolve(date + ".json"), date);
            if (point != null) points.add(point);
        }
        LocalDate baselineDate = report.date().minusDays(7);
        if (points.size() != 7) return unavailable(report, baselineDate, points.size(),
                EconomyWeekComparison.Status.INSUFFICIENT_HISTORY);
        DailyPoint baseline = points.getFirst();
        if (baseline.activeGold().signum() == 0) return unavailable(report, baselineDate, 7,
                EconomyWeekComparison.Status.ZERO_BASELINE);

        BigDecimal activeDelta = money(report.activeGold30d().subtract(baseline.activeGold()));
        BigDecimal totalDelta = money(report.totalGold().subtract(baseline.totalGold()));
        return new EconomyWeekComparison(EconomyWeekComparison.Status.AVAILABLE,
                report.date(), baselineDate, 7,
                money(report.activeGold30d()), baseline.activeGold(), activeDelta,
                ratio(activeDelta, baseline.activeGold()), money(report.totalGold()), baseline.totalGold(),
                totalDelta, ratio(totalDelta, baseline.totalGold()));
    }

    private static EconomyWeekComparison unavailable(
            EconomyReport report,
            LocalDate baselineDate,
            int historicalDays,
            EconomyWeekComparison.Status status
    ) {
        BigDecimal zeroMoney = BigDecimal.ZERO.setScale(2);
        BigDecimal zeroRatio = BigDecimal.ZERO.setScale(4);
        return new EconomyWeekComparison(status, report.date(), baselineDate, historicalDays,
                money(report.activeGold30d()), zeroMoney, zeroMoney, zeroRatio,
                money(report.totalGold()), zeroMoney, zeroMoney, zeroRatio);
    }

    private static DailyPoint read(Path source, LocalDate expectedDate) throws IOException {
        if (!Files.isRegularFile(source)) return null;
        String json = Files.readString(source);
        String dateText = field(DATE, json);
        String totalText = field(TOTAL, json);
        String activeText = field(ACTIVE, json);
        if (dateText == null || totalText == null || activeText == null) return null;
        try {
            LocalDate date = LocalDate.parse(dateText);
            BigDecimal total = money(new BigDecimal(totalText));
            BigDecimal active = money(new BigDecimal(activeText));
            if (!date.equals(expectedDate) || total.signum() < 0 || active.signum() < 0) return null;
            return new DailyPoint(date, total, active);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String field(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void writeAtomically(
            Path target, String content, BooleanSupplier publishAllowed
    ) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            requirePublication(publishAllowed);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                requirePublication(publishAllowed);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requirePublication(BooleanSupplier publishAllowed) {
        if (!publishAllowed.getAsBoolean()) throw new CancellationException("economy history generation expired");
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal delta, BigDecimal baseline) {
        return baseline.signum() == 0 ? BigDecimal.ZERO.setScale(4)
                : delta.divide(baseline, 4, RoundingMode.HALF_UP);
    }

    private record DailyPoint(LocalDate date, BigDecimal totalGold, BigDecimal activeGold) {}
}
