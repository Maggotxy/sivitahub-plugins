package cn.originalrpg.combat;

final class CombatTagRules {
    private CombatTagRules() {}

    static long deadline(long nowMillis, long durationSeconds) {
        if (durationSeconds <= 0L) return nowMillis;
        long durationMillis;
        try {
            durationMillis = Math.multiplyExact(durationSeconds, 1_000L);
            return Math.addExact(nowMillis, durationMillis);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    static long remainingSeconds(long nowMillis, long deadlineMillis) {
        if (deadlineMillis <= nowMillis) return 0L;
        return Math.max(1L, Math.floorDiv(deadlineMillis - nowMillis + 999L, 1_000L));
    }
}
