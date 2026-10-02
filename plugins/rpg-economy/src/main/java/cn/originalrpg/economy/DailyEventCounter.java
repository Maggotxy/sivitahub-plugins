package cn.originalrpg.economy;

import java.util.HashSet;
import java.util.Set;

/**
 * 每日事件场次计数器（G-207）。
 *
 * <p>「每日最多 N 场」必须是第一类约束，而不是日额度除法的巧合：额度调小或单场结算变小时，
 * 场次不应该跟着变多。{@code rpg-world} 持有同样的规则（{@code WorldEventRules.dailyEventAllowed}），
 * 两个插件互不依赖且共享实现只能放进 {@code rpg-api}（超出本任务改动范围），因此各留一份本地纯逻辑。
 */
final class DailyEventCounter {
    private long currentDay = Long.MIN_VALUE;
    private int used;
    private final Set<String> claimedKeys = new HashSet<>();

    /**
     * 尝试占用当天一个名额。
     *
     * @param day      当天键；与上一次不同则重置，实现跨日恢复
     * @param dailyCap 每日上限；{@code <= 0} 表示不限制
     * @param key      同一事件重放的稳定标识；非空时同一 key 只占一次名额
     */
    boolean tryClaim(long day, int dailyCap, String key) {
        if (day != currentDay) {
            currentDay = day;
            used = 0;
            claimedKeys.clear();
        }
        if (dailyCap <= 0) return true;
        if (key != null && !claimedKeys.add(key)) return true;
        if (used >= dailyCap) {
            if (key != null) claimedKeys.remove(key);
            return false;
        }
        used++;
        return true;
    }

    int usedToday() {
        return used;
    }
}
