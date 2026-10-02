package cn.originalrpg.skills;

/**
 * 攻方增益结算的幂等守卫（G-105）。
 *
 * <p>记录「某个伤害事件上一次被施加攻方增益的游戏刻」，保证同一事件在同一 tick 内
 * 只会被乘一次 —— 即使本插件的 HIGHEST 幂等入口与监听器内联调用都触达同一事件，
 * 也不会叠加放大。键用弱引用，事件对象回收后自动出表，不会积压。
 */
final class AttackerBonusTag {
    private final java.util.Map<Object, Integer> appliedAtTick = new java.util.WeakHashMap<>();

    boolean alreadyApplied(Object event, int currentTick) {
        Integer applied = appliedAtTick.get(event);
        return applied != null && applied == currentTick;
    }

    void mark(Object event, int currentTick) {
        appliedAtTick.put(event, currentTick);
    }
}
