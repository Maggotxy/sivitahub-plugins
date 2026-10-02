package cn.originalrpg.api.model;

import java.math.BigDecimal;

/**
 * One independently idempotent reward inside a coalesced database transaction.
 * The key remains per game event so retrying a partially observed batch cannot
 * mint the same reward twice.
 */
public record ActivityRewardRequest(
        BigDecimal requestedGold,
        long requestedMerit,
        String idempotencyKey,
        String reason
) {
}
