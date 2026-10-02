package cn.originalrpg.api.service;

import cn.originalrpg.api.model.BuyOrderOperationResult;
import cn.originalrpg.api.model.MarketOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Phase 4 市场 V2 买单簿（{@code rpg_market_buy_orders}）。
 *
 * <p>C-009 单一托管：买单的托管金币同样落在 {@code rpg_accounts}，成交流水同样写
 * {@code rpg_ledger}，交付仍走 {@code rpg_market_deliveries}。本接口不引入第二套市场、
 * 第二套结算或第二种货币 —— 它只是「同一簿记的另一侧」。状态机与
 * {@link MarketOrder} 一致：{@code PREPARED → ACTIVE → FILLED/CANCELLED/EXPIRED} 只能前向。</p>
 */
public interface MarketBuyOrderService {
    /**
     * 挂买单：校验后托管买家金币（下单即扣款进托管），状态直接进入 ACTIVE。
     * 幂等依据是 {@code orderId} 主键；同 id 重放返回 DUPLICATE 且不重复扣款。
     */
    CompletionStage<BuyOrderOperationResult> place(
            UUID orderId, UUID buyerId, String itemMatcher, long price, int quantity, String idempotencyKey);

    /**
     * 撮合：卖家把 {@code quantity} 件 {@code itemMatcher} 交给买家。
     * 托管金币税后结算给卖家，商品经持久化邮箱交付给买家（与卖单同一投递通道）。
     * 幂等依据是 {@code idempotency_key}（流水表唯一约束）。
     */
    CompletionStage<BuyOrderOperationResult> fill(
            UUID orderId, UUID sellerId, String itemPayload, int quantity, String idempotencyKey);

    /** 撤单：托管金币全额退回买家，释放托管。 */
    CompletionStage<BuyOrderOperationResult> cancel(UUID orderId, UUID buyerId, String idempotencyKey);

    /** 过期：把 {@code createdBefore} 之前的 ACTIVE 买单批量退回托管，返回处理条数。 */
    CompletionStage<Integer> expire(java.time.Instant createdBefore, int limit);

    CompletionStage<Optional<MarketOrder>> order(UUID orderId);

    CompletionStage<List<MarketOrder>> activeOrders(String itemMatcher, int limit);
}
