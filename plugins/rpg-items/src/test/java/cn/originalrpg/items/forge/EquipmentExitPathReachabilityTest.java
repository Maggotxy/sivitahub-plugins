package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.rules.EquipmentLifecycle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-008 逐路径可达性合同：声明出的每条退出路径，要么有 src/main 生产入口，
 * 要么被明确断言为「当前无入口」并说明了理由。
 *
 * <p>W3-A3 的验收点就在这里：{@code DESTROYED} 这条路径必须真的接了线，
 * 而 {@code SEASON_ATTUNED} 属于赛季终局内容，当前 Phase 下确实没有触发者——
 * 测试断言的是「没有」，不是假装它有。</p>
 */
class EquipmentExitPathReachabilityTest {
    private static final Path ROOT = repositoryRoot();

    @Test
    void destroyedHasAProductionEntryPointAndSeasonAttunementStillDoesNot() throws IOException {
        List<String> producers = productionReferences("DESTROYED");
        assertTrue(producers.stream().anyMatch(path -> path.contains("/forge/DestroyCommand.java")),
                "DESTROYED 必须有 src/main 生产入口，实际命中：" + producers);
        assertTrue(producers.stream().anyMatch(path -> path.contains("/forge/DestroyRules.java")));

        // 赛季调谐至今没有任何 src/main 消费者：只命中状态机自身与审计/账本读取方。
        List<String> attuned = productionReferences("SEASON_ATTUNED");
        assertEquals(List.of("plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java"),
                attuned, "SEASON_ATTUNED 若出现新消费者，必须同时补上内容管线接线与测试");
    }

    @Test
    void seasonAttunementHasNoPersistedProducerInTheEquipmentPlugin() throws IOException {
        // 状态机不会为 SEASON_ATTUNED 生成任何结果态，因此它无法被任何玩家操作触发。
        for (EquipmentOperationKind kind : EquipmentOperationKind.values()) {
            EquipmentLifecycle.State outcome = EquipmentLifecycle.afterOperation(
                    kind, EquipmentLifecycle.seedForOperation(kind));
            assertFalse(outcome == EquipmentLifecycle.State.SEASON_ATTUNED,
                    kind + " 不应把装备直接推进赛季调谐：该路径尚无生产入口");
        }
        String source = Files.readString(ROOT.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/RpgItemsPlugin.java"), StandardCharsets.UTF_8);
        assertFalse(source.contains("SEASON_ATTUNED"),
                "rpg-items 里不得出现未接线的赛季调谐分支");
    }

    @Test
    void destroyEntryPointFollowsTheSalvageOutboxContract() throws IOException {
        String code = Files.readString(ROOT.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/DestroyCommand.java"), StandardCharsets.UTF_8);
        int stage = code.indexOf("operations.stage(player, request)");
        int consume = code.indexOf("held.setAmount(0)", stage);
        int save = code.indexOf("player.saveData()", consume);
        int submit = code.indexOf("operations.submit(player, request", save);
        assertTrue(stage > 0 && consume > stage && save > consume && submit > save,
                "销毁必须先落 PDC 恢复信封，再移除输入，再保存玩家，最后提交出站");
        assertTrue(code.contains("EquipmentOperationKind.DESTROY"));
        assertTrue(code.contains("BigDecimal.ZERO.setScale(2)"), "首次输入只确认意图，不能收费");
        assertTrue(code.contains("\"destroy:\" + operationId"), "幂等键必须唯一且可审计");
        assertFalse(code.contains("economy.burn("), "销毁不得绕过账本直接铸币或扣币");
    }

    /** src/main 里出现该标识符的文件（相对仓根的 / 分隔路径）。 */
    private static List<String> productionReferences(String identifier) throws IOException {
        List<String> hits = new ArrayList<>();
        Path main = ROOT.resolve("plugins");
        try (var modules = Files.list(main)) {
            for (Path module : modules.toList()) {
                Path sources = module.resolve("src/main");
                if (!Files.isDirectory(sources)) continue;
                try (var files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        if (Files.readString(file, StandardCharsets.UTF_8).contains(identifier)) {
                            hits.add(ROOT.relativize(file).toString().replace('\\', '/'));
                        }
                    }
                }
            }
        }
        return hits.stream().sorted().toList();
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
