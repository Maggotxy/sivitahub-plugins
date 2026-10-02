package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 1 成长异常清单（{@code rpg_progression_anomalies}）的一行。
 *
 * <p>定位是**人工复核清单**，不是自动修复器：写入只代表「这份档案无法被既有语义唯一解释，
 * 需要人来判断」，{@link #reason()} 记录判定依据，{@link #detectedAt()} 记录发现时刻。</p>
 */
public record ProgressionAnomaly(
        UUID playerId,
        int ascension,
        int level,
        long experience,
        String reason,
        Instant detectedAt
) {
    public ProgressionAnomaly {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }
}
