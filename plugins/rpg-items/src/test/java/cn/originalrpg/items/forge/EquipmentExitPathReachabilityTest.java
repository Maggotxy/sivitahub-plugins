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

    /** C-008 里「调谐」这条退出路径在当前 Phase 下唯一允许出现的位置：状态机自身。 */
    private static final String LIFECYCLE_ONLY =
            "plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java";

    @Test
    void destroyedHasAProductionEntryPointAndSeasonAttunementStillDoesNot() throws IOException {
        List<String> producers = productionReferences("DESTROYED");
        assertTrue(producers.stream().anyMatch(path -> path.contains("/forge/DestroyCommand.java")),
                "DESTROYED 必须有 src/main 生产入口，实际命中：" + producers);
        assertTrue(producers.stream().anyMatch(path -> path.contains("/forge/DestroyRules.java")));
        List<String> delivery = productionReferences("ESCROW_MAILBOX");
        assertTrue(delivery.stream().anyMatch(path -> path.contains("/forge/DestroyDeliveryRules.java")),
                "销毁产物的交付通道必须也有 src/main 消费者，否则终态路径可能绕过唯一市场邮箱。"
                        + "实际命中：" + delivery);
        assertTrue(delivery.stream().anyMatch(path -> path.contains("/forge/DestroyCommand.java")),
                "销毁指令必须显式走邮箱通道，实际命中：" + delivery);

        // 赛季调谐至今没有任何 src/main 消费者：只命中状态机自身。
        assertEquals(List.of(LIFECYCLE_ONLY), productionReferences("SEASON_ATTUNED"),
                "SEASON_ATTUNED 若出现新消费者，必须同时补上内容管线接线与测试");
        // 连注释里提及也不许出现：状态机的 TODO 是唯一允许的登记处。
        assertEquals(List.of(LIFECYCLE_ONLY), productionReferences("赛季调谐"),
                "赛季调谐未接线，不得先在 rpg-items 里写出占位分支或占位配置");
    }

    @Test
    void seasonAttunementHasNeitherCommandNorConfigurationSurface() throws IOException {
        // 「没有触发者」必须在玩家可见面上也成立：没有指令、没有 config 键、
        // 没有插件注册。只断言 Java 标识符会漏掉「顺手加个 /attune 占位指令」。
        String pluginYml = Files.readString(ROOT.resolve("plugins/rpg-items/src/main/resources/plugin.yml"),
                StandardCharsets.UTF_8);
        for (String forbidden : List.of("attune", "attunement", "赛季调谐")) {
            assertFalse(pluginYml.toLowerCase(java.util.Locale.ROOT).contains(forbidden.toLowerCase(
                            java.util.Locale.ROOT)),
                    "rpg-items/plugin.yml 不得为未接线的赛季调谐注册指令：" + forbidden);
        }
        String config = Files.readString(ROOT.resolve("plugins/rpg-items/src/main/resources/config.yml"),
                StandardCharsets.UTF_8);
        assertFalse(config.contains("attun"),
                "rpg-items/config.yml 不得为未接线的赛季调谐预留配置键");
        String bootstrap = Files.readString(ROOT.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/RpgItemsPlugin.java"), StandardCharsets.UTF_8);
        assertFalse(bootstrap.contains("Attun"),
                "未接线功能不得在插件启动路径里注册消费者");
    }

    @Test
    void everyDeclaredExitPathIsEitherWiredOrExplicitlyUnwired() throws IOException {
        // C-008 声明了 5 条退出路径。逐条给出「已接线 / 当前无入口」的机械判定，
        // 让「只声明没接线」这件事每次都必须在测试里显式承认一次。
        java.util.Map<String, Boolean> wired = new java.util.LinkedHashMap<>();
        wired.put("SALVAGED", wiredIn("SALVAGED"));
        wired.put("SACRIFICED", wiredIn("SACRIFICED"));
        wired.put("LEGACY_EXTRACTED", wiredIn("LEGACY_EXTRACTED"));
        wired.put("DESTROYED", wiredIn("DESTROYED"));
        wired.put("SEASON_ATTUNED", wiredIn("SEASON_ATTUNED"));

        assertTrue(wired.get("SALVAGED"), "分解必须已接线");
        assertTrue(wired.get("LEGACY_EXTRACTED"), "传承必须已接线");
        assertTrue(wired.get("DESTROYED"), "明确销毁必须已接线");
        // SACRIFICED 与 SEASON_ATTUNED 允许未接线，但必须是「唯一命中状态机自身」，
        // 而不是散落在一个半成品实现里。
        assertFalse(wired.get("SEASON_ATTUNED"), "赛季调谐当前无入口（见 EquipmentLifecycle 的 TODO）");
        assertEquals(List.of(LIFECYCLE_ONLY), productionReferences("SACRIFICED"));
    }

    /** 该状态在本仓 src/main 里是否存在非状态机自身的消费者。 */
    private static boolean wiredIn(String state) throws IOException {
        return productionReferences(state).stream().anyMatch(path -> !path.equals(LIFECYCLE_ONLY));
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
