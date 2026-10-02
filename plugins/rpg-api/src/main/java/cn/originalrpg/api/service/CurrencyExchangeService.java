package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CurrencyExchangeQuote;
import cn.originalrpg.api.model.CurrencyExchangeResult;
import cn.originalrpg.api.model.WealthBalance;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface CurrencyExchangeService {
    CompletionStage<WealthBalance> balance(UUID playerId);

    CompletionStage<List<CurrencyExchangeQuote>> quotes(UUID playerId);

    CompletionStage<CurrencyExchangeResult> exchange(
            UUID playerId,
            String routeId,
            long units,
            String idempotencyKey
    );
}
