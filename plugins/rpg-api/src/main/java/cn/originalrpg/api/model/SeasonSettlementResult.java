package cn.originalrpg.api.model;

import cn.originalrpg.api.rules.SeasonSettlementState;

import java.util.Objects;

/**
 * 一次赛季结算状态迁移的结果。{@code Status} 里的拒绝原因对应 C-010 状态机的失败模式，
 * 调用方（管理命令）据此给出可操作提示，而不是靠异常字符串猜。
 */
public record SeasonSettlementResult(
        Status status,
        SeasonSettlementState state,
        String seasonId,
        long scanned,
        long carried,
        boolean replayed
) {
    public SeasonSettlementResult {
        Objects.requireNonNull(status, "status");
        state = state == null ? SeasonSettlementState.DRAFT : state;
        seasonId = seasonId == null ? "" : seasonId;
    }

    public enum Status {
        /** 状态已推进/已回滚。 */
        APPLIED,
        /** 同一结算 id 的完全相同重放，没有任何重复发放。 */
        DUPLICATE,
        /** 同一结算 id 的请求内容不同，拒绝而不是覆盖历史。 */
        CONFLICT,
        /** 目标态与当前态相同，无操作。 */
        UNCHANGED,
        /** 该迁移不在状态机的合法边里。 */
        ILLEGAL_TRANSITION,
        /** 迁移后必须等待上一次结算进度，或结算 id 不存在。 */
        BLOCKED,
        INVALID
    }

    public boolean succeeded() {
        return status == Status.APPLIED || status == Status.DUPLICATE || status == Status.UNCHANGED;
    }
}
