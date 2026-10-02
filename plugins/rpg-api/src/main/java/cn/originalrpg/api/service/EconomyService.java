package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CurrencyOperationResult;
import cn.originalrpg.api.model.TransactionResult;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface EconomyService {
    CompletionStage<BigDecimal> balance(UUID playerId);

    CompletionStage<CurrencyOperationResult> credit(
            UUID playerId,
            BigDecimal amount,
            String idempotencyKey,
            String reason
    );

    CompletionStage<CurrencyOperationResult> burn(
            UUID playerId,
            BigDecimal amount,
            String idempotencyKey,
            String reason
    );

    /**
     * Reverses one previously committed debit exactly once.
     *
     * <p>The original ledger entry must be a Gold burn owned by the same
     * player and have exactly the same amount. A successful reversal is tied
     * to that debit in the ledger, so changing the refund idempotency key
     * cannot refund it twice.</p>
     */
    CompletionStage<CurrencyOperationResult> refund(
            UUID playerId,
            BigDecimal amount,
            String originalDebitKey,
            String idempotencyKey,
            String reason
    );

    CompletionStage<TransactionResult> transfer(
            UUID sender,
            UUID receiver,
            BigDecimal amount,
            BigDecimal feeRate,
            String idempotencyKey,
            String reason
    );
}
