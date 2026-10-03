package cn.originalrpg.items.forge;

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
 * <p><b>为什么不是行为测试</b>：被断言的不是运行期行为，而是生产代码里「先落 PDC 恢复
 * 信封、再消费输入、再落 playerdata、最后提交出站」这段调用序列的先后，以及「标记过的
 * 托管副本不得被当成权威物品再操作」。生产类是 Bukkit 监听器/指令（真实 {@code Player}、
 * {@code ItemStack} PDC、{@code Bukkit} 调度器），本仓既无 {@code MockBukkit} 也无
 * {@code Mockito}，无法在 JUnit 进程里构造对象去触发这些路径。</p>
 *
 * <p><b>落地方式</b>：断言逐条迁移到声明式规格 {@code scripts/source-contract-specs/all.json}。
 * 原断言跨多个文件（ForgeListener / EquipmentOperationCoordinator / EquipmentSecurity /
 * AuthoritativeItemProcessingListener / UnbindCommand / LegacyCommand / LegacyBlueprintService），
 * 已按文件归属拆成 {@code 主键 / #coordinator / #security / #unbind-legacy / #unbind-legacy-chain /
 * #legacy-chain / #legacy-blueprint} 多个 spec 键；{@code ordered} 链保证序列在
 * <b>同一个文件</b>里成立（等价原 {@code indexOf} 链，包括原来 for 循环逐文件跑的那两条）。</p>
 */
class ForgeOperationIntegrationContractTest {

    private static final String BASE =
            "plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/ForgeOperationIntegrationContractTest.java";

    @Test
    void forgePersistsEscrowBeforeConsumingInputsAndUsesDeterministicOutbox() throws Exception {
        assertContractHolds(BASE, "forgePersistsEscrowBeforeConsumingInputsAndUsesDeterministicOutbox");
    }

    @Test
    void operationMarkerMakesEscrowCopiesUnauthenticAndUnusable() throws Exception {
        assertContractHolds(BASE + "#security", "operationMarkerMakesEscrowCopiesUnauthenticAndUnusable");
    }

    @Test
    void projectileKillsUseTheLaunchWeaponInsteadOfTheCurrentMainHand() throws Exception {
        assertContractHolds(BASE, "projectileKillsUseTheLaunchWeaponInsteadOfTheCurrentMainHand");
    }

    @Test
    void unresolvedDatabaseOutcomeKeepsTheEnvelopeButReleasesOnlyTheLocalGate() throws Exception {
        assertContractHolds(BASE + "#coordinator",
                "unresolvedDatabaseOutcomeKeepsTheEnvelopeButReleasesOnlyTheLocalGate");
    }

    @Test
    void onlyTheCurrentLoginSessionMayClearAndPersistTheEquipmentEnvelope() throws Exception {
        assertContractHolds(BASE + "#coordinator",
                "onlyTheCurrentLoginSessionMayClearAndPersistTheEquipmentEnvelope");
    }

    @Test
    void unbindAndLegacyUseTheSameDurableEscrowProtocolAsForge() throws Exception {
        assertContractHolds(BASE + "#unbind-legacy", "unbindAndLegacyUseTheSameDurableEscrowProtocolAsForge");
        assertContractHolds(BASE + "#unbind-legacy-chain",
                "unbindCommandPersistsEnvelopeBeforeEscrowAndSubmitsAfterSave");
        assertContractHolds(BASE + "#legacy-chain",
                "legacyCommandPersistsEnvelopeBeforeEscrowAndSubmitsAfterSave");
    }

    @Test
    void legacyBlueprintIdentityIsDeterministicInsideThePersistedResult() throws Exception {
        assertContractHolds(BASE + "#legacy-blueprint",
                "legacyBlueprintIdentityIsDeterministicInsideThePersistedResult");
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
