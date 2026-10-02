package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.SeasonSettlementPreview;
import cn.originalrpg.api.model.SeasonSettlementResult;
import cn.originalrpg.api.rules.SeasonSettlementState;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.SeasonSettlementService;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-101 剩余半条的接线 + 防误触合同（G-302 第二批：改为行为级 + 反射）。
 *
 * <p>原实现读 {@code RpgAdminPlugin.java} / {@code SeasonSettlementCommand.java} 的源码文本
 * 做 {@code contains}。现在改为三层各自最强、且能真的失败的判据：</p>
 * <ol>
 *   <li><b>真实转发行为</b>：直接构造生产类 {@link SeasonSettlementCommand}
 *       （与 {@code RpgAdminPlugin} 用的是同一条 {@code of(...)} 路径），喂一个只计数的
 *       假服务，跑一遍 {@code status}/{@code preview}/{@code advance}，断言命令确实把动作
 *       转发给了服务 —— 而不是自己算了一遍。</li>
 *   <li><b>真实配置解析</b>：用 SnakeYAML 加载 {@code src/main/resources/config.yml}，
 *       断言 {@code season-settlement.enabled} 解析出来就是 {@code false}（默认关，L-009）。</li>
 *   <li><b>反射接线</b>：{@code RpgAdminPlugin} 必须声明 {@code SeasonSettlementCommand}
 *       字段与 {@code handleSeason} 方法。插件继承 {@code JavaPlugin}，本仓无 MockBukkit/
 *       Mockito，构造不出实例，所以「编译期接线成立」只能这样证。</li>
 * </ol>
 */
final class SeasonSettlementWiringTest {

    private static final String CONFIRM = AdminConfirmation.LITERAL;

    @Test
    void advanceAndPreviewHaveRealProductionConsumersOutsideTheServiceItself() {
        // 生产消费者 = SeasonSettlementCommand。用真实实例 + 计数服务断言它真的把
        // preview()/advance() 转发出去（旧的源码扫描只能证明「字符串出现在文件里」）。
        CountingService service = new CountingService();
        SeasonSettlementCommand command = command(service, true);
        List<String> messages = new ArrayList<>();

        command.handle(sender(messages), new String[]{"season", "status"});
        assertTrue(service.stateCalls > 0, "status 必须真的读服务状态，而不是本地猜");

        messages.clear();
        command.handle(sender(messages), new String[]{"season", "preview"});
        assertEquals(1, service.previewCalls, "preview 必须真的转发给服务");

        messages.clear();
        command.handle(sender(messages), new String[]{
                "season", "advance", "previewed", CONFIRM, "criterion accepted"});
        assertEquals(1, service.advanceCalls, "advance 必须真的转发给服务");
    }

    @Test
    void adminSeasonEntryIsSourcedFromTheAuthoritativeFacadeNotFromASecondStateMachine() {
        // 0) 插件分派链必须有 season 子命令与 SeasonSettlementCommand 字段。
        boolean fieldPresent = false;
        for (Field field : RpgAdminPlugin.class.getDeclaredFields()) {
            if (field.getType() == SeasonSettlementCommand.class) fieldPresent = true;
        }
        assertTrue(fieldPresent, "RpgAdminPlugin 必须持有 SeasonSettlementCommand 协作者");
        Method handleSeason = assertDeclared(RpgAdminPlugin.class, "handleSeason");
        assertEquals(boolean.class, handleSeason.getReturnType());

        // 1) 管理面不得持有 JDBC 类型：有 JDBC 字段/参数就说明它可能在自建第二条路径。
        for (Field field : SeasonSettlementCommand.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.startsWith("java.sql.") || type.startsWith("javax.sql.")
                            || type.contains("DataSource"),
                    "管理面不得持有数据库连接（会变成第二套状态机）：" + field.getName());
        }

