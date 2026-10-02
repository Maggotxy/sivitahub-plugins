package cn.originalrpg.skills;

/** Pure timing rules for the cancellable single-F/double-F combat control. */
final class SkillInputPolicy {
    private SkillInputPolicy() {
    }

    static boolean isDoublePress(long firstMillis, long secondMillis, long windowMillis) {
        long delta = secondMillis - firstMillis;
        return delta >= 0L && delta <= windowMillis;
    }

    static long delayTicks(long windowMillis) {
        return Math.max(1L, Math.floorDiv(Math.max(0L, windowMillis) + 49L, 50L));
    }
}
