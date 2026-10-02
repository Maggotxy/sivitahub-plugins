package cn.originalrpg.market;

final class StallClaimPolicy {
    private StallClaimPolicy() {
    }

    enum Decision {
        ALLOWED,
        CLAIM_REQUIRED,
        INTEGRATION_UNAVAILABLE
    }

    static Decision decide(
            boolean providerAvailable,
            boolean verificationSucceeded,
            boolean claimPresent,
            boolean playerMayEdit
    ) {
        if (!providerAvailable || !verificationSucceeded) return Decision.INTEGRATION_UNAVAILABLE;
        if (!claimPresent || !playerMayEdit) return Decision.CLAIM_REQUIRED;
        return Decision.ALLOWED;
    }
}