        // 2) 真实行为：命令只转发一次，绝不自算状态。fake 服务记下每次调用的目标态。
        CountingService service = new CountingService();
        SeasonSettlementCommand command = command(service, true);
        command.handle(sender(new ArrayList<>()), new String[]{
                "season", "advance", "previewed", CONFIRM, "reason"});
        assertEquals(List.of(SeasonSettlementState.PREVIEWED), service.advanceTargets,
                "命令必须把目标态原样交给服务，而不是在本地推进");
    }

    @Test
    void destructiveSeasonPathRequiresTheConfirmLiteralAndShipsDisabledByDefault() throws IOException {
        // 真实配置解析：默认必须关（L-009）。
        Map<?, ?> config = yaml(repositoryRoot().resolve(
                "plugins/rpg-admin/src/main/resources/config.yml"));
        Object section = config.get("season-settlement");
        assertTrue(section instanceof Map<?, ?>, "config.yml 必须含 season-settlement 段");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) section).get("enabled"),
                "换季管理面必须默认关闭（能力接线但不启用）");

        // 真实行为：没有 CONFIRM 时命令不得转发给状态机。
        CountingService service = new CountingService();
        SeasonSettlementCommand command = command(service, true);
        List<String> messages = new ArrayList<>();
        // 5 个参数但第 4 个不是 CONFIRM：必须停在命令层并提示确认字面量。
        command.handle(sender(messages), new String[]{
                "season", "advance", "previewed", "just do it", "reason"});
        assertEquals(0, service.advanceCalls, "缺少 CONFIRM 字面量时不得进入状态机");
        assertTrue(messages.stream().anyMatch(line -> line.contains("admin.season-confirm")),
                "必须提示确认字面量：" + messages);

        // 参数都不够时也只给用法，同样不进状态机。
        List<String> shortArgs = new ArrayList<>();
        command.handle(sender(shortArgs), new String[]{"season", "advance", "previewed"});
        assertEquals(0, service.advanceCalls, "参数形状不合法时不得进入状态机");

        // 开关关闭时连只读也不动服务。
        CountingService disabledService = new CountingService();
        SeasonSettlementCommand disabled = command(disabledService, false);
        disabled.handle(sender(new ArrayList<>()), new String[]{"season", "status"});
        assertEquals(0, disabledService.stateCalls, "开关关闭时必须失败关闭，连状态都不读");
    }

    private static SeasonSettlementCommand command(SeasonSettlementService service, boolean enabled) {
        return SeasonSettlementCommand.of(plugin(), service, chapterService(), messages(), enabled)
                .orElseThrow();
    }

    private static Method assertDeclared(Class<?> owner, String name) {
        try {
            return owner.getDeclaredMethod(name, CommandSender.class, String[].class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("缺少 " + owner.getSimpleName() + "." + name, exception);
        }
    }

    private static Map<?, ?> yaml(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    private static MessageService messages() {
        return (MessageService) Proxy.newProxyInstance(
                MessageService.class.getClassLoader(), new Class<?>[]{MessageService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "text" -> {
                        String key = (String) args[1];
                        StringBuilder rendered = new StringBuilder(key == null ? "" : key);
                        if (args.length >= 3 && args[2] instanceof Map<?, ?> values) {
                            rendered.append(' ').append(values);
                        }
                        yield rendered.toString();
                    }
                    case "lines" -> List.of();
                    case "supportedLocales" -> java.util.Set.of("en_us");
                    case "defaultLocale", "locale" -> "en_us";
                    case "setLocale" -> false;
                    default -> null;
                });
    }

    private static ChapterService chapterService() {
        ChapterState chapter = new ChapterState("launch-1", "awakening", 0, 0, 1L,
                java.time.Instant.EPOCH);
        return new ChapterService() {
            @Override
            public ChapterState current() {
                return chapter;
            }

            @Override
            public List<cn.originalrpg.api.model.ChapterDefinition> definitions() {
                return List.of();
            }

            @Override
            public java.util.Optional<cn.originalrpg.api.model.ChapterDefinition> next() {
                return java.util.Optional.empty();
            }

            @Override
            public CompletionStage<ChapterState> refresh() {
                return CompletableFuture.completedFuture(chapter);
            }

            @Override
            public CompletionStage<cn.originalrpg.api.model.ChapterTransitionResult> advance(
                    String targetChapterId, String actor, String reason
            ) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> java.util.logging.Logger.getAnonymousLogger();
                    case "getName" -> "rpg-admin-test";
                    case "isEnabled" -> true;
                    default -> null;
                });
    }

    private static CommandSender sender(List<String> sink) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "sendMessage" -> {
                        if (args != null && args.length > 0
                                && args[args.length - 1] instanceof net.kyori.adventure.text.Component component) {
                            sink.add(net.kyori.adventure.text.serializer.plain
                                    .PlainTextComponentSerializer.plainText().serialize(component));
                        }
                        yield null;
                    }
                    case "getName" -> "console";
                    case "hasPermission", "isPermissionSet", "isOp" -> true;
                    default -> null;
                });
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }

    /** 只记录调用次数与目标态：断言「命令有没有真的把动作转发出去」。 */
    private static final class CountingService implements SeasonSettlementService {
        private long stateCalls;
        private long previewCalls;
        private long advanceCalls;
        private final List<SeasonSettlementState> advanceTargets = new ArrayList<>();

        @Override
        public CompletionStage<SeasonSettlementState> state(String seasonId) {
            stateCalls++;
            return CompletableFuture.completedFuture(SeasonSettlementState.DRAFT);
        }

        @Override
        public CompletionStage<SeasonSettlementPreview> preview(String seasonId) {
            previewCalls++;
            return CompletableFuture.completedFuture(new SeasonSettlementPreview(
                    "launch-1", "launch-2", 0L, 0L, 0L, 0L, 0L, "digest"));
        }

        @Override
        public CompletionStage<SeasonSettlementResult> advance(
                String seasonId, SeasonSettlementState target, String actor, String reason
        ) {
            return advance(seasonId, seasonId, target, actor, reason);
        }

        @Override
        public CompletionStage<SeasonSettlementResult> advance(
                String seasonId, String nextSeasonId, SeasonSettlementState target,
                String actor, String reason
        ) {
            advanceCalls++;
            advanceTargets.add(target);
            return CompletableFuture.completedFuture(new SeasonSettlementResult(
                    SeasonSettlementResult.Status.APPLIED, SeasonSettlementState.DRAFT,
                    seasonId, 0L, 0L, false));
        }
    }
}
