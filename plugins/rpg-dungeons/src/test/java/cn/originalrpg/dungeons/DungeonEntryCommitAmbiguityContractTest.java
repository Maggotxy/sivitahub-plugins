package cn.originalrpg.dungeons;

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
 * <p><b>为什么不是行为测试</b>：被断言的不是运行期行为，而是生产代码里某段调用序列的先后
 * （入场提交 → 歧义消解 → 派对状态 → 回滚）与「某段代码里不得出现某个片段」。生产类
 * {@code DungeonCommand} 依赖真实 {@code Player}/{@code World}/{@code Plugin} 与
 * {@code Bukkit.getScheduler()}，本仓既无 {@code MockBukkit} 也无 {@code Mockito}
 * （见 {@code AttackerBonusWiringTest} 的假实体上限：只够跑纯数值结算，跑不了传送与
 * 异步提交），无法在 JUnit 进程里构造对象去触发这些路径。</p>
 *
 * <p><b>落地方式</b>：断言逐条迁移到声明式规格 {@code scripts/source-contract-specs/all.json}，
 * 由 {@code scripts/check-source-contract.sh} 执行；{@code ordered} 链保证序列在
 * <b>同一个文件</b>里成立（等价原 {@code indexOf} 链）。判据覆盖不降级。</p>
 */
class DungeonEntryCommitAmbiguityContractTest {

    /** DungeonCommand.java 的契约（跨文件作用域已按文件归属拆键）。 */
    private static final String COMMAND_SPEC = "plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/"
            + "DungeonEntryCommitAmbiguityContractTest.java#dungeon-command";
    /** DungeonManager.java 的契约。 */
    private static final String MANAGER_SPEC = "plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/"
            + "DungeonEntryCommitAmbiguityContractTest.java#dungeon-manager";

    @Test
    void ambiguousCommitQueriesDurableStateBeforeRefundingOrConsumingTickets() throws Exception {
        assertContractHolds(COMMAND_SPEC,
                "ambiguousCommitQueriesDurableStateBeforeRefundingOrConsumingTickets");
    }

    @Test
    void instanceIsHeldAcrossTeleportAndCommitDisambiguation() throws Exception {
        assertContractHolds(COMMAND_SPEC, "instanceIsHeldAcrossTeleportAndCommitDisambiguation");
        assertContractHolds(MANAGER_SPEC, "entrySettlementHoldSurvivesUntilTeleportAndCommitFinish");
        assertContractHolds(MANAGER_SPEC, "recyclingAnInstanceFailsClosedOnAnyRemainingHold");
    }

    @Test
    void hiddenTicketIsDurablyEscrowedBeforeInventoryRemovalAndTeleport() throws Exception {
        assertContractHolds(COMMAND_SPEC, "hiddenTicketIsDurablyEscrowedBeforeInventoryRemovalAndTeleport");
    }

    @Test
    void partialPartyEscrowFailureKeepsCandidatesAvailableForImmediateReconciliation() throws Exception {
        assertContractHolds(COMMAND_SPEC,
                "partialPartyEscrowFailureKeepsCandidatesAvailableForImmediateReconciliation");
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
