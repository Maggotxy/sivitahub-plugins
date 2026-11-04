package cn.originalrpg.api.model;

import java.util.Set;

/** Read-only support-credit status; does not expose an earnable-currency conversion or membership benefit. */
public record SupportCreditStatus(long balance, long weekIssued, long weekLimit, Set<SupportCosmetic> cosmetics) {
    public SupportCreditStatus {
        if (balance < 0 || weekIssued < 0 || weekLimit < 0) throw new IllegalArgumentException("Negative support status");
        cosmetics = Set.copyOf(cosmetics);
    }
}
