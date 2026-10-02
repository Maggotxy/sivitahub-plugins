package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 1 成长异常清单（{@code rpg_progression_anomalies}）的一行。
 *
 * <p>定位是**人工复核清单**，不是自动修复器：写入只代表「这份档案无法被既有语义唯一解释，
 * 需要人来判断」，{@link #reason()} 记录判定依据，{@link #detectedAt()} 记录发现时刻。</p>
 *
 * <p>[H] 复核结论是**同一行上的两列**（{@link #reviewer()} / {@link #reviewedAt()}），
 * 不是删除：{@code null} 表示尚未复核，非空即已由真人签署。保留原行是为了让「当初为什么
 * 被判定异常」与「谁在何时签的字」在同一处可审计 —— 删掉原行会让复核结论失去对照物。</p>
 */
public record ProgressionAnomaly(
        UUID playerId,
        int ascension,
        int level,
        long experience,
        String reason,
        Instant detectedAt,
        String reviewer,
        Instant reviewedAt
) {
    public ProgressionAnomaly {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }

    /** 是否仍待人工复核（[H] 门禁的未决状态）。 */
    public boolean pendingReview() {
        return reviewedAt == null;
    }
}
