package cn.originalrpg.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyEventCounterTest {
    @Test
    void theFourthRaidOfTheSameDayIsRejected() {
        // G-207 行为级验收：文档承诺袭击「每日最多 3 场」，第 4 场必须被拒。
        DailyEventCounter counter = new DailyEventCounter();
        long day = 20_000L;
        for (int event = 1; event <= 3; event++) {
            assertTrue(counter.tryClaim(day, 3, "raid-" + event), "第 " + event + " 场必须放行");
        }
        assertFalse(counter.tryClaim(day, 3, "raid-4"), "第 4 场必须被拒");
        assertEquals(3, counter.usedToday());
    }

    @Test
    void theNextDayRestoresTheFullAllowance() {
        DailyEventCounter counter = new DailyEventCounter();
        long day = 20_000L;
        for (int event = 1; event <= 3; event++) counter.tryClaim(day, 3, "raid-" + event);
        assertFalse(counter.tryClaim(day, 3, "raid-4"));

        assertTrue(counter.tryClaim(day + 1, 3, "raid-5"), "跨日必须重置名额");
        assertEquals(1, counter.usedToday());
    }

    @Test
    void aReplayedRaidDoesNotConsumeASecondSlot() {
        // 同一个袭击重放（同一幂等键）不应吃掉第二个名额：否则一次事件就会挤掉后续真实场次。
        DailyEventCounter counter = new DailyEventCounter();
        long day = 20_000L;
        assertTrue(counter.tryClaim(day, 3, "raid-a"));
        assertTrue(counter.tryClaim(day, 3, "raid-a"));
        assertEquals(1, counter.usedToday());
        assertTrue(counter.tryClaim(day, 3, "raid-b"));
        assertTrue(counter.tryClaim(day, 3, "raid-c"));
        assertFalse(counter.tryClaim(day, 3, "raid-d"), "三个真实场次后第 4 场必须被拒");
    }

    @Test
    void aZeroCapMeansNoEventLimit() {
        DailyEventCounter counter = new DailyEventCounter();
        for (int event = 0; event < 99; event++) {
            assertTrue(counter.tryClaim(20_000L, 0, "raid-" + event));
        }
    }
}
