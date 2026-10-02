package cn.originalrpg.api.model;

import java.util.Objects;

/** 成长异常写入口的结果。 */
public record ProgressionAnomalyResult(Status status, ProgressionAnomaly anomaly) {
    public ProgressionAnomalyResult {
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        /** 本次真的新落了一行异常记录。 */
        RECORDED,
        /** 该玩家已有一条未复核记录：更新其最新快照而不是新增第二行（主键是 player_uuid）。 */
        UPDATED,
        /** 档案符合当前语义，不需要人工复核。 */
        HEALTHY,
        INVALID
    }
}
