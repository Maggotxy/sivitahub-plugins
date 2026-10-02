package cn.originalrpg.core.persistence;

import java.time.Instant;

/**
 * 一条已持久化的 Outbox 记录（G-102）。
 *
 * <p>业务事务先写 {@code rpg_outbox}，后台消费者再按 {@code aggregateType}
 * 投递。{@code idempotencyKey} 是投递侧的唯一去重键：重复投递必须命中同一条
 * 记录，不得产生副作用。</p>
 */
public record OutboxEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        String idempotencyKey,
        String payload,
        int attempts,
        Instant createdAt,
        Instant processedAt
) {
    public boolean processed() {
        return processedAt != null;
    }
}
