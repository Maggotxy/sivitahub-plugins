package cn.originalrpg.api.service;

import cn.originalrpg.api.model.ProgressionAnomaly;
import cn.originalrpg.api.model.ProgressionAnomalyResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Phase 1：成长异常清单（{@code rpg_progression_anomalies}）的写入口与 [H] 复核出口。
 *
 * <p>AGENTS.md §3 / {@code docs/audits/PROGRESSION_SEMANTICS_AUDIT.md} 要求：
 * 旧档案无法用 {@code ProgressionRules} 唯一映射时，**禁止 {@code level % 30} 猜测**，
 * 必须落进 {@code rpg_progression_anomalies} 交人工复核。{@link #inspect} 就是那个写入口
 * （真实调用链：{@code JdbcProfileService.load()} → 读到不可解释档案即落行）。</p>
 *
 * <p>[H] 门禁语义按文档实现：{@code rpg_progression_anomalies} 的行是「待人工裁决」，
 * 只有人能签它（{@link #acknowledge}）。自动流程既不修档案、也不签署记录、
 * 更不据此发补偿 —— C-013 的「自动系统只能冻结，不能自动改价」在这里同样成立。</p>
 *
 * <p><b>读侧已由 {@code /rpgadmin anomaly} 接线</b>：{@code list} 列未复核记录（分页/上限），
 * {@code ack <player-uuid> <结论>} 落 {@code reviewer} 署名 + {@code reviewed_at} 时间。
 * 完整闭环见 {@code docs/ECONOMY.md} §「[H] 复核闭环」。</p>
 */
public interface ProgressionAnomalyService {
    /**
     * 按当前 {@code ProgressionRules} 判定这份快照能否被唯一解释。
     * 健康返回 {@link ProgressionAnomalyResult.Status#HEALTHY} 且不落库。
     */
    CompletionStage<ProgressionAnomalyResult> inspect(
            UUID playerId, int ascension, int level, long experience);

    /** 读取某玩家未复核的异常记录（已签署的行不再返回）。 */
    CompletionStage<Optional<ProgressionAnomaly>> pending(UUID playerId);

    /** 列出未复核记录（人工复核清单，按发现时间升序）。 */
    CompletionStage<List<ProgressionAnomaly>> pending(int limit);

    /** 分页列出未复核记录：{@code offset} 跳过的行数，{@code limit} 单页上限。 */
    CompletionStage<List<ProgressionAnomaly>> pending(int offset, int limit);

    /** 未复核记录总数（分页要显示「还有多少条」，也让操作者能判断清单是否真的在缩短）。 */
    CompletionStage<Long> pendingCount();

    /**
     * [H] 门禁出口：人工复核后把 {@code reviewer} 署名与 {@code reviewedAt} 时间落到该行。
     *
     * <p>语义是**幂等签署**而不是删除：行仍在表里，只是带上复核结论；重复调用同一玩家不报错、
     * 不覆盖首个署名与首次复核时间（先到者为准）。{@code reviewer} 必须非空，否则拒绝
     * —— 没有署名的复核不算复核。</p>
     *
     * @param note 复核结论（随署名落库，供后续审计对照；空白视为未填写）
     * @return {@code true} = 本次真的签了；{@code false} = 无该玩家待复核行，或参数不合法
     */
    CompletionStage<Boolean> acknowledge(UUID playerId, String reviewer, String note);
}
