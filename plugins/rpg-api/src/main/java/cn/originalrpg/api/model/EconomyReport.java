package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record EconomyReport(
        LocalDate date,
        long accountCount,
        BigDecimal totalGold,
        long totalMerit,
        long totalStarlight,
        BigDecimal activeGold30d,
        BigDecimal medianGold,
        BigDecimal p90Gold,
        BigDecimal p99Gold,
        BigDecimal topOnePercentGoldShare,
        BigDecimal marketTopSellerShare30d,
        long activeListingCount,
        long pendingDeliveryCount,
        long quarantinedListingCount,
        BigDecimal medianActiveListingAgeHours,
        BigDecimal p90ActiveListingAgeHours,
        BigDecimal oldestActiveListingAgeHours,
        long soldListings7d,
        long expiredListings7d,
        EconomyFlow daily,
        EconomyFlow sevenDays,
        EconomyFlow thirtyDays,
        List<EconomyReasonTotal> topSources7d,
        List<EconomyReasonTotal> topSinks7d
) {
    public EconomyReport {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(totalGold, "totalGold");
        Objects.requireNonNull(activeGold30d, "activeGold30d");
        Objects.requireNonNull(medianGold, "medianGold");
        Objects.requireNonNull(p90Gold, "p90Gold");
        Objects.requireNonNull(p99Gold, "p99Gold");
        Objects.requireNonNull(topOnePercentGoldShare, "topOnePercentGoldShare");
        Objects.requireNonNull(marketTopSellerShare30d, "marketTopSellerShare30d");
        Objects.requireNonNull(medianActiveListingAgeHours, "medianActiveListingAgeHours");
        Objects.requireNonNull(p90ActiveListingAgeHours, "p90ActiveListingAgeHours");
        Objects.requireNonNull(oldestActiveListingAgeHours, "oldestActiveListingAgeHours");
        if (accountCount < 0 || totalMerit < 0 || totalStarlight < 0 || activeListingCount < 0
                || pendingDeliveryCount < 0 || quarantinedListingCount < 0 || soldListings7d < 0
                || expiredListings7d < 0 || medianActiveListingAgeHours.signum() < 0
                || p90ActiveListingAgeHours.signum() < 0 || oldestActiveListingAgeHours.signum() < 0) {
            throw new IllegalArgumentException("Economy report counts cannot be negative");
        }
        Objects.requireNonNull(daily, "daily");
        Objects.requireNonNull(sevenDays, "sevenDays");
        Objects.requireNonNull(thirtyDays, "thirtyDays");
        topSources7d = List.copyOf(topSources7d);
        topSinks7d = List.copyOf(topSinks7d);
    }

    public BigDecimal sinkRatio() {
        return daily.sinkRatio();
    }

    public BigDecimal mintedGold() { return daily.mintedGold(); }
    public BigDecimal burnedGold() { return daily.burnedGold(); }
    public BigDecimal feesBurned() { return daily.feesBurned(); }
    public BigDecimal transferVolume() { return daily.transferVolume(); }
    public long mintedMerit() { return daily.mintedMerit(); }
    public long spentMerit() { return daily.spentMerit(); }
}
