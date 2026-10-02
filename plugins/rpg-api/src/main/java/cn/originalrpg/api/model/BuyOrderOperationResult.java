package cn.originalrpg.api.model;

import java.util.Objects;

/**
 * Phase 4 市场 V2：买单簿操作结果。
 *
 * <p>C-009 单一托管：买单与卖单共用同一套结算/税模型（{@code rpg_ledger} + {@code rpg_accounts}
 * + {@code rpg_market_deliveries}），本 DTO 只描述买单自己的状态，不引入第二套账本。</p>
 */
public record BuyOrderOperationResult(Status status, MarketOrder order) {
    public BuyOrderOperationResult {
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        SUCCESS,
        NOT_FOUND,
        NOT_OWNER,
        NOT_ACTIVE,
        /** 买单簿与卖单挂牌对不上：没有可成交的攻击者商品。 */
        NO_MATCH,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        DUPLICATE,
        INVALID
    }
}
