package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302-RESIDUAL-2：本类原用 {@code Files.readString} 读 {@code DungeonManager.java} 生产源码，
 * 再用 8 处 {@code indexOf} + 先后比较断言「调用点顺序」。那种写法有双重问题：
 * 真实行为被破坏时它未必变红（它只看字符串位置），纯重构时反而无谓变红。
 *
 * <p>现在改成<b>真跑</b>：借 {@link DungeonBukkitTestHarness} 装一个动态代理的
 * {@code Server}/{@code Plugin}/{@code World} 与记录型 {@code BukkitScheduler}，
 * 真实构造 {@code DungeonManager}，再真实调用会话绑定/回收/超时回调三条路径，
 * 断言可观察的状态变化（哪些任务被取消、会话代际指向谁、迟到回调是否被围栏挡住）。</p>
 *
 * <p><b>8 处 indexOf 的逐条归属</b>（每条都改成真跑断言）：</p>
 * <ol>
 *   <li>{@code assign >= 0} / {@code cancelOld > assign} / {@code putSession > cancelOld} /
 *       {@code schedule > putSession} → {@link #assignmentCancelsTheOldTimeoutBeforeBindingANewSession()}：
 *       真跑两次 {@code assignInstance}，断言第二次调用取消了第一次的超时任务、
 *       会话代际换成新 UUID、并挂了一个捕获新代际的新任务。</li>
 *   <li>{@code release >= 0} / {@code cancelRelease > release} →
 *       {@link #recyclingAnInstanceCancelsItsActiveTimeoutTask()}：真跑一次会话绑定后触发回收，
 *       断言仍持有的超时任务被取消、会话条目被清掉。</li>
 *   <li>{@code timeout >= 0} / {@code generationFence > timeout} →
 *       {@link #aLateScheduledCallbackMustStillOwnTheCurrentSession()}：真跑旧任务回调，
 *       断言它是 no-op（会话代际与成员都未被改动），再跑当前任务回调，断言它真的推进。</li>
 * </ol>
 */
class DungeonTimeoutLifecycleContractTest {

    private static final String INSTANCE_WORLD = "dungeon_instance_timeout_contract";

    @Test
    void assignmentCancelsTheOldTimeoutBeforeBindingANewSession() throws Exception {
        DungeonBukkitTestHarness.Scheduler scheduler = new DungeonBukkitTestHarness.Scheduler();
        DungeonManager manager = DungeonBukkitTestHarness.newManager(scheduler);
        var world = DungeonBukkitTestHarness.world(INSTANCE_WORLD);
        UUID member = UUID.randomUUID();

        DungeonBukkitTestHarness.assignInstance(manager, "timeout-contract", new LinkedHashSet<>(List.of(member)), world);
        Map<String, Object> sessions = DungeonBukkitTestHarness.field(manager, "instanceSessions");
        Map<String, Object> timeouts = DungeonBukkitTestHarness.field(manager, "instanceTimeouts");
        UUID firstSession = (UUID) sessions.get(INSTANCE_WORLD);
        assertTrue(firstSession != null, "首次绑定必须写入会话代际，否则迟到回调无法被围栏识别");
        assertEquals(1, scheduler.callbacks.size(), "首次绑定必须挂一个捕获该代际的超时任务");
        assertEquals(0, scheduler.cancelled.size(), "首次绑定没有旧任务可取消");

        // 同一世界再次绑定：必须先取消旧超时，再写入新代际，最后挂捕获新代际的任务。
        DungeonBukkitTestHarness.assignInstance(manager, "timeout-contract", new LinkedHashSet<>(List.of(member)), world);
        UUID secondSession = (UUID) sessions.get(INSTANCE_WORLD);
        assertNotEquals(firstSession, secondSession, "重新绑定必须换一个会话代际（否则旧回调仍自称拥有会话）");
        assertSame(scheduler.tasks.get(1), timeouts.get(INSTANCE_WORLD),
                "新绑定的超时任务必须登记在 instanceTimeouts 上，否则回收时无从取消");
        assertEquals(1, scheduler.cancelled.size(),
                "重新绑定必须先取消旧超时任务，否则旧任务会在新会话上触发一次过期结算");
        assertEquals(2, scheduler.callbacks.size(), "重新绑定后必须挂上捕获新代际的任务");
        assertNotSame(scheduler.tasks.get(0), scheduler.tasks.get(1),
                "两次绑定必须是两个不同的任务句柄，否则「取消旧任务」无从区分");
    }

    @Test
    void recyclingAnInstanceCancelsItsActiveTimeoutTask() throws Exception {
        DungeonBukkitTestHarness.Scheduler scheduler = new DungeonBukkitTestHarness.Scheduler();
        DungeonManager manager = DungeonBukkitTestHarness.newManager(scheduler);
        var world = DungeonBukkitTestHarness.world(INSTANCE_WORLD);

        DungeonBukkitTestHarness.assignInstance(manager, "timeout-contract",
                new LinkedHashSet<>(List.of(UUID.randomUUID())), world);
        Map<String, Object> sessions = DungeonBukkitTestHarness.field(manager, "instanceSessions");
        Map<String, Object> timeouts = DungeonBukkitTestHarness.field(manager, "instanceTimeouts");
        assertTrue(timeouts.containsKey(INSTANCE_WORLD), "绑定后必须持有超时任务，否则回收无任务可取消");

        // 回收该实例（生产入口是 releaseInstance）。
        var release = DungeonManager.class.getDeclaredMethod("releaseInstance", org.bukkit.World.class);
        release.setAccessible(true);
        release.invoke(manager, world);

        assertEquals(1, scheduler.cancelled.size(), "回收实例必须取消仍持有的超时任务，否则回收后还会触发超时结算");
        assertFalse(timeouts.containsKey(INSTANCE_WORLD), "回收后不得再持有该世界的超时任务");
        assertFalse(sessions.containsKey(INSTANCE_WORLD), "回收后必须清掉会话代际");
    }

    @Test
    void aLateScheduledCallbackMustStillOwnTheCurrentSession() throws Exception {
        DungeonBukkitTestHarness.Scheduler scheduler = new DungeonBukkitTestHarness.Scheduler();
        DungeonManager manager = DungeonBukkitTestHarness.newManager(scheduler);
        var world = DungeonBukkitTestHarness.world(INSTANCE_WORLD);
        UUID member = UUID.randomUUID();

        DungeonBukkitTestHarness.assignInstance(manager, "timeout-contract", new LinkedHashSet<>(List.of(member)), world);
        Runnable staleCallback = scheduler.callbacks.get(0);
        UUID firstSession = (UUID) DungeonBukkitTestHarness.field(manager, "instanceSessions").get(INSTANCE_WORLD);

        // 换会话：旧回调从此不再拥有当前代际。
        DungeonBukkitTestHarness.assignInstance(manager, "timeout-contract", new LinkedHashSet<>(List.of(member)), world);
        Map<String, Object> sessions = DungeonBukkitTestHarness.field(manager, "instanceSessions");
        Map<String, Object> members = DungeonBukkitTestHarness.field(manager, "instanceMembers");
        UUID secondSession = (UUID) sessions.get(INSTANCE_WORLD);
        assertNotEquals(firstSession, secondSession, "换会话必须换代际");

        // 迟到回调：必须被围栏挡住，不得改动任何会话状态。
        staleCallback.run();
        assertEquals(secondSession, sessions.get(INSTANCE_WORLD),
                "迟到的旧回调必须证明自己仍拥有会话，否则不得动会话代际");
        assertFalse(members.isEmpty(),
                "迟到的旧回调不得把当前会话的成员清空（那会连带提前回收实例）");

        // 当前回调：必须真的推进（清掉超时登记；成员都离线时继续回收）。
        scheduler.callbacks.get(1).run();
        assertFalse(DungeonBukkitTestHarness.field(manager, "instanceTimeouts").containsKey(INSTANCE_WORLD),
                "拥有当前代际的回调必须真的推进超时处理，否则围栏会变成恒真 no-op");
        Object remaining = members.get(INSTANCE_WORLD);
        assertTrue(remaining == null || ((Set<?>) remaining).isEmpty(),
                "成员离线时当前回调必须清掉成员集合");
    }
}
