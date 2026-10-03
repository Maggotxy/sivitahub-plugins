package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.SeasonAttunementRecord;
import cn.originalrpg.api.rules.EquipmentLifecycle;
import cn.originalrpg.api.rules.SeasonAttunementRules;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-008 逐路径可达性合同：声明出的每条退出路径，要么有 src/main 生产入口，
 * 要么被明确断言为「当前无入口」并说明了理由。
 *
 * <p>W3-A3 的验收点在这里：{@code DESTROYED} 必须有 src/main 生产入口。
 * W6-A2 之后 {@code SEASON_ATTUNED} 也接了线，但触发者是换季结算，不是玩家指令：
 * 因此在 rpg-items 里它的正确形态是「仍然零入口」，玩家可见面必须继续为空。</p>
 *
 * <p><b>W19 改造（G-302 最后一个已知残留）</b>：本类原有一个
 * {@code productionReferences(identifier)} helper —— 它遍历 {@code plugins/*&#47;src/main}，
 * 用 {@code Files.readString(...).contains(identifier)} 统计「哪些生产文件提到了某个标识符」，
 * 两个 {@code @Test} 直接依赖其结果。那种写法有两个问题：</p>
 * <ul>
 *   <li>判据太弱：不是「有入口」而是「源码里出现过这个词」（注释里出现过就算）。</li>
 *   <li>G-302 口径上不允许：它是「读本仓生产源码 + 源码字符串断言」，
 *       且 readString 藏在 helper 里，W16 判据漏判、直到 W18 补强判据后才暴露。</li>
 * </ul>
 * <p>W19 逐条处置（不批量替换）：</p>
 * <ol>
 *   <li><b>行为化（真跑）</b>：
 *       <ul>
 *         <li>「每条退出路径要么已接线、要么明确无入口」→ 真跑
 *             {@link EquipmentLifecycle} / {@link EquipmentExitPathRules} /
 *             {@link DestroyRules}（枚举全部 kind × 全部状态），不再数源码里的出现次数。</li>
 *         <li>「rpg-items 里没有任何玩家操作会把装备推进 SEASON_ATTUNED」→ 真跑
 *             {@code seedForOperation} / {@code afterOperation} 的 8×12 组合，
 *             并断言唯一可达该终态的写入者是 rpg-api 的 rpg-core 侧账本，
 *             而不是装备插件的操作 outbox。</li>
 *         <li>「玩家可见面为零」→ 真解析打包后的 {@code plugin.yml} / {@code config.yml}
 *             （与生产同一份 SnakeYAML + 同一份口径），不再逐行读源码文本。</li>
 *       </ul>
 *   </li>
 *   <li><b>迁进声明式源码契约</b>（只能静态保证；规格文件里每条都带
 *       {@code _why_static} / {@code _migration} 说明）：
 *       「标识符出现在哪些 src/main 文件」这一形态本身 —— 包括「唯一权威状态机之外
 *       还必须有真实消费者」与「装备插件里零出现」的跨插件零出现断言 ——
 *       逐条落成 per-file 拆键 + {@code ordered} 链，由
 *       {@code scripts/check-source-contract.sh} 真实执行。{@code productionReferences}
 *       已删除，本类不再扫描生产源码。</li>
 * </ol>
 */
class EquipmentExitPathReachabilityTest {

    /** 规格键前缀：本模块自己的规格文件（不抢 all.json 的键）。 */
    private static final String SPEC_CLASS =
            "plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/"
                    + "EquipmentExitPathReachabilityTest.java";

    /**
     * C-008 的退出路径枚举（与 {@link EquipmentLifecycle#isExitPath} 的声明同集合）。
     * 逐条跟踪「有没有真实入口」，而不是靠一次 grep 下结论。
     */
    private static final List<EquipmentLifecycle.State> DECLARED_EXIT_PATHS = List.of(
            EquipmentLifecycle.State.SALVAGED,
            EquipmentLifecycle.State.SACRIFICED,
            EquipmentLifecycle.State.LEGACY_EXTRACTED,
            EquipmentLifecycle.State.SEASON_ATTUNED,
            EquipmentLifecycle.State.DESTROYED);

    @Test
    void destroyedHasAProductionEntryPointAndSeasonAttunementStaysOutOfTheEquipmentPlugin() throws Exception {
        // ① 行为化：销毁这条退出路径真的接在玩家操作上 —— 真跑规则，不看源码文本。
        assertTrue(EquipmentExitPathRules.isExitPath(EquipmentOperationKind.DESTROY),
                "销毁必须是状态机认可的退出路径，否则 C-008 的第四条退出路径是纸面条款");
        assertTrue(DestroyRules.outcomeIsTerminal(EquipmentOperationKind.DESTROY),
                "销毁的结果态必须真的是终态（不是 no-op 操作）");
        assertTrue(DestroyRules.persistedStateIsTerminal(
                        EquipmentLifecycle.State.DESTROYED.name()),
                "账本里写入 DESTROYED 必须被认成终态，否则终态守卫会放它回世界");
        // ② 行为化：交付通道只有一条 —— 与 /salvage 共用市场邮箱（C-009）。
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyRules.deliveryLane(EquipmentOperationKind.DESTROY),
                "销毁产物的交付通道必须与分解一致（唯一市场邮箱）");

        // ③ 行为化：rpg-items 的玩家操作 outbox 里没有任何一种会产出 SEASON_ATTUNED。
        for (EquipmentOperationKind kind : EquipmentOperationKind.values()) {
            assertFalse(EquipmentLifecycle.afterOperation(kind, EquipmentLifecycle.seedForOperation(kind))
                            == EquipmentLifecycle.State.SEASON_ATTUNED,
                    kind + " 不得把装备直接推进赛季调谐：触发者只能是换季结算");
        }
        assertNull(EquipmentLifecycle.seedForOperation(EquipmentOperationKind.SEASON_ATTUNEMENT),
                "赛季调谐没有可推断的种子态，因此它不可能经玩家操作进入");
        // ④ 行为化：唯一权威写入者（rpg-core 账本）确实存在且给出调谐结果态。
        assertTrue(SeasonAttunementRules.outcomeIsAttuned(SeasonAttunementRecord.REQUIRED_STATE),
                "调谐结果态必须与 C-008 登记的 SEASON_ATTUNED 一致");
        assertTrue(SeasonAttunementRules.TRIGGER.contains("SeasonSettlement"),
                "调谐的唯一触发者必须是换季结算（C-010），实际：" + SeasonAttunementRules.TRIGGER);

        // ⑤ 只能静态保证的那一半：「装备插件里零出现 + 唯一权威状态机之外必须有真实消费者」
        //    （跨插件「零出现」断言没有运行期形态）→ 声明式契约，由门禁真实执行。
        assertSourceContractHolds("season-attunement-consumers", "seasonAttunementStaysOutOfTheEquipmentPlugin");
    }

    @Test
    void seasonAttunementHasNeitherCommandNorConfigurationSurface() throws Exception {
        // G-302 行为化：原来逐行读 plugin.yml / config.yml / RpgItemsPlugin.java 的源码文本，
        // 只为回答「有没有 /attune 指令、有没有 attune 配置键」。
        // 现在**真解析打包后的清单**（与生产同一份 SnakeYAML、同一份规则），
        // 断言任何命令名、别名、子键都不带调谐词根；这比读源码行更接近玩家可见面。
        Map<?, ?> plugin = packagedYaml("plugin.yml");
        List<String> commandSurface = new ArrayList<>();
        collectKeys(plugin.get("commands"), commandSurface);
        assertFalse(commandSurface.stream().anyMatch(EquipmentExitPathReachabilityTest::mentionsAttunement),
                "/attune 之类的玩家指令不得存在，实际命令面：" + commandSurface);
        assertTrue(commandSurface.contains("destroy"),
                "销毁必须仍然是指令面的一部分（本断言不是恒真），实际：" + commandSurface);

        Map<?, ?> config = packagedYaml("config.yml");
        List<String> configSurface = new ArrayList<>();
        collectKeys(config, configSurface);
        assertFalse(configSurface.stream().anyMatch(EquipmentExitPathReachabilityTest::mentionsAttunement),
                "调谐不得有配置键，实际配置面：" + configSurface);
        assertTrue(configSurface.contains("destroy.confirmation-window-seconds"),
                "销毁的配置键必须仍然存在（本断言不是恒真），实际：" + configSurface);

        // 源码层的那一半（RpgItemsPlugin.java 里不得出现调谐分支）只能静态保证 → 门禁执行。
        assertSourceContractHolds("season-attunement", "seasonAttunementHasNeitherCommandNorConfigurationSurface");
    }

    @Test
    void everyDeclaredExitPathIsEitherWiredOrExplicitlyUnwired() throws Exception {
        // C-008 声明了 5 条退出路径。原实现逐条数「源码里出现过几次 SEASON_ATTUNED/SALVAGED」，
        // 结论依赖注释与字符串。现在改为**真跑状态机**回答同一个问题：
        // 「这条路径在本仓有没有一个真实的写入者（经状态机认可的迁移）」。
        assertEquals(DECLARED_EXIT_PATHS.size(), declaredExitPathCount(),
                "退出路径枚举必须与状态机声明一致（多一条少一条都要在这里显式承认）");
        for (EquipmentLifecycle.State path : DECLARED_EXIT_PATHS) {
            assertTrue(EquipmentLifecycle.isExitPath(path), path + " 必须被状态机认成退出路径");
        }

        // 已接线：从 BOUND（首次拥有即绑定）出发，存在生产者操作把它推进该终态。
        assertTrue(isWired(EquipmentLifecycle.State.SALVAGED), "分解必须已接线（/salvage）");
        assertTrue(isWired(EquipmentLifecycle.State.LEGACY_EXTRACTED), "传承必须已接线（/legacy）");
        assertTrue(isWired(EquipmentLifecycle.State.DESTROYED), "明确销毁必须已接线（/destroy）");
        // 拒绝路径不得被算成「已接线」：已付费解绑的装备必须被门禁拦下。
        assertFalse(DestroyGate.allowsTerminalExit(true, true, EquipmentOperationKind.DESTROY),
                "已付费解绑的装备不得直接销毁，必须先重新绑定（否则绕过唯一市场通道）");
        assertTrue(DestroyGate.allowsTerminalExit(true, false, EquipmentOperationKind.DESTROY),
                "已绑定（未解绑）的自制装备可以走销毁（本断言不是恒真）");

        // 赛季调谐：经状态机可达（BOUND/UNBOUND → SEASON_ATTUNED），但**不是玩家操作**，
        // 因此不能算「经玩家操作 outbox 接线」；它的真实触发者是换季结算（rpg-core）。
        assertTrue(EquipmentLifecycle.isValidTransition(
                        EquipmentLifecycle.State.BOUND, EquipmentLifecycle.State.SEASON_ATTUNED),
                "赛季调谐必须是状态机允许的迁移，否则第五条退出路径是纸面条款");
        assertTrue(SeasonAttunementRules.attunable(EquipmentLifecycle.State.BOUND),
                "绑定态装备必须可被换季结算调谐");
        assertFalse(wiredByPlayerOperation(EquipmentLifecycle.State.SEASON_ATTUNED),
                "赛季调谐不得有玩家操作生产者（触发者只能是换季结算）");

        // 预留态：SACRIFICED 在状态机里可达，但本仓没有任何 kind 会产出它 ——
        // 这一条必须每次显式承认一次（原实现用「唯一出现文件 == 状态机自身」表达）。
        assertFalse(wiredByPlayerOperation(EquipmentLifecycle.State.SACRIFICED),
                "献祭仍是预留态：不得有玩家操作生产者");
        assertTrue(EquipmentExitPathRules.hasExitPath(),
                "启动自检必须认为退出路径齐全，失败清单：" + EquipmentExitPathRules.exitPathFailures());

        // 「谁引用了这个标识符」在运行期没有对应形态（跨插件「零出现」尤其如此）→ 门禁执行。
        assertSourceContractHolds("exit-path-producers", "everyDeclaredExitPathIsEitherWiredOrExplicitlyUnwired");
    }

    @Test
    void seasonAttunementHasNoPersistedProducerInTheEquipmentPlugin() throws Exception {
        // 一半是**真实行为断言**：枚举全部 kind × 全部状态，装备插件的操作面永远
        // 产不出 SEASON_ATTUNED，也没有持有态种子。
        for (EquipmentOperationKind kind : EquipmentOperationKind.values()) {
            EquipmentLifecycle.State seed = EquipmentLifecycle.seedForOperation(kind);
            if (kind == EquipmentOperationKind.SEASON_ATTUNEMENT) {
                assertNull(seed, "赛季调谐不是玩家操作，不得有可推断的种子态");
                assertNull(EquipmentLifecycle.afterOperation(kind, EquipmentLifecycle.State.BOUND),
                        "赛季调谐不得经玩家操作 outbox 产出结果态");
            }
            for (EquipmentLifecycle.State from : EquipmentLifecycle.State.values()) {
                assertFalse(EquipmentLifecycle.afterOperation(kind, from)
                                == EquipmentLifecycle.State.SEASON_ATTUNED,
                        kind + "(" + from + ") 不得产出赛季调谐态");
            }
            assertFalse(EquipmentLifecycle.afterOperation(kind, seed)
                            == EquipmentLifecycle.State.SEASON_ATTUNED,
                    kind + " 不应把装备直接推进赛季调谐：触发者只能是换季结算");
        }
        // 另一半（「rpg-items 启动路径里不得出现 SEASON_ATTUNED 分支」）只能对源码
        // 结构做静态保证 → 同一个规格键的 forbidden，由门禁执行。
        assertSourceContractHolds("season-attunement", "seasonAttunementHasNoPersistedProducerInTheEquipmentPlugin");
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
        assertSourceContractHolds("destroy-command", "destroyEntryPointPersistsEscrowBeforeSubmitting");
        assertSourceContractHolds("destroy-command", "destroyCommandUsesAUniqueAuditableIdempotencyKey");
    }

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
                DestroyDeliveryRules.laneFor(EquipmentOperationKind.REFINEMENT));

        // 「判据必须排在 operations.stage 之前，且拒绝必须 return 而不是 throw」这两条
        // 只能对源码结构做静态保证 → 门禁执行。
        assertSourceContractHolds("destroy-command", "mailboxLaneGateFailsClosedBeforeAnyStateIsMutated");
    }

    /** 状态机声明的退出路径条数（直接来自 {@link EquipmentLifecycle#isExitPath}，不写死）。 */
    private static int declaredExitPathCount() {
        return (int) java.util.Arrays.stream(EquipmentLifecycle.State.values())
                .filter(EquipmentLifecycle::isExitPath)
                .count();
    }

    /**
     * 是否已接线：存在一条经状态机认可的玩家操作迁移，把装备从持有态推进到该终态。
     * 与 {@link EquipmentExitPathRules#isExitPath} 同口径（真跑，不读源码）。
     */
    private static boolean isWired(EquipmentLifecycle.State target) {
        return wiredByPlayerOperation(target);
    }

    /** 玩家操作面（{@link EquipmentOperationKind}）里是否有产出该状态的生产者。 */
    private static boolean wiredByPlayerOperation(EquipmentLifecycle.State target) {
        for (EquipmentOperationKind kind : EquipmentOperationKind.values()) {
            EquipmentLifecycle.State seed = EquipmentLifecycle.seedForOperation(kind);
            if (seed == null) continue;
            if (EquipmentLifecycle.afterOperation(kind, seed) == target) return true;
        }
        return false;
    }

    /** 词根判定：调谐在玩家可见面上只允许零出现。 */
    private static boolean mentionsAttunement(String text) {
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("attun") || text.contains("赛季调谐");
    }

    /** 递归收全部键路径（含 YAML 的子键），让「配置面」这件事可被直接断言。 */
    private static void collectKeys(Object node, List<String> keys) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                keys.add(key);
                if (entry.getValue() instanceof Map<?, ?> nested) {
                    List<String> children = new ArrayList<>();
                    collectKeys(nested, children);
                    for (String child : children) keys.add(key + "." + child);
                } else if (entry.getValue() instanceof List<?> list) {
                    for (Object element : list) keys.add(key + "." + element);
                }
            }
        }
    }

    /**
     * 真解析打包进 jar 的 YAML 清单（plugin.yml / config.yml）。
     *
     * <p>为什么这是行为化而不是换一种读源码：解析的是**运行期同一份资源**，
     * 走到的是生产同一套 SnakeYAML 口径（重复键直接拒绝），断言落在解析结果
     * （键集合 / 命令名）上而不是某一行文本上；注释、换行、键序改动都不再影响结论。</p>
     */
    private static Map<?, ?> packagedYaml(String name) throws Exception {
        try (InputStream input = EquipmentExitPathReachabilityTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull(input, "打包资源缺失: " + name);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            assertTrue(loaded instanceof Map, name + " 的顶层必须是映射");
            return (Map<?, ?>) loaded;
        }
    }

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertSourceContractHolds(String specKey, String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        Path spec = root.resolve("scripts/source-contract-specs/rpg-items.json");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        assertTrue(Files.isRegularFile(spec), "缺少本模块契约规格: " + spec);
        assertFalse(new String(Files.readAllBytes(spec), StandardCharsets.UTF_8).isBlank(),
                "本模块契约规格不得为空（清空规格等于删断言）");
        Process process = new ProcessBuilder("sh", gate.toString(), SPEC_CLASS + "#" + specKey, method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "源码契约门禁失败（" + specKey + " :: " + method + "）：\n" + output);
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
