package cn.originalrpg.api.model;

public record MerchantUsage(int purchasedUnits, int buybackUnits) {
    public MerchantUsage {
        if (purchasedUnits < 0 || buybackUnits < 0) {
            throw new IllegalArgumentException("Merchant usage cannot be negative");
        }
    }

    public static MerchantUsage empty() {
        return new MerchantUsage(0, 0);
    }
}
