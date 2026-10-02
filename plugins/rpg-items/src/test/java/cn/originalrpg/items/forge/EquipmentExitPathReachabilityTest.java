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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-008 逐路径可达性合同：声明出的每条退出路径，要么有 src/main 生产入口，
 * 要么被明确断言为「当前无入口」并说明了理由。
 *
 * <p>W3-A3 的验收点在这里：{@code DESTROYED} 必须有 src/main 生产入口。
 * W6-A2 之后 {@code SEASON_ATTUNED} 也接了线，但触发者是换季结算，不是玩家指令：
 * 因此在 rpg-items 里它的正确形态是「仍然零入口」，玩家可见面必须继续为空。</p>
 */
class EquipmentExitPathReachabilityTest {
    private static final Path ROOT = repositoryRoot();

    /** C-008 里「调谐」这条退出路径在装备插件里唯一允许出现的位置：状态机自身（经 rpg-api 依赖）。 */
    private static final String LIFECYCLE_ONLY =
            "plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/EquipmentLifecycle.java";

    @Test
    void destroyedHasAProductionEntryPointAndSeasonAttunementStaysOutOfTheEquipmentPlugin() throws IOException {
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

        // W6-A2：赛季调谐已接线，但触发者是换季结算（rpg-core/EquipmentStateLedger#attune），
        // 因此 rpg-items 里它仍然零出现。
        assertFalse(productionReferences("SEASON_ATTUNED").stream()
                        .anyMatch(path -> path.startsWith("plugins/rpg-items/")),
                "赛季调谐没有玩家入口：装备插件里不得出现 SEASON_ATTUNED 分支");
        assertFalse(productionReferences("赛季调谐").stream()
                        .anyMatch(path -> path.startsWith("plugins/rpg-items/")),
                "赛季调谐不在装备插件里落地：状态机的接线登记是唯一允许的提及处");
        // 消费者在 rpg-core：触发者必须是换季结算，且经唯一权威状态机写入。
        assertTrue(productionReferences("SEASON_ATTUNED").stream()
                        .anyMatch(path -> path.endsWith("/EquipmentStateLedger.java")),
                "赛季调谐必须有真实消费者：" + productionReferences("SEASON_ATTUNED"));
    }

