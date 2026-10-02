package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record EconomyFlow(
        int days,
        BigDecimal mintedGold,
        BigDecimal burnedGold,
        BigDecimal feesBurned,
        BigDecimal transferVolume,
        long mintedMerit,
        long spentMerit,
        long mintedStarlight,
        long spentStarlight
) {
    public EconomyFlow {
        if (days < 1 || mintedMerit < 0 || spentMerit < 0 || mintedStarlight < 0 || spentStarlight < 0) {
            throw new IllegalArgumentException("Invalid economy flow");
        }
        Objects.requireNonNull(mintedGold, "mintedGold");
        Objects.requireNonNull(burnedGold, "burnedGold");
        Objects.requireNonNull(feesBurned, "feesBurned");
        Objects.requireNonNull(transferVolume, "transferVolume");
    }

    public BigDecimal sinkRatio() {
        if (mintedGold.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return burnedGold.add(feesBurned).divide(mintedGold, 2, RoundingMode.HALF_UP);
    }

    public BigDecimal netGold() {
        return mintedGold.subtract(burnedGold).subtract(feesBurned).setScale(2, RoundingMode.HALF_UP);
    }
}
