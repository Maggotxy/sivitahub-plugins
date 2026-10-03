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
    void seasonAttunementHasNeitherCommandNorConfigurationSurface() throws Exception {
        // 「没有玩家入口」必须在玩家可见面上也成立：没有指令、没有 config 键、
        // 没有插件注册。只断言 Java 标识符会漏掉「顺手加个 /attune 占位指令」。
        // G-302：跨三个文件（plugin.yml / config.yml / RpgItemsPlugin）的否定断言
        // 只能对源码与清单结构做静态保证 → 迁进声明式规格，由门禁执行
        // （forbidden 的语义 = 不得出现在本键声明的任何文件里，与原逐文件断言等价）。
        assertSourceContractHolds(SEASON_SPEC, "seasonAttunementHasNeitherCommandNorConfigurationSurface");
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
    void seasonAttunementHasNoPersistedProducerInTheEquipmentPlugin() throws Exception {
        // 没有任何 EquipmentOperationKind 会把装备推进 SEASON_ATTUNED：调谐由换季
        // 结算侧直接消费状态机写入，不经过玩家操作的 outbox，也没有持有态种子。
        // 这一半是**真实行为断言**（跑状态机本身，与源码文本无关）—— 保留：
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
        // 另一半（「rpg-items 启动路径里不得出现 SEASON_ATTUNED 分支」）只能对源码
        // 结构做静态保证 → 同一个规格键的 forbidden，由门禁执行。
        assertSourceContractHolds(SEASON_SPEC, "seasonAttunementHasNoPersistedProducerInTheEquipmentPlugin");
    }

    @Test
    void destroyEntryPointFollowsTheSalvageOutboxContract() throws Exception {
        // G-302 行为化：原实现读 DestroyCommand 源码做 indexOf 链 + 三条 contains。
        // 现在改为真跑这条退出路径的**纯规则**（它们就是「销毁到底是不是一条合法退出路径」
        // 的全部判据），并把「调用顺序」与「不得绕过账本」这两点交给声明式门禁。
        assertTrue(EquipmentExitPathRules.isExitPath(EquipmentOperationKind.DESTROY),
                "销毁必须是状态机认可的退出路径，否则 C-008 的第四条退出路径是纸面条款");
        assertTrue(DestroyRules.descendsToTerminal(EquipmentOperationKind.DESTROY),
                "销毁必须被算作「分解到底」");
        assertTrue(DestroyRules.outcomeIsTerminal(EquipmentOperationKind.DESTROY),
                "销毁的结果态必须真的是终态（不是 no-op 操作）");
        assertTrue(DestroyDeliveryRules.lanesAgree(EquipmentOperationKind.DESTROY),
                "销毁产物的交付通道与退回通道必须一致（否则拒绝时原物会被寄走）");
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyRules.deliveryLane(EquipmentOperationKind.DESTROY),
                "销毁产物只能经唯一市场邮箱通道交付（C-009）");
        assertFalse(DestroyGate.allowsTerminalExit(true, true, EquipmentOperationKind.DESTROY),
                "已付费解绑的装备不得直接销毁，必须先重新绑定（否则绕过唯一市场通道）");
        assertTrue(DestroyGate.allowsTerminalExit(true, false, EquipmentOperationKind.DESTROY),
                "已绑定（未解绑）的自制装备可以走销毁");
        assertFalse(DestroyRules.persistedStateIsTerminal(null),
                "账本没有行时不得当成终态，否则预登记行竞争下会重复扣币");

        // 结构约束（调用顺序 + 不得绕过账本）仍然只能对源码做静态保证 → 门禁执行。
        assertSourceContractHolds(DESTROY_COMMAND_SPEC, "destroyEntryPointPersistsEscrowBeforeSubmitting");
        assertSourceContractHolds(DESTROY_COMMAND_SPEC, "destroyCommandUsesAUniqueAuditableIdempotencyKey");
    }

    private static final String SEASON_SPEC =
            "plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/"
                    + "EquipmentExitPathReachabilityTest.java#season-attunement";

    private static final String DESTROY_COMMAND_SPEC =
            "plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/"
                    + "EquipmentExitPathReachabilityTest.java#destroy-command";

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertSourceContractHolds(String specKey, String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(), specKey, method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "源码契约门禁失败（" + specKey + " :: " + method + "）：\n" + output);
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
    void theMailboxLaneGateFailsClosedBeforeAnyStateIsMutated() throws Exception {
        // G-302 行为化：原实现读 DestroyCommand 源码比对交付通道判据与托管编排的先后。
        // 判据本身（哪条通道、是否必须在状态变更前拒绝）是纯规则，真跑一遍：
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyRules.deliveryLane(EquipmentOperationKind.DESTROY),
                "销毁必须显式判定交付通道，不能隐式依赖默认值");
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyRules.refundLane(EquipmentOperationKind.DESTROY),
                "退回通道必须与结果通道走同一条托管面");
        // 非终态操作不产出物品 ⇒ 不进邮箱（判据必须真的区分两种通道，不是恒真）。
        assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY,
                DestroyDeliveryRules.laneFor(cn.originalrpg.api.model.EquipmentOperationKind.REFINEMENT));

        // 「判据必须排在 operations.stage 之前，且拒绝必须 return 而不是 throw」这两条
        // 只能对源码结构做静态保证 → 门禁执行。
        assertSourceContractHolds(DESTROY_COMMAND_SPEC, "mailboxLaneGateFailsClosedBeforeAnyStateIsMutated");
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
