package cn.originalrpg.admin;

import cn.originalrpg.api.model.EconomyFlow;
import cn.originalrpg.api.model.EconomyReasonTotal;
import cn.originalrpg.api.model.EconomyReport;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class EconomySnapshotWriter {
    private EconomySnapshotWriter() {}

    static void write(Path dataFolder, EconomyReport report) throws IOException {
        write(dataFolder, report, null);
    }

    static void write(
            Path dataFolder, EconomyReport report, EconomyWeekComparison weekComparison
    ) throws IOException {
        write(dataFolder, report, weekComparison, () -> true);
    }

    static void write(
            Path dataFolder, EconomyReport report, EconomyWeekComparison weekComparison,
            BooleanSupplier publishAllowed
    ) throws IOException {
        Objects.requireNonNull(publishAllowed, "publishAllowed");
        requirePublication(publishAllowed);
        Files.createDirectories(dataFolder);
        Path target = dataFolder.resolve("economy.json");
        Path temporary = Files.createTempFile(dataFolder, "economy.json.", ".tmp");
        try {
            Files.writeString(temporary, json(report, Instant.now(), weekComparison),
                    StandardOpenOption.TRUNCATE_EXISTING);
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
        if (!publishAllowed.getAsBoolean()) throw new CancellationException("economy snapshot generation expired");
    }

    static String json(EconomyReport report, Instant sampledAt) {
        return json(report, sampledAt, null);
    }

    static String json(
            EconomyReport report, Instant sampledAt, EconomyWeekComparison weekComparison
    ) {
        StringBuilder json = new StringBuilder(2_048);
        json.append('{')
                .append("\"schemaVersion\":1,")
                .append("\"sampledAt\":").append(quoted(sampledAt.toString())).append(',')
                .append("\"reportDate\":").append(quoted(report.date().toString())).append(',')
                .append("\"accounts\":").append(report.accountCount()).append(',')
                .append("\"totalGold\":").append(quoted(report.totalGold().toPlainString())).append(',')
                .append("\"totalMerit\":").append(report.totalMerit()).append(',')
                .append("\"totalStarlight\":").append(report.totalStarlight()).append(',')
                .append("\"activeGold30d\":").append(quoted(report.activeGold30d().toPlainString())).append(',')
                .append("\"medianGold\":").append(quoted(report.medianGold().toPlainString())).append(',')
                .append("\"p90Gold\":").append(quoted(report.p90Gold().toPlainString())).append(',')
                .append("\"p99Gold\":").append(quoted(report.p99Gold().toPlainString())).append(',')
                .append("\"topOnePercentGoldShare\":")
                .append(quoted(report.topOnePercentGoldShare().toPlainString())).append(',')
                .append("\"marketTopSellerShare30d\":")
                .append(quoted(report.marketTopSellerShare30d().toPlainString())).append(',')
                .append("\"activeListings\":").append(report.activeListingCount()).append(',')
                .append("\"pendingDeliveries\":").append(report.pendingDeliveryCount()).append(',')
                .append("\"quarantinedListings\":").append(report.quarantinedListingCount()).append(',')
                .append("\"medianActiveListingAgeHours\":")
                .append(money(report.medianActiveListingAgeHours())).append(',')
                .append("\"p90ActiveListingAgeHours\":")
                .append(money(report.p90ActiveListingAgeHours())).append(',')
                .append("\"oldestActiveListingAgeHours\":")
                .append(money(report.oldestActiveListingAgeHours())).append(',')
                .append("\"soldListings7d\":").append(report.soldListings7d()).append(',')
                .append("\"expiredListings7d\":").append(report.expiredListings7d()).append(',')
                .append("\"daily\":");
        appendFlow(json, report.daily());
        json.append(",\"sevenDays\":");
        appendFlow(json, report.sevenDays());
        json.append(",\"thirtyDays\":");
        appendFlow(json, report.thirtyDays());
        json.append(",\"topSources7d\":");
        appendReasons(json, report.topSources7d());
        json.append(",\"topSinks7d\":");
        appendReasons(json, report.topSinks7d());
        if (weekComparison != null) {
            json.append(",\"weekOverWeek\":");
            appendWeekComparison(json, weekComparison);
        }
        return json.append("}\n").toString();
    }

    private static void appendWeekComparison(StringBuilder json, EconomyWeekComparison comparison) {
        json.append('{')
                .append("\"status\":").append(quoted(comparison.status().name())).append(',')
                .append("\"available\":").append(comparison.available()).append(',')
                .append("\"currentDate\":").append(quoted(comparison.currentDate().toString())).append(',')
                .append("\"baselineDate\":").append(quoted(comparison.baselineDate().toString())).append(',')
                .append("\"historicalDays\":").append(comparison.historicalDays()).append(',')
                .append("\"currentActiveGold\":").append(money(comparison.currentActiveGold())).append(',')
                .append("\"baselineActiveGold\":").append(money(comparison.baselineActiveGold())).append(',')
                .append("\"activeGoldDelta\":").append(money(comparison.activeGoldDelta())).append(',')
                .append("\"activeGoldChangeRatio\":")
                .append(quoted(comparison.activeGoldChangeRatio().toPlainString())).append(',')
                .append("\"currentTotalGold\":").append(money(comparison.currentTotalGold())).append(',')
                .append("\"baselineTotalGold\":").append(money(comparison.baselineTotalGold())).append(',')
                .append("\"totalGoldDelta\":").append(money(comparison.totalGoldDelta())).append(',')
                .append("\"totalGoldChangeRatio\":")
                .append(quoted(comparison.totalGoldChangeRatio().toPlainString()))
                .append('}');
    }

    private static void appendFlow(StringBuilder json, EconomyFlow flow) {
        json.append('{')
                .append("\"days\":").append(flow.days()).append(',')
                .append("\"mintedGold\":").append(money(flow.mintedGold())).append(',')
                .append("\"burnedGold\":").append(money(flow.burnedGold())).append(',')
                .append("\"feesBurned\":").append(money(flow.feesBurned())).append(',')
                .append("\"transferVolume\":").append(money(flow.transferVolume())).append(',')
                .append("\"netGold\":").append(money(flow.netGold())).append(',')
                .append("\"sinkRatio\":").append(money(flow.sinkRatio())).append(',')
                .append("\"mintedMerit\":").append(flow.mintedMerit()).append(',')
                .append("\"spentMerit\":").append(flow.spentMerit()).append(',')
                .append("\"mintedStarlight\":").append(flow.mintedStarlight()).append(',')
                .append("\"spentStarlight\":").append(flow.spentStarlight())
                .append('}');
    }

    private static void appendReasons(StringBuilder json, List<EconomyReasonTotal> reasons) {
        json.append('[');
        for (int index = 0; index < reasons.size(); index++) {
            if (index > 0) json.append(',');
            EconomyReasonTotal reason = reasons.get(index);
            json.append("{\"reason\":").append(quoted(reason.reason()))
                    .append(",\"amount\":").append(money(reason.amount())).append('}');
        }
        json.append(']');
    }

    private static String money(BigDecimal value) {
        return quoted(value.toPlainString());
    }

    private static String quoted(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }
}
