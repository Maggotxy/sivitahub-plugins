package cn.originalrpg.skills;

/**
 * 攻方增益的唯一结算口径（G-105）。
 *
 * <p>战斗伤害被拆成两种互不重复的运算，顺序固定、职责单一：
 * <ol>
 *   <li><b>加增益</b>（{@link #bonus}）：rallying-standard 光环与 battle-trance 连击层数
 *       合成一个乘数 {@code bonus >= 1.0}。它不直接写 {@code event.setDamage}。</li>
 *   <li><b>乘倍率</b>：属性 / 武器 / 暴击的 {@code attackMultiplier}，PvP / Boss 归一化
 *       {@code targetMultiplier}。</li>
 * </ol>
 *
 * <p>历史缺陷：SkillsCommand 在总线外先 {@code event.setDamage(base * 1.12)}，
 * 随后 {@code ExecuteListener.onDamage} 又对同一伤害乘 {@code attackMultiplier}
 * 与 {@code targetMultiplier}，同一个 bonus 被结算两次（G-105「二次放大」）。
 *
 * <p>现在的约束：本类只产出乘数，调用方必须在总线的**同一个**结算点、按
 * 「先减伤 → 再乘攻击倍率 → 最后乘本增益」的顺序使用一次，禁止在事件监听器里
 * 预先改写 {@code event.getDamage()}。
 *
 * <p>纯函数，不接触 Bukkit，便于在无服务端环境下断言乘数。
 */
final class AttackerBonusRules {
    /** rallying-standard 光环：固定 +12%，与原实现一致。 */
    static final double STANDARD_BONUS = 0.12;
    /** battle-trance 每层 +6%，最多 5 层，与原实现一致。 */
    static final double TRANCE_BONUS_PER_STACK = 0.06;
    static final int TRANCE_MAX_STACKS = 5;

    private AttackerBonusRules() {
    }

    /**
     * 光环与连击层数合成一个乘数；两者同时生效时相乘。
     * 非有限输入按「不生效」处理，返回值恒 {@code >= 1.0}，绝不产生负伤害或传播 NaN。
     */
    static double bonus(boolean standardActive, int tranceStacks) {
        double multiplier = 1.0;
        if (standardActive) multiplier += STANDARD_BONUS;
        if (tranceStacks > 0) {
            multiplier += Math.min(TRANCE_MAX_STACKS, Math.max(0, tranceStacks)) * TRANCE_BONUS_PER_STACK;
        }
        return Double.isFinite(multiplier) ? Math.max(1.0, multiplier) : 1.0;
    }

    /**
     * 把增益施加到伤害上。这是**唯一**允许调用 {@code event.setDamage} 形态的入口：
     * 输入是总线已经算完的伤害，输出是最终伤害，调用方不得在此之前重复乘同一 bonus。
     */
    static double apply(double damage, double bonus) {
        if (!Double.isFinite(damage)) return 0.0;
        double bounded = Double.isFinite(bonus) ? Math.max(1.0, bonus) : 1.0;
        return Math.max(0.0, damage) * bounded;
    }

    /** battle-trance 连击层数推进：2.5 秒内续击叠层，否则从 1 层重开。 */
    static int nextStacks(long now, long lastHitAt, int currentStacks) {
        if (now - lastHitAt > 2_500L) return 1;
        return Math.min(TRANCE_MAX_STACKS, Math.max(0, currentStacks) + 1);
    }
}
