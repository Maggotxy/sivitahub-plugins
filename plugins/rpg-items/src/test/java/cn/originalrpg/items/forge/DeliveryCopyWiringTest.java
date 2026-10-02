package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站交付路径的接线守卫。
 *
 * <p>被断言的是「真实调用点在哪些文件里」与「调用形态是否绑定操作号」，
 * 数值与标记语义的正确性由 {@link DeliveryCopyRulesTest} 行为级覆盖。</p>
 */
class DeliveryCopyWiringTest {

    @Test
    void everyEscrowProducerBindsItsOutboundPayloadToTheOperation() throws Exception {
        Path root = repositoryRoot();
        for (String file : List.of(
                "ForgeListener.java", "SalvageCommand.java", "UnbindCommand.java", "LegacyCommand.java")) {
            String code = Files.readString(root.resolve(
                    "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/" + file));
            assertTrue(code.contains("EquipmentOperationCoordinator.payload("),
                    file + " 必须经交付副本入口生成出站载荷");
            assertTrue(code.contains(", operationId)"),
                    file + " 出站载荷必须绑定本操作号");
        }
    }

    @Test
    void outboundDeliveryNeverStripsTheEscrowMarker() throws Exception {
        String code = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/EquipmentOperationCoordinator.java"));

        assertFalse(code.contains("EquipmentOperationMarker.clear"),
                "出站路径若清掉标记，交付出去的副本就是一份可再次操作的干净权威物品 —— 重复交付窗口");
        assertTrue(code.contains("DeliveryCopyRules.markerSource"),
                "打标记决策必须走可测试的纯规则，而不是散落在序列化里");
    }

    @Test
    void theMarkerlessOverloadIsNeverUsedBySettlementPaths() throws Exception {
        Path root = repositoryRoot();
        for (String file : List.of(
                "ForgeListener.java", "SalvageCommand.java", "UnbindCommand.java", "LegacyCommand.java")) {
            String code = Files.readString(root.resolve(
                    "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/" + file));
            assertFalse(code.contains("operations.itemPayload("),
                    file + " 不得再走无操作号的 itemPayload 便捷重载");
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
