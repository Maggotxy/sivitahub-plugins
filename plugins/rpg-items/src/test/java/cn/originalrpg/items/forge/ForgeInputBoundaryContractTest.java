package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302 批次：本类原用 {@code Files.readString} 读生产源码做 {@code indexOf} 断言，
 * 现改为「构建/接线契约测试」。
 *
 * <p><b>为什么不是行为测试</b>：被断言的是「预览 / 点击校验 / 最终提交三条路径必须共用
 * 同一个基础校验」以及「点击 / 拖拽 / 关闭三条库存事件都必须校验菜单归属」。生产类是
 * Bukkit 库存监听器（真实 {@code InventoryClickEvent} / {@code Player}），本仓既无
 * {@code MockBukkit} 也无 {@code Mockito}，无法在 JUnit 进程里构造对象去触发这些路径。</p>
 *
 * <p><b>落地方式</b>：断言逐条迁移到声明式规格 {@code scripts/source-contract-specs/all.json}，
 * 由 {@code scripts/check-source-contract.sh} 执行；{@code ordered} 链保证序列在
 * <b>同一个文件</b>里成立。原 {@code indexOf} 链把「helper 定义（文件末尾）」排在三处
 * 调用之前，那是链的写法问题而非语义：语义是「helper 存在 + 三处调用」，因此拆成
 * required（helper 定义）与 ordered（三处调用）。</p>
 */
class ForgeInputBoundaryContractTest {

    private static final String SPEC_KEY =
            "plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/ForgeInputBoundaryContractTest.java";

    @Test
    void previewAndBothCommitChecksRejectAlreadySignedEquipment() throws Exception {
        assertContractHolds("previewAndBothCommitChecksRejectAlreadySignedEquipment");
    }

    @Test
    void everyForgeInventoryMutationChecksTheMenuOwner() throws Exception {
        assertContractHolds("everyForgeInventoryMutationChecksTheMenuOwner");
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
