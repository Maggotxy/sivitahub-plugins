package cn.originalrpg.api.service;

import cn.originalrpg.api.model.SupportCreditResult;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 宪法 C-012：现实付费积分 {@code support_credit} 的独立账本。
 *
 * <p>物理隔离是硬约束，不是注释：</p>
 * <ul>
 *   <li>余额只在 {@code rpg_support_credit_accounts}，流水只在 {@code rpg_support_credit_ledger}；
 *       不写 {@code rpg_accounts}（金币）、{@code rpg_merit_ledger}（功勋）、
 *       {@code rpg_credit_ledger}（星辉）。</li>
 *   <li>{@link #spend} 的 {@code targetKind} 若属 {@link SupportCreditTarget} 的禁止清单
 *       （装备/材料/保护石/幸运符/经验/体力/掉率/赛季层调谐/排行战力），一律
 *       {@link SupportCreditResult.Status#CROSS_CURRENCY_FORBIDDEN}，不落任何行。</li>
 * </ul>
 */
public interface SupportCreditService {
    /** C-012 允许的消费通道：只有外观与便利，绝不触及战力经济。 */
    enum SupportCreditTarget {
        /** 外观 / 装饰。 */
        COSMETIC(true),
        /** 便利（背包整理、传送卷等非战斗收益）。 */
        CONVENIENCE(true),
        /** 装备。禁止。 */
        EQUIPMENT(false),
        /** 强化材料。禁止。 */
        MATERIAL(false),
        /** 保护石 / 幸运符等强化辅助物。禁止。 */
        FORGE_CONSUMABLE(false),
        /** 经验 / 等级。禁止。 */
        EXPERIENCE(false),
        /** 体力 / 能量。禁止。 */
        ENERGY(false),
        /** 掉率 / 幸运。禁止。 */
        DROP_RATE(false),
        /** 赛季层调谐（C-008 的调谐退出路径）。禁止。 */
        SEASON_TUNING(false),
        /** 排行榜战力。禁止。 */
        COMBAT_POWER(false);

        private final boolean allowed;

        SupportCreditTarget(boolean allowed) {
            this.allowed = allowed;
        }

        /** true = C-012 允许（仅外观与便利）。 */
        public boolean allowedByC012() {
            return allowed;
        }
    }

    CompletionStage<Long> balance(UUID playerId);

    /**
     * 充值：余额表与流水表**同一 JDBC 事务**提交，幂等键唯一约束决定「是否首次生效」。
     * 重放同一幂等键返回 {@link SupportCreditResult.Status#DUPLICATE} 且不再加余额。
     */
    CompletionStage<SupportCreditResult> grant(UUID playerId, long amount, String idempotencyKey, String reason);

    /**
     * 消费：同一事务内校验目标是否被 C-012 允许、余额是否足够，然后落流水并扣减余额。
     * 禁止清单目标在动账之前就返回 {@link SupportCreditResult.Status#CROSS_CURRENCY_FORBIDDEN}。
     */
    CompletionStage<SupportCreditResult> spend(
            UUID playerId, long amount, SupportCreditTarget target, String idempotencyKey, String reason);
}
