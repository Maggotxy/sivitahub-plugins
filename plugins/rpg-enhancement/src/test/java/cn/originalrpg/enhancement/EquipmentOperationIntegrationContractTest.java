package cn.originalrpg.enhancement;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302 批次：本类原用 {@code Files.readString} 读生产源码做字符串/{@code indexOf} 断言，
 * 现改为「构建/接线契约测试」。
 *
 * <p><b>为什么不是行为测试</b>：被断言的不是运行期行为，而是生产代码里「先落恢复信封、
 * 再消费输入、再落 playerdata、最后提交出站」这段调用序列的先后，以及「撤回事件不得
 * 解除已提交操作的本地闸门」。生产类 {@code RefineCommand} / {@code LimitBreakCommand}
 * 是 Bukkit 指令门面（真实 {@code Player}、{@code ItemStack} PDC、{@code EquipmentService}
 * 与数据库出站），本仓既无 {@code MockBukkit} 也无 {@code Mockito}，无法在 JUnit 进程里
 * 构造对象去触发这些路径。</p>
 *
 * <p><b>落地方式</b>：断言逐条迁移到声明式规格 {@code scripts/source-contract-specs/all.json}。
 * 原断言跨两个文件（Refine / LimitBreak），已按文件归属拆成 {@code #refine} 与
 * {@code #limit-break} 两个 spec 键；{@code ordered} 链保证序列在<b>同一个文件</b>里成立
 * （等价原 {@code indexOf} 链）。判据覆盖不降级。</p>
 */
class EquipmentOperationIntegrationContractTest {

    private static final String REFINE_SPEC_KEY = "plugins/rpg-enhancement/src/test/java/"
            + "cn/originalrpg/enhancement/EquipmentOperationIntegrationContractTest.java#refine";
    private static final String LIMIT_BREAK_SPEC_KEY = "plugins/rpg-enhancement/src/test/java/"
            + "cn/originalrpg/enhancement/EquipmentOperationIntegrationContractTest.java#limit-break";

    @Test
    void refinementAndLimitBreakUseDurableOutboxInsteadOfSplitBurnRefund() throws Exception {
        assertContractHolds(REFINE_SPEC_KEY, "refinementUsesDurableOutboxInsteadOfSplitBurnRefund");
        assertContractHolds(LIMIT_BREAK_SPEC_KEY, "limitBreakUsesDurableOutboxInsteadOfSplitBurnRefund");
    }

    @Test
    void submittedGateSurvivesAuthenticationRevocation() throws Exception {
        assertContractHolds(REFINE_SPEC_KEY, "submittedGateSurvivesAuthenticationRevocation");
        assertContractHolds(LIMIT_BREAK_SPEC_KEY, "limitBreakSubmittedGateSurvivesAuthenticationRevocation");
    }

    @Test
    void refinedEquipmentIsSerializedAfterSigningAndLoreRefresh() throws Exception {
        assertContractHolds(REFINE_SPEC_KEY, "refinedEquipmentIsSignedInsideTheSharedCommitPath");
        assertContractHolds(REFINE_SPEC_KEY, "refinedResultIsBuiltBeforeTheLiveItemIsEscrowed");
    }

    @Test
    void durableEnvelopePrecedesEveryEnhancementLocalGate() throws Exception {
        assertContractHolds(REFINE_SPEC_KEY, "durableEnvelopePrecedesEveryEnhancementLocalGate");
        assertContractHolds(LIMIT_BREAK_SPEC_KEY, "limitBreakDurableEnvelopePrecedesEveryLocalGate");
    }

    @Test
    void completionFeedbackCannotTargetAReplacedLoginSession() throws Exception {
        assertContractHolds(REFINE_SPEC_KEY, "completionFeedbackCannotTargetAReplacedLoginSession");
        assertContractHolds(LIMIT_BREAK_SPEC_KEY, "limitBreakCompletionFeedbackCannotTargetAReplacedLoginSession");
    }

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertContractHolds(String specKey, String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(), specKey, method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "源码契约门禁失败（" + specKey + " :: " + method + "）：\n" + output);
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
