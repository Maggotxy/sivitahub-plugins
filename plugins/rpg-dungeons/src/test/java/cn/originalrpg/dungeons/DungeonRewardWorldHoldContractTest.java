package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302 批次：本类原用 {@code Files.readString} 读生产源码做 {@code indexOf} 顺序断言，
 * 现改为「构建/接线契约测试」。
 *
 * <p><b>为什么不是行为测试</b>：被断言的是生产代码里「回收实例之前必须先确认奖励意图
 * 已持久化」的调用序列先后。生产类 {@code DungeonManager} 是 Bukkit 插件门面
 * （{@code JavaPlugin} / 真实 {@code World} / 调度器），本仓既无 {@code MockBukkit}
 * 也无 {@code Mockito}，无法在 JUnit 进程里构造对象去触发这些路径。</p>
 *
 * <p><b>落地方式</b>：断言逐条迁移到声明式规格 {@code scripts/source-contract-specs/all.json}，
 * 由 {@code scripts/check-source-contract.sh} 执行；{@code ordered} 链保证序列在
 * <b>同一个文件</b>里成立。原断言里跨多个方法体的顺序链已按方法切分成三条契约
 * （回收持有 / 超时持有 / 持久化意图完成顺序），语义不降级。</p>
 */
class DungeonRewardWorldHoldContractTest {

    private static final String SPEC_KEY =
            "plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonRewardWorldHoldContractTest.java";

    @Test
    void emptyInstanceCannotRecycleBeforeItsRewardIntentIsDurableAndClosed() throws Exception {
        assertContractHolds("emptyInstanceCannotRecycleBeforeItsRewardIntentIsDurableAndClosed");
    }

    @Test
    void sessionTimeoutObeysTheSameRewardRecycleHold() throws Exception {
        assertContractHolds("sessionTimeoutObeysTheSameRewardRecycleHold");
    }

    @Test
    void durableIntentIsCompletedBeforeTheRecycleHoldIsRemoved() throws Exception {
        assertContractHolds("durableIntentIsCompletedBeforeTheRecycleHoldIsRemoved");
    }

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertContractHolds(String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(), SPEC_KEY, method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(), "源码契约门禁失败（" + method + "）：\n" + output);
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
