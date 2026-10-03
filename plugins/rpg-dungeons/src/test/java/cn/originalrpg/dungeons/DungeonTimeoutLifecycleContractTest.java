package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302-RESIDUAL-2：本类原用 {@code Files.readString} 读 {@code DungeonManager.java} 生产源码，
 * 再用 8 处 {@code indexOf} + 先后比较断言「调用点顺序」。那种写法有双重问题：
 * 真实行为被破坏时它未必变红（它只看字符串位置），纯重构时反而无谓变红；
 * 而 {@code readString} 又藏在 {@code source(...)} helper 里，逃过了 W16 门禁的「同一方法体内
 * 直接出现」判据。
 *
 * <p><b>为什么不是行为测试</b>：本类被断言的是「{@code DungeonManager} 里某段调用序列的先后」，
 * 而 {@code DungeonManager} 的构造与运行深度依赖真实 Paper 运行时：构造器就要
 * {@code Bukkit.getWorldContainer()/getWorlds()}、{@code NamespacedKey(Plugin, ...)}、
 * {@code Material.isAir()}（需要 Paper 的 {@code RegistryAccess}），运行还要真世界、真调度器、
 * 真实体。本仓既无 MockBukkit 也无 Mockito，JUnit 进程里装不出这套运行时
 * （W18-A1 实测：装假 Server/Bukkit 门面后仍在 {@code Registry.legacyRegistryFor} 处
 * {@code IllegalStateException: No RegistryAccess implementation found}）。</p>
 *
 * <p><b>落地方式</b>（G-302 要求②）：断言逐条迁移到本模块自己的声明式规格
 * {@code scripts/source-contract-specs/rpg-dungeons.json}（不改 all.json，避免与别的任务抢键），
 * 由 {@code scripts/check-source-contract.sh} 执行；每个测试方法真实执行门禁并绑定同名契约条目，
 * 覆盖不降级（8 处 {@code indexOf} 顺序链转成 {@code ordered}，且保证在同一个文件里成立）。
 * 门禁自身有负向自测（{@code scripts/selftest-gates.sh} §4b），改坏生产代码同样会让本类变红。</p>
 *
 * <p><b>8 处 indexOf 的逐条归属</b>：</p>
 * <ol>
 *   <li>{@code assign >= 0} / {@code cancelOld > assign} / {@code putSession > cancelOld} /
 *       {@code schedule > putSession} → {@link #assignmentCancelsTheOldTimeoutBeforeBindingANewSession()}。</li>
 *   <li>{@code release >= 0} / {@code cancelRelease > release} →
 *       {@link #recyclingAnInstanceCancelsItsActiveTimeoutTask()}。</li>
 *   <li>{@code timeout >= 0} / {@code generationFence > timeout} →
 *       {@link #aLateScheduledCallbackMustStillOwnTheCurrentSession()}。</li>
 * </ol>
 */
class DungeonTimeoutLifecycleContractTest {

    private static final String SPEC_KEY = "plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/"
            + "DungeonTimeoutLifecycleContractTest.java";

    @Test
    void assignmentCancelsTheOldTimeoutBeforeBindingANewSession() throws Exception {
        // 原断言：assign < cancelOld < putSession < schedule。
        // 想证明：换世界/换会话前必须先把旧超时取消，再写入新会话代际，最后才挂捕获该代际的任务。
        assertContractHolds("recycledWorldsCancelOldTimeoutsAndFenceLateCallbacksBySessionId");
    }

    @Test
    void recyclingAnInstanceCancelsItsActiveTimeoutTask() throws Exception {
        // 原断言：release < cancelRelease。
        assertContractHolds("recyclingAnInstanceCancelsItsActiveTimeoutTask");
    }

    @Test
    void aLateScheduledCallbackMustStillOwnTheCurrentSession() throws Exception {
        // 原断言：timeout < generationFence。
        assertContractHolds("aLateScheduledCallbackMustStillOwnTheCurrentSession");
    }

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertContractHolds(String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        Path spec = root.resolve("scripts/source-contract-specs/rpg-dungeons.json");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        assertTrue(Files.isRegularFile(spec), "缺少本模块契约规格: " + spec);
        assertFalse(new String(Files.readAllBytes(spec), StandardCharsets.UTF_8).isBlank(),
                "本模块契约规格不得为空（清空规格等于删断言）");
        Process process = new ProcessBuilder("sh", gate.toString(), SPEC_KEY, method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "源码契约门禁失败（" + method + "）：\n" + output);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
