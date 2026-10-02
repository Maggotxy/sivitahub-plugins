package cn.originalrpg.api.service;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 赛季层功勋钱包（区别于 {@code rpg_progression_wallets.merit} 的永久层）。
 *
 * <p>C-010 要求「永久层与赛季层物理隔离」。这里只提供赛季层的记账入口，
 * 它不能给自己铸币（无充值路径），只能由玩家自己在两层的转换/贡献动作里移动，
 * 或由换季结算一次性结清。</p>
 */
public interface SeasonMeritWalletService {
    /** 当前赛季层功勋。未开户返回 0。 */
    CompletionStage<Long> seasonMerit(UUID playerId);

    /**
     * 把 {@code amount} 从永久层转入赛季层（幂等键去重）。
     * 转入只是把同一份功勋换个展示层，净供给恒为 0。
     */
    CompletionStage<Long> deposit(UUID playerId, long amount, String idempotencyKey, String reason);

    /** 把全部赛季层功勋转回永久层（幂等键去重）。 */
    CompletionStage<Long> withdraw(UUID playerId, String idempotencyKey, String reason);
}
