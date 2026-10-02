package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.SeasonSettlementPreview;
import cn.originalrpg.api.model.SeasonSettlementResult;
import cn.originalrpg.api.rules.SeasonSettlementState;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.SeasonSettlementService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * C-010 换季九态状态机的管理面驱动器：{@code /rpgadmin season <status|preview|advance>}。
 *
 * <p>本类只做「解析入参 + 调服务 + 播报结果」，**不含任何状态机、不直接读写换季表**：
 * 九态的合法边、闸门锁、资金事务、Kill Switch 全部由 {@link SeasonSettlementService}
 * 的实现持有。这样「换季必须事务化」只有一份实现，管理面换掉也不会多出第二条路径。</p>
 *
 * <p>三重防误触，缺一不发资金动作：</p>
 * <ol>
 *   <li>配置开关 {@code season-settlement.enabled} 默认关（L-009：能力接线但不启用）；</li>
 *   <li>子命令权限与 {@code incident}/{@code chapter} 同源（{@code rpgadmin} 命令级
 *       {@code rpg.admin}，默认 op），玩家角色不可达；</li>
 *   <li>{@code advance} 必须显式出现 {@code CONFIRM} 字面量。</li>
 * </ol>
 */
final class SeasonSettlementCommand {
    private final Plugin plugin;
    private final SeasonSettlementService settlements;
    private final ChapterService chapters;
    private final MessageService messages;
    private final Logger logger;
    private final boolean enabled;

    SeasonSettlementCommand(
            Plugin plugin, SeasonSettlementService settlements, ChapterService chapters,
            MessageService messages, Logger logger, boolean enabled
    ) {
        this.plugin = plugin;
        this.settlements = settlements;
        this.chapters = chapters;
        this.messages = messages;
        this.logger = logger;
        this.enabled = enabled;
    }

    /** 未注入服务时返回空：{@code season} 子命令不注册，而不是注册一个必然抛错的壳。 */
    static Optional<SeasonSettlementCommand> of(
            Plugin plugin, SeasonSettlementService settlements, ChapterService chapters,
            MessageService messages, boolean enabled
    ) {
        if (plugin == null || settlements == null || messages == null) return Optional.empty();
        return Optional.of(new SeasonSettlementCommand(
                plugin, settlements, chapters, messages, plugin.getLogger(), enabled));
    }

