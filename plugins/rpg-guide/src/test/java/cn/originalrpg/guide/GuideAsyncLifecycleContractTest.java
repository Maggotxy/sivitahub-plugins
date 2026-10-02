package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302 第二批：本类原用 {@code Files.readString} 读生产源码做字符串/{@code indexOf} 断言，
 * 现改为「构建/接线契约测试」。
 *
 * <p><b>为什么不是行为测试</b>：被断言的不是运行期行为，而是生产代码里某段调用序列的先后，
 * 或某个片段必须/不得出现。生产类是 Bukkit 插件门面（{@code JavaPlugin} / 需要真实 Paper
 * 服务器），本仓既无 {@code MockBukkit} 也无 {@code Mockito}，无法在 JUnit 进程里构造对象
 * 去触发这些路径；其中部分约束（新写的回调/菜单必须走统一分发器）在运行期不可观测。</p>
 *
 * <p><b>落地方式</b>（G-302 要求②「移到 scripts/ 下的静态检查」）：断言逐条迁移到声明式规格
 * {@code scripts/source-contract-specs/all.json}，由 {@code scripts/check-source-contract.sh}
 * 执行；每个测试方法真实执行门禁并绑定到同名契约条目，覆盖不降级。门禁自身有负向自测
 * （{@code scripts/selftest-gates.sh} §4），改坏生产代码同样会让本类变红。</p>
 */
class GuideAsyncLifecycleContractTest {

    private static final String SPEC_KEY = "plugins/rpg-guide/src/test/java/cn/originalrpg/guide/GuideAsyncLifecycleContractTest.java";

    @Test
    void guideUiAndStarterCallbacksRejectOldConnectionsAndShutdown() throws Exception {
        assertContractHolds("guideUiAndStarterCallbacksRejectOldConnectionsAndShutdown");
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
