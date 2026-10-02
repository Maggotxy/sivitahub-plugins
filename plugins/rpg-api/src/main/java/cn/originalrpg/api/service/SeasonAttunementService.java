package cn.originalrpg.api.service;

import cn.originalrpg.api.model.SeasonAttunementRecord;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * C-008 第五条退出路径「赛季调谐」的接线点。
 *
 * <p>文档语义（不是发明）：{@code docs/LONG_TERM_GAME_ECONOMY_PLAN.md} 第 3 节把调谐
 * 列为装备「退出四层」的第③层，与分解（SALVAGED）/ 传承（LEGACY_EXTRACTED）并列；
 * {@code docs/CODEX_LONG_TERM_EXECUTION_PLAN.md} 的 Phase 4 把它归到装备退出工作里。
 * 它因此是一次「把装备身份带离当前赛季流通」的处置，不是一条玩家指令。</p>
 *
 * <p>触发者只有一个：换季结算（C-010）。旧赛季收口时，旧赛季资产层里的装备身份
 * 必须被处置，否则它们会带着旧赛季的数值继续在新赛季流通。调谐把身份收口成
 * {@code SEASON_ATTUNED}，再随唯一托管市场邮箱通道（C-009）交还给原持有者。</p>
 *
 * <p>契约：</p>
 * <ul>
 *   <li>结果态固定为 {@link SeasonAttunementRecord#REQUIRED_STATE}；状态机之外
 *       没有第二条路径可以产出该状态（{@code afterOperation} 不为调谐生成结果态）。</li>
 *   <li>幂等：{@code idempotencyKey} 由触发操作给出，重放返回同一记录，不二次推进。</li>
 *   <li>不结算金币 / 功勋 / 星辉，产物不得成为 support_credit 的兑换出口（C-012）。</li>
 *   <li>默认关闭（L-009）：未启用时不得读写任何调谐行，也不得被调用方走到。</li>
 * </ul>
 */
public interface SeasonAttunementService {
    /**
     * 把一名玩家在旧赛季的一件装备身份收口成赛季调谐态，并经唯一邮箱通道交还。
     *
     * @param idempotencyKey 触发操作的幂等键（生产路径为 {@code season-attune:<结算id>:<玩家>}）
     * @return 落库后的记录；被拒时 {@link SeasonAttunementRecord#attuned()} 为 false
     */
    CompletionStage<SeasonAttunementRecord> attune(
            String seasonId, java.util.UUID itemId, java.util.UUID ownerId, String idempotencyKey);

    /**
     * 换季结算入口：把一名玩家的旧赛季装备逐件调谐。
     *
     * <p>只处理旧赛季、且尚未成为终态的装备身份。同一玩家重放返回同一批记录。</p>
     */
    CompletionStage<List<SeasonAttunementRecord>> attuneForSettlement(
            String seasonId, java.util.UUID ownerId, String idempotencyKey);
}