    /**
     * 处理 {@code season} 分支。
     *
     * @return 是否已消费本次命令（本类接管全部 season 分支，恒定返回 true）
     */
    boolean handle(CommandSender sender, String[] args) {
        if (!enabled) {
            // 失败关闭：开关关闭时连读都不做，避免「只读预览」被当成能力已启用。
            sender.sendMessage(Component.text(
                    messages.text(sender, "admin.season-disabled"), NamedTextColor.RED));
            return true;
        }
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            showStatus(sender);
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("preview")) {
            showPreview(sender);
            return true;
        }
        if (args.length >= 5 && args[1].equalsIgnoreCase("advance")) {
            advance(sender, args);
            return true;
        }
        sender.sendMessage(Component.text(
                messages.text(sender, "admin.season-usage"), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                messages.text(sender, "admin.season-advance-usage"), NamedTextColor.YELLOW));
        return true;
    }

    /** 只读当前状态；读失败降级为提示而不是让整条命令崩。 */
    private void showStatus(CommandSender sender) {
        String seasonId = currentSeasonId();
        settlements.state(seasonId).whenComplete((state, error) -> runMain(() -> {
            if (error != null || state == null) {
                failRead(sender, error);
                return;
            }
            sender.sendMessage(Component.text(messages.text(sender, "admin.season-current", Map.of(
                    "season", seasonId, "state", state.name())), NamedTextColor.GOLD));
        }));
    }

    /** PREVIEWED 一态的只读预览：产生事实指纹，但不推进状态、不动资金。 */
    private void showPreview(CommandSender sender) {
        String seasonId = currentSeasonId();
        settlements.preview(seasonId).whenComplete((preview, error) -> runMain(() -> {
            if (error != null || preview == null) {
                failRead(sender, error);
                return;
            }
            sendPreview(sender, seasonId, preview);
        }));
    }

    private void sendPreview(CommandSender sender, String seasonId, SeasonSettlementPreview preview) {
        sender.sendMessage(Component.text(messages.text(sender, "admin.season-current", Map.of(
                "season", seasonId, "state", "PREVIEW")), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(messages.text(sender, "admin.season-preview", Map.of(
                "players", preview.players(),
                "settled", preview.settledPlayers(),
                "season-merit", preview.seasonMerit(),
                "retained", preview.retainedMerit(),
                "honor", preview.honorTotal())), NamedTextColor.AQUA));
    }

    /**
     * 推进一格：{@code season advance <target> CONFIRM [<next-season>] <reason...>}。
     *
     * <p>资金动作只发生在 SETTLING，此时必须给出不同于当前赛季的目标赛季；不给就等于
     * 「结算到自己身上」，服务会以 INVALID 拒绝 —— 那正是 C-010 明令禁止的
     * 「只改 season-id 假装换季」。其他状态给不给目标赛季都不影响语义。</p>
     */
    private void advance(CommandSender sender, String[] args) {
        Optional<SeasonSettlementState> target = SettlementTarget.parse(args[2]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.season-unknown", Map.of(
                    "target", args[2],
                    "states", String.join(", ", SettlementTarget.names()))), NamedTextColor.RED));
            return;
        }
        // CONFIRM 必须在原因之前：原因本身是自由文本，只有位置固定才拦得住误触。
        if (!AdminConfirmation.isConfirmed(args[3])) {
            sender.sendMessage(Component.text(
                    messages.text(sender, "admin.season-confirm"), NamedTextColor.YELLOW));
            return;
        }
        SeasonSettlementState next = target.orElseThrow();
        // 参数形状：advance <target> CONFIRM [<next-season>] <reason...>
        // 第 5 个参数是可选的目标赛季，其余全部属于原因（原因本身可以带空格）。
        String seasonId = currentSeasonId();
        boolean hasNextSeason = args.length >= 6;
        String nextSeasonId = hasNextSeason ? args[4].strip() : seasonId;
        String reason = String.join(" ", List.of(args).subList(hasNextSeason ? 5 : 4, args.length)).strip();
        // 资金动作是一条审计事实：先落日志再执行，日志里带完整的操作者/目标/原因。
        logger.warning("AUDIT actor=" + sender.getName() + " action=season-advance season=" + seasonId
                + " target=" + next.name() + " next=" + nextSeasonId + " reason=" + reason);
        settlements.advance(seasonId, nextSeasonId, next, sender.getName(), reason)
                .whenComplete((result, error) -> runMain(() -> {
                    if (error != null || result == null) {
                        sender.sendMessage(Component.text(
                                messages.text(sender, "admin.season-failed"), NamedTextColor.RED));
                        if (error != null) {
                            logger.warning("Season settlement failed closed: " + error.getMessage());
                        }
                        return;
                    }
                    report(sender, seasonId, result);
                }));
    }

    private void report(CommandSender sender, String seasonId, SeasonSettlementResult result) {
        String key = switch (result.status()) {
            case APPLIED -> "admin.season-applied";
            case DUPLICATE -> "admin.season-duplicate";
            case UNCHANGED -> "admin.season-unchanged";
            case CONFLICT -> "admin.season-conflict";
            case ILLEGAL_TRANSITION -> "admin.season-illegal";
            case BLOCKED -> "admin.season-blocked";
            case INVALID -> "admin.season-invalid";
        };
        NamedTextColor color = switch (result.status()) {
            case APPLIED, DUPLICATE -> NamedTextColor.GREEN;
            case UNCHANGED -> NamedTextColor.YELLOW;
            default -> NamedTextColor.RED;
        };
        sender.sendMessage(Component.text(messages.text(sender, key, Map.of(
                "state", result.state().name(),
                "scanned", result.scanned(),
                "carried", result.carried())), color));
        // 播报后再读一次真实落库状态：不拿返回值当事实，操作者看到的是数据库里的那一格。
        settlements.state(seasonId).whenComplete((state, error) -> runMain(() -> {
            if (error != null || state == null) {
                failRead(sender, error);
                return;
            }
            sender.sendMessage(Component.text(messages.text(sender, "admin.season-current", Map.of(
                    "season", seasonId, "state", state.name())), NamedTextColor.GOLD));
        }));
    }

    private void failRead(CommandSender sender, Throwable error) {
        sender.sendMessage(Component.text(
                messages.text(sender, "admin.season-failed"), NamedTextColor.RED));
        if (error != null) logger.warning("Season settlement read failed closed: " + error.getMessage());
    }

    private String currentSeasonId() {
        if (chapters == null) return DEFAULT_SEASON;
        ChapterState state = chapters.current();
        return state == null || state.seasonId() == null || state.seasonId().isBlank()
                ? DEFAULT_SEASON : state.seasonId();
    }

    /** 章节账本还没建账时的最后兜底，与服务侧 {@code chapters.season-id} 默认值一致。 */
    private static final String DEFAULT_SEASON = "launch-1";

    private void runMain(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /** Tab 补全：status / preview / advance <target> CONFIRM <reason>。 */
    static List<String> complete(String[] args) {
        if (args.length == 2) return prefix(List.of("status", "preview", "advance"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("advance")) {
            return prefix(SettlementTarget.names(), args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("advance")) {
            return prefix(AdminConfirmation.completions(args[3]), args[3]);
        }
        return List.of();
    }

    private static List<String> prefix(List<String> values, String input) {
        String normalized = input == null ? "" : input.toLowerCase(java.util.Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(java.util.Locale.ROOT).startsWith(normalized))
                .toList();
    }
}
