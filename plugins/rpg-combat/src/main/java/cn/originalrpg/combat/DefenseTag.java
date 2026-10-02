package cn.originalrpg.combat;

/**
 * 防御结算的幂等守卫。
 *
 * 记录「某个伤害事件上一次被施加防御减伤的游戏刻」，保证同一事件在同一 tick 内
 * 只会被减一次 —— 即使互砍路径与父类监听器都被派发到同一事件，也不会双减。
 * 键用弱引用，事件对象回收后自动出表，不会积压。
 */
final class DefenseTag {
    private final java.util.Map<Object, Integer> appliedAtTick = new java.util.WeakHashMap<>();

    boolean alreadyApplied(Object event, int currentTick) {
        Integer applied = appliedAtTick.get(event);
        return applied != null && applied == currentTick;
    }

    void mark(Object event, int currentTick) {
        appliedAtTick.put(event, currentTick);
    }
}
