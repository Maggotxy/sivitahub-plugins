package cn.originalrpg.market;

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
 * <p><b>为什么不是行为测试</b>：被断言的是生产代码里几段调用序列的先后 —— 受信邮箱
 * 反序列化必须先清掉旧的装备托管标记再给玩家、失败清单标记只能用当前登录句柄清除、
 * 登录对账必须等未提交的挂牌准备完成、插件关闭必须清掉全部进程内闸门。生产类是
 * Bukkit 指令/事件监听器（真实 {@code Player}、{@code ItemStack} PDC、异步市场服务），
 * 本仓既无 {@code MockBukkit} 也无 {@code Mockito}，无法在 JUnit 进程里构造对象去
 * 触发这些路径。</p>
 *
 * <p><b>落地方式</b>：断言逐条迁移到声明式规格 {@code scripts/source-contract-specs/all.json}。
 * 原来把 {@code completePreparation} 与 {@code recoverPreparedListings} 两个方法体的锚点
 * 混在一条 {@code indexOf} 链里（跨方法体的链在语义上就不成立），现按方法拆成多条
 * {@code ordered} 契约，覆盖不降级。</p>
 */
class MailboxDeliveryIntegrationContractTest {

    private static final String COMMAND =
            "plugins/rpg-market/src/test/java/cn/originalrpg/market/MailboxDeliveryIntegrationContractTest.java#market-command";
    private static final String PLUGIN =
            "plugins/rpg-market/src/test/java/cn/originalrpg/market/MailboxDeliveryIntegrationContractTest.java#market-plugin";

    @Test
    void trustedMailboxBoundaryClearsLegacyEquipmentEscrowLockBeforeDeliveryReceipt() throws Exception {
        assertContractHolds(COMMAND,
                "trustedMailboxBoundaryClearsLegacyEquipmentEscrowLockBeforeDeliveryReceipt");
    }

    @Test
    void staleListingCallbackCannotSaveOverANewerLoginAndOrphanMarkersAreRecovered() throws Exception {
        assertContractHolds(COMMAND,
                "staleListingCallbackCannotSaveOverANewerLoginAndOrphanMarkersAreRecovered");
        assertContractHolds(COMMAND,
                "loginReconciliationWaitsForAnUncommittedPreparationThenClearsOrphans");
        assertContractHolds(COMMAND, "readyRequiresTheExactAuthenticatedHandle");
        assertContractHolds(COMMAND, "staleDisconnectMustNotRevokeANewerAuthenticatedHandle");
    }

    @Test
    void pluginDisableClosesEveryProcessLocalMarketGate() throws Exception {
        assertContractHolds(COMMAND, "pluginDisableClosesEveryProcessLocalMarketGate");
        assertContractHolds(PLUGIN, "pluginDisableClosesTheMarketCommandGate");
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
