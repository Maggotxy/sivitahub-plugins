package cn.originalrpg.api.model;

import java.util.Objects;

/**
 * Phase 5 / 宪法 C-012：support_credit（现实付费积分）独立账本的结算结果。
 *
 * <p>与 {@link CreditResult} 的语义差别是刻意的：{@code support_credit} 是**现实付费**来源，
 * 必须与 {@code gold / merit / starlight} 物理隔离，因此这里不暴露任何跨币种字段，
 * 只暴露本账本余额与已入账单位。</p>
 */
public record SupportCreditResult(Status status, long balance, long amount) {
    public SupportCreditResult {
        Objects.requireNonNull(status, "status");
        if (balance < 0 || amount < 0) {
            throw new IllegalArgumentException("Support credit values cannot be negative");
        }
    }

    public enum Status {
        SUCCESS,
        /** 余额不足，或兑换目标不是实物/便利商品。 */
        INSUFFICIENT_FUNDS,
        /** 幂等键命中：本次请求已经入账，余额未再次变动。 */
        DUPLICATE,
        /** 目标被 C-012 禁止连接清单拦下（充值不得影响战力经济）。 */
        CROSS_CURRENCY_FORBIDDEN,
        BALANCE_LIMIT,
        INVALID
    }
}
