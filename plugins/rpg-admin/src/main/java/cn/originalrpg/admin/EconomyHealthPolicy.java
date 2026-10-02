package cn.originalrpg.admin;

import java.math.BigDecimal;
import java.util.Objects;

/** Code-owned alert thresholds matching docs/ECONOMY.md. Never changes prices automatically. */
final class EconomyHealthPolicy {
    private static final BigDecimal MINIMUM_MINTED_GOLD_SAMPLE = new BigDecimal("10000.00");
    private static final BigDecimal WARNING_MINIMUM_SINK_RATIO = new BigDecimal("0.85");
    private static final BigDecimal CRITICAL_MINIMUM_SINK_RATIO = new BigDecimal("0.70");
    private static final BigDecimal WARNING_WEALTH_SHARE = new BigDecimal("0.55");
    private static final BigDecimal CRITICAL_WEALTH_SHARE = new BigDecimal("0.70");
    private static final BigDecimal WARNING_MARKET_SHARE = new BigDecimal("0.35");
    private static final BigDecimal CRITICAL_MARKET_SHARE = new BigDecimal("0.50");
    private static final BigDecimal WARNING_SOURCE_SHARE = new BigDecimal("0.45");
    private static final BigDecimal CRITICAL_SOURCE_SHARE = new BigDecimal("0.60");
    private static final BigDecimal WARNING_ACTIVE_GOLD_WEEKLY_GROWTH = new BigDecimal("0.04");
    private static final BigDecimal WARNING_LISTING_AGE_HOURS = new BigDecimal("120.00");
    private static final BigDecimal CRITICAL_LISTING_AGE_HOURS = new BigDecimal("168.00");

    private EconomyHealthPolicy() {}

    static State evaluate(
            BigDecimal mintedGold,
            BigDecimal sinkRatio,
            BigDecimal wealthShare,
            BigDecimal marketShare,
            BigDecimal largestSourceShare,
            long quarantined,
            BigDecimal p90ListingAgeHours,
            long soldListings7d,
            long expiredListings7d
    ) {
        return evaluate(mintedGold, sinkRatio, wealthShare, marketShare, largestSourceShare,
                quarantined, p90ListingAgeHours, soldListings7d, expiredListings7d, null);
    }

    static State evaluate(
            BigDecimal mintedGold,
            BigDecimal sinkRatio,
            BigDecimal wealthShare,
            BigDecimal marketShare,
            BigDecimal largestSourceShare,
            long quarantined,
            BigDecimal p90ListingAgeHours,
            long soldListings7d,
            long expiredListings7d,
            BigDecimal activeGoldWeeklyGrowth
    ) {
        Objects.requireNonNull(mintedGold, "mintedGold");
        Objects.requireNonNull(sinkRatio, "sinkRatio");
        Objects.requireNonNull(wealthShare, "wealthShare");
        Objects.requireNonNull(marketShare, "marketShare");
        Objects.requireNonNull(largestSourceShare, "largestSourceShare");
        Objects.requireNonNull(p90ListingAgeHours, "p90ListingAgeHours");

        // Security/inventory integrity signals remain critical even before the
        // economy has enough issuance volume for a meaningful sink ratio.
        if (quarantined > 0 || p90ListingAgeHours.compareTo(CRITICAL_LISTING_AGE_HOURS) > 0) {
            return State.CRITICAL;
        }
        // A single prologue or first hunt naturally has 0% sinks and 100%
        // source concentration. Do not page operators until roughly one full
        // active-player daily envelope has been observed in the 7-day window.
        if (mintedGold.compareTo(MINIMUM_MINTED_GOLD_SAMPLE) < 0) return State.INSUFFICIENT_DATA;
        if (wealthShare.compareTo(CRITICAL_WEALTH_SHARE) > 0
                || marketShare.compareTo(CRITICAL_MARKET_SHARE) > 0
                || largestSourceShare.compareTo(CRITICAL_SOURCE_SHARE) > 0) {
            return State.CRITICAL;
        }
        if (sinkRatio.compareTo(CRITICAL_MINIMUM_SINK_RATIO) < 0) return State.CRITICAL;
        if (sinkRatio.compareTo(WARNING_MINIMUM_SINK_RATIO) < 0
                || wealthShare.compareTo(WARNING_WEALTH_SHARE) > 0
                || marketShare.compareTo(WARNING_MARKET_SHARE) > 0
                || largestSourceShare.compareTo(WARNING_SOURCE_SHARE) > 0
                || activeGoldWeeklyGrowth != null
                    && activeGoldWeeklyGrowth.compareTo(WARNING_ACTIVE_GOLD_WEEKLY_GROWTH) > 0
                || p90ListingAgeHours.compareTo(WARNING_LISTING_AGE_HOURS) > 0
                || expiredListings7d >= 10 && expiredListings7d > soldListings7d * 2L) {
            return State.WARNING;
        }
        return State.HEALTHY;
    }

    enum State {
        INSUFFICIENT_DATA,
        HEALTHY,
        WARNING,
        CRITICAL
    }
}
