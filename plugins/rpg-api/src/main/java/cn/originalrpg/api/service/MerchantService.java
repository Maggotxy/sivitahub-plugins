package cn.originalrpg.api.service;

import cn.originalrpg.api.model.MerchantTransactionResult;
import cn.originalrpg.api.model.MerchantUsage;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MerchantService {
    CompletionStage<Map<String, MerchantUsage>> usage(UUID playerId, String periodKey);

    CompletionStage<MerchantTransactionResult> purchase(
            UUID playerId,
            String offerId,
            String periodKey,
            int units,
            int maximumUnits,
            BigDecimal unitPrice,
            String itemPayload,
            String idempotencyKey
    );

    CompletionStage<MerchantTransactionResult> buyback(
            UUID playerId,
            String offerId,
            String periodKey,
            int units,
            int maximumUnits,
            BigDecimal unitPrice,
            String idempotencyKey
    );
}