    @Test
    void seasonAttunementHasNeitherCommandNorConfigurationSurface() throws IOException {
        // 「没有玩家入口」必须在玩家可见面上也成立：没有指令、没有 config 键、
        // 没有插件注册。只断言 Java 标识符会漏掉「顺手加个 /attune 占位指令」。
        String pluginYml = Files.readString(ROOT.resolve("plugins/rpg-items/src/main/resources/plugin.yml"),
                StandardCharsets.UTF_8);
        for (String forbidden : List.of("attune", "attunement", "赛季调谐")) {
            assertFalse(pluginYml.toLowerCase(java.util.Locale.ROOT).contains(forbidden.toLowerCase(
                            java.util.Locale.ROOT)),
                    "赛季调谐的触发者是换季结算，不得为它注册玩家指令：" + forbidden);
        }
        String config = Files.readString(ROOT.resolve("plugins/rpg-items/src/main/resources/config.yml"),
                StandardCharsets.UTF_8);
        assertFalse(config.contains("attun"),
                "rpg-items/config.yml 不得为赛季调谐预留配置键（开关在 rpg-core）");
        String bootstrap = Files.readString(ROOT.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/RpgItemsPlugin.java"), StandardCharsets.UTF_8);
        assertFalse(bootstrap.contains("Attun"),
                "赛季调谐的触发者在 rpg-core，装备插件启动路径不得注册任何消费者");
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
        // SACRIFICED 仍是预留态；SEASON_ATTUNED 已在 rpg-core 接线（消费者是
        // 换季结算与状态机账本），但装备插件里依然零出现（玩家零入口）。
        assertTrue(wired.get("SEASON_ATTUNED"),
                "赛季调谐必须已有真实触发者（换季结算），否则 C-008 的第五条退出路径仍是纸面条款");
        assertFalse(productionReferences("SEASON_ATTUNED").stream()
                        .anyMatch(path -> path.startsWith("plugins/rpg-items/")),
                "赛季调谐不得在 rpg-items 里出现消费者（触发者是换季结算）");
        assertEquals(List.of(LIFECYCLE_ONLY), productionReferences("SACRIFICED"));
    }

    /**
     * 该状态在本仓 src/main 里是否存在非状态机自身的消费者。
     *
     * <p>W6-A2 起赛季调谐的消费者在 rpg-core（换季结算 + 状态机账本），
     * 因此这里看的是全仓，而不是只看装备插件：只看装备插件会把「已经接线」
     * 误判成「没接线」。</p>
     */
    private static boolean wiredIn(String state) throws IOException {
        return productionReferences(state).stream().anyMatch(path -> !path.equals(LIFECYCLE_ONLY));
    }

    @Test
    void seasonAttunementHasNoPersistedProducerInTheEquipmentPlugin() throws IOException {
        // 没有任何 EquipmentOperationKind 会把装备推进 SEASON_ATTUNED：调谐由换季
        // 结算侧直接消费状态机写入，不经过玩家操作的 outbox，也没有持有态种子。
        for (EquipmentOperationKind kind : EquipmentOperationKind.values()) {
            EquipmentLifecycle.State seed = EquipmentLifecycle.seedForOperation(kind);
            if (kind == EquipmentOperationKind.SEASON_ATTUNEMENT) {
                assertNull(seed, "赛季调谐不是玩家操作，不得有可推断的种子态");
                assertNull(EquipmentLifecycle.afterOperation(kind, EquipmentLifecycle.State.BOUND),
                        "赛季调谐不得经玩家操作 outbox 产出结果态");
            }
            assertFalse(EquipmentLifecycle.afterOperation(kind, seed)
                            == EquipmentLifecycle.State.SEASON_ATTUNED,
                    kind + " 不应把装备直接推进赛季调谐：触发者只能是换季结算");
        }
        String source = Files.readString(ROOT.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/RpgItemsPlugin.java"), StandardCharsets.UTF_8);
        assertFalse(source.contains("SEASON_ATTUNED"),
                "rpg-items 里不得出现赛季调谐分支（触发者在 rpg-core）");
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

    /**
     * C-009 交付通道判据必须失败关闭在「输入被清空」之前。
     *
     * <p>原实现把这条判据写在 {@code held.setAmount(0)} 与 {@code player.saveData()}
     * 之后：一旦口径改成非邮箱通道，异常会在装备已从手上消失、恢复信封已落
     * playerdata 之后抛出，玩家物品凭空消失且没有任何结算记录。判据必须排在
     * 托管编排（{@code operations.stage}）之前，才能在任何状态变更前拒绝。</p>
     */
    @Test
    void theMailboxLaneGateFailsClosedBeforeAnyStateIsMutated() throws IOException {
        String code = Files.readString(ROOT.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/DestroyCommand.java"), StandardCharsets.UTF_8);
        int gate = code.indexOf("DestroyRules.deliveryLane(");
        int stage = code.indexOf("operations.stage(player, request)");
        int consume = code.indexOf("held.setAmount(0)", stage);
        int save = code.indexOf("player.saveData()", consume);
        assertTrue(gate > 0, "销毁必须显式判定交付通道，不能隐式依赖默认值");
        assertTrue(gate < stage,
                "交付通道判据必须在托管编排之前：否则异常会在装备被清空、恢复信封落盘之后才抛出");
        assertTrue(stage < consume && consume < save, "编排 -> 消费输入 -> 保存玩家的顺序不得颠倒");
        // 失败关闭必须是「拒绝并返回」，不能只打日志继续往下走。
        String gateBlock = code.substring(gate, stage);
        assertTrue(gateBlock.contains("return;"), "交付通道不合法时必须直接拒绝本次操作");
        assertFalse(gateBlock.contains("throw new IllegalStateException"),
                "不得用异常终止一个已经暂停了玩家装备的操作，必须走可读的失败关闭分支");
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
