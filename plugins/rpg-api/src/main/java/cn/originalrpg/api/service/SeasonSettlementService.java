package cn.originalrpg.api.service;

import cn.originalrpg.api.model.SeasonSettlementPreview;
import cn.originalrpg.api.model.SeasonSettlementResult;
import cn.originalrpg.api.rules.SeasonSettlementState;

import java.util.concurrent.CompletionStage;

/**
 * C-010：换季必须事务化。九态状态机
 * {@code DRAFT → PREVIEWED → APPROVED → FREEZING → SETTLING → VERIFYING → COMPLETED/FAILED/ROLLED_BACK}。
 *
 * <p>推进一格的合法前置（调用方必须按此顺序，服务不提供跳步）：</p>
 * <ol>
 *   <li>{@code DRAFT → PREVIEWED}：无额外前置；这一步写入本次结算的事实指纹。</li>
 *   <li>{@code PREVIEWED → APPROVED}：事实指纹必须与预览时一致，否则 CONFLICT（要求重新预览）。</li>
 *   <li>{@code APPROVED → FREEZING}：无额外前置。</li>
 *   <li>{@code FREEZING → SETTLING}：<b>资金动作</b>。必须给出不同于当前赛季的
 *       {@code nextSeasonId}（结算到自己身上一律 INVALID）；且结算前复算的事实指纹
 *       必须仍与审批时一致，否则先置 FAILED 而不是照旧执行。</li>
 *   <li>{@code SETTLING → VERIFYING}：只在校验通过后允许；失败则置 FAILED。</li>
 *   <li>{@code VERIFYING → COMPLETED}：只在校验通过后允许；失败则置 FAILED。</li>
 *   <li>{@code FAILED → ROLLED_BACK}：终态之间的唯一出口，只能由操作者显式回滚。</li>
 * </ol>
 *
 * <p>契约：</p>
 * <ul>
 *   <li>每次调用只推进一格；非法迁移被拒绝且不写任何行。</li>
 *   <li>迁移到 SETTLING 时，把旧赛季的「赛季层」功勋结清进不可变账本，
 *       并在同一事务里把钱包的赛季层归零、把钱包打上新赛季标记。</li>
 *   <li>永久层余额、金币、星辉、物品、战力一概不参与换季。</li>
 *   <li>同一 {@code settlementId} 的重放返回 DUPLICATE，绝不重复发放或重复归零。</li>
 *   <li>换季是资金动作：FAILED 之后只能由操作者回滚（ROLLED_BACK），
 *       绝不允许退回可继续结算的状态。</li>
 * </ul>
 */
public interface SeasonSettlementService {
    /** 只读当前状态；不存在时返回 DRAFT（尚未建账）。 */
    CompletionStage<SeasonSettlementState> state(String seasonId);

    /** 只读最新一次结算事实的预览，不推进状态。 */
    CompletionStage<SeasonSettlementPreview> preview(String seasonId);

    /** 推进一格，{@code reason} 必填以便审计。 */
    CompletionStage<SeasonSettlementResult> advance(
            String seasonId, SeasonSettlementState target, String actor, String reason);

    /**
     * 推进一格，并在 SETTLING 时把旧赛季的赛季层功勋结清。
     *
     * @param seasonId        正在收口的赛季（旧赛季）
     * @param nextSeasonId    SETTLING 时要切到的新赛季；其他状态可为 null
     */
    CompletionStage<SeasonSettlementResult> advance(
            String seasonId, String nextSeasonId, SeasonSettlementState target, String actor, String reason);
}
