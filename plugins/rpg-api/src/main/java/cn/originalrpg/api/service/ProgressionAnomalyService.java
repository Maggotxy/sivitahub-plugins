package cn.originalrpg.api.service;

import cn.originalrpg.api.model.ProgressionAnomaly;
import cn.originalrpg.api.model.ProgressionAnomalyResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Phase 1：成长异常的检测与落库写入口。
 *
 * <p>AGENTS.md §3 / {@code docs/audits/PROGRESSION_SEMANTICS_AUDIT.md} 要求：
 * 旧档案无法用 {@code ProgressionRules} 唯一映射时，**禁止 {@code level % 30} 猜测**，
 * 必须落进 {@code rpg_progression_anomalies} 交人工复核。本接口就是那个写入口。</p>
 *
 * <p>[H] 门禁语义按文档实现：{@code rpg_progression_anomalies} 的行是「待人工裁决」，
 * 只有人能把它标为已阅（{@link #acknowledge}）。自动流程既不修档案、也不删记录、
 * 更不据此发补偿 —— C-013 的「自动系统只能冻结，不能自动改价」在这里同样成立。</p>
 */
public interface ProgressionAnomalyService {
    /**
     * 按当前 {@code ProgressionRules} 判定这份快照能否被唯一解释。
     * 健康返回 {@link ProgressionAnomalyResult.Status#HEALTHY} 且不落库。
     */
    CompletionStage<ProgressionAnomalyResult> inspect(
            UUID playerId, int ascension, int level, long experience);

    /** 读取某玩家未复核的异常记录。 */
    CompletionStage<Optional<ProgressionAnomaly>> pending(UUID playerId);

    /** 列出未复核记录（人工复核清单，按发现时间升序）。 */
    CompletionStage<List<ProgressionAnomaly>> pending(int limit);

    /**
     * [H] 门禁出口：人工复核通过后删除该行。这是唯一能清掉异常记录的路径，
     * 参数 {@code reviewer} 必须非空，否则拒绝（没有署名的复核不算复核）。
     */
    CompletionStage<Boolean> acknowledge(UUID playerId, String reviewer);
}
