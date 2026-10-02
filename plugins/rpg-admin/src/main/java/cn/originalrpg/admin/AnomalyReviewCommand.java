package cn.originalrpg.admin;

import cn.originalrpg.api.model.ProgressionAnomaly;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProgressionAnomalyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Phase 1 [H] 门禁的运行期出口：{@code /rpgadmin anomaly <list|ack>}。
 *
 * <p>本类只做「解析入参 + 调服务 + 播报结果」，**不含任何判定口径、不直接读写异常表**：
 * 「什么算不可解释」唯一实现在 {@code ProgressionRules.classifyAnomaly}，
 * 签署与清理唯一实现在 {@link ProgressionAnomalyService}。管理面换掉也不会多出第二条签署路径。</p>
 *
 * <p>权限沿用现有模型：{@code rpgadmin} 命令级 {@code rpg.admin}（plugin.yml，默认 op），
 * 本类不新造权限节点 —— 复核是 [H] 动作，但不该出现第二套「谁能复核」的判定。</p>
 *
 * <p>署名落库：{@code reviewer} 由 {@code sender.getName()} 取，由带 {@code CONFIRM}
 * 的那次调用写入 {@code rpg_progression_anomalies.reviewer/review_note/reviewed_at}。
 * 日志只是并行的审计留痕，不是署名本体。</p>
 */
final class AnomalyReviewCommand {
    /** list 的单页默认上限；操作者可覆写，但会被收敛到 {@link #MAX_LIMIT}。 */
    static final int DEFAULT_LIMIT = 25;
    /** 单页硬上限：与 {@code ProgressionAnomalyService#pending(int, int)} 的收敛口径一致。 */
    static final int MAX_LIMIT = 500;

    private final ProgressionAnomalyService anomalies;
    private final MessageService messages;
    private final Logger logger;
    private final java.util.function.Consumer<Runnable> scheduler;

    AnomalyReviewCommand(
            ProgressionAnomalyService anomalies, MessageService messages, Logger logger,
            java.util.function.Consumer<Runnable> scheduler
    ) {
        this.anomalies = anomalies;
        this.messages = messages;
        this.logger = logger;
        this.scheduler = scheduler == null ? Runnable::run : scheduler;
    }

    /** 未注入服务时返回空：{@code anomaly} 子命令不注册，而不是注册一个必然抛错的壳。 */
    static Optional<AnomalyReviewCommand> of(
            ProgressionAnomalyService anomalies, MessageService messages, Plugin plugin
    ) {
        if (anomalies == null || messages == null || plugin == null) return Optional.empty();
        return of(anomalies, messages, plugin,
                task -> plugin.getServer().getScheduler().runTask(plugin, task));
    }

    /** 同上，但由调用方给主线程派发器（测试夹具用 {@code Runnable::run} 走真同步路径）。 */
    static Optional<AnomalyReviewCommand> of(
            ProgressionAnomalyService anomalies, MessageService messages, Plugin plugin,
            java.util.function.Consumer<Runnable> scheduler
    ) {
        if (anomalies == null || messages == null || plugin == null) return Optional.empty();
        return Optional.of(new AnomalyReviewCommand(anomalies, messages, plugin.getLogger(), scheduler));
    }

    /**
     * 处理 {@code anomaly} 分支。
     *
     * @return 是否已消费本次命令（本类接管全部 anomaly 分支，恒定返回 true）
     */
    boolean handle(CommandSender sender, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            if (args.length <= 2) return list(sender, 0, DEFAULT_LIMIT);
            // list <offset> [<limit>]：offset 翻页，limit 覆写单页上限。
            Integer offset = parseCount(sender, args[2]);
            if (offset == null) return true;
            int limit = DEFAULT_LIMIT;
            if (args.length >= 4) {
                Integer parsed = parseCount(sender, args[3]);
                if (parsed == null) return true;
                // 在命令层就收敛：播报里的 limit 必须是真正生效的那个值，
                // 否则操作者照着回显的窗口算下一页会算错。
                limit = Math.clamp(parsed, 1, MAX_LIMIT);
            }
            return list(sender, offset, limit);
        }
        if (args[1].equalsIgnoreCase("ack")) {
            return acknowledge(sender, args);
        }
        sender.sendMessage(Component.text(
                messages.text(sender, "admin.anomaly-usage"), NamedTextColor.YELLOW));
        return true;
    }

    /** 只读分支：列未复核清单。读失败降级为提示，不让整条命令崩。 */
    private boolean list(CommandSender sender, int offset, int limit) {
        anomalies.pending(offset, limit).whenComplete((pending, error) -> runMain(() -> {
            if (error != null || pending == null) {
                fail(sender, "admin.anomaly-failed", error);
                return;
            }
            // 总数单独读一次：分页必须让操作者看到「还剩多少条」，
            // 否则无法判断清单是在缩短还是在被反复刷新。
            anomalies.pendingCount().whenComplete((total, countError) -> runMain(() -> {
                if (countError != null || total == null) {
                    fail(sender, "admin.anomaly-failed", countError);
                    return;
                }
                sender.sendMessage(Component.text(messages.text(sender, "admin.anomaly-list", Map.of(
                        "count", pending.size(), "total", total,
                        "offset", offset, "limit", limit)), NamedTextColor.GOLD));
                if (pending.isEmpty()) {
                    sender.sendMessage(Component.text(
                            messages.text(sender, "admin.anomaly-list-empty"), NamedTextColor.GREEN));
                }
                for (ProgressionAnomaly anomaly : pending) sendAnomaly(sender, anomaly);
                sender.sendMessage(Component.text(messages.text(sender, "admin.anomaly-ack-usage"),
                        NamedTextColor.GRAY));
            }));
        }));
        return true;
    }

    /**
     * {@code ack <player-uuid> CONFIRM <结论...>}。
     *
     * <p>{@code CONFIRM} 必须在结论之前：结论是自由文本，只有位置固定才拦得住误触
     * （与 {@code season advance} 同一约定，复用 {@link AdminConfirmation}）。</p>
     */
    private boolean acknowledge(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text(
                    messages.text(sender, "admin.anomaly-ack-usage"), NamedTextColor.YELLOW));
            return true;
        }
        UUID playerId = parseUuid(sender, args[2]);
        if (playerId == null) return true;
        if (!AdminConfirmation.isConfirmed(args[3])) {
            sender.sendMessage(Component.text(
                    messages.text(sender, "admin.anomaly-confirm"), NamedTextColor.YELLOW));
            return true;
        }
        String note = String.join(" ", List.of(args).subList(4, args.length)).strip();
        if (note.length() > 200) {
            sender.sendMessage(Component.text(
                    messages.text(sender, "admin.anomaly-note-length"), NamedTextColor.YELLOW));
            return true;
        }
        String reviewer = safeActor(sender.getName());
        // 署名先落日志再执行：与其它管理动作同一形状，日志里带完整的操作者/目标/结论。
        logger.warning("AUDIT actor=" + sender.getName() + " action=anomaly-ack target=" + playerId
                + " reviewer=" + reviewer + " note=" + note);
        anomalies.acknowledge(playerId, reviewer, note).whenComplete((signed, error) -> runMain(() -> {
            if (error != null || signed == null) {
                fail(sender, "admin.anomaly-failed", error);
                return;
            }
            if (Boolean.TRUE.equals(signed)) {
                sender.sendMessage(Component.text(messages.text(sender, "admin.anomaly-acked", Map.of(
                        "player", playerId, "reviewer", reviewer, "note",
                        note.isBlank() ? "—" : note)), NamedTextColor.GREEN));
                return;
            }
            // 幂等口径：本次没有改动，要么无待复核行，要么已被别人签过。
            // 回读一次把「已由谁于何时签署」摆到操作者眼前，而不是笼统报失败。
            anomalies.pending(playerId).whenComplete((stillPending, readError) -> runMain(() -> {
                if (readError != null || stillPending == null) {
                    fail(sender, "admin.anomaly-failed", readError);
                    return;
                }
                sender.sendMessage(Component.text(messages.text(sender, "admin.anomaly-not-pending",
                        Map.of("player", playerId)), NamedTextColor.YELLOW));
            }));
        }));
        return true;
    }

    private void sendAnomaly(CommandSender sender, ProgressionAnomaly anomaly) {
        sender.sendMessage(Component.text(messages.text(sender, "admin.anomaly-entry", Map.of(
                "player", anomaly.playerId(),
                "ascension", anomaly.ascension(),
                "level", anomaly.level(),
                "experience", anomaly.experience(),
                "reason", anomaly.reason(),
                "detected", format(anomaly.detectedAt()))), NamedTextColor.YELLOW));
    }

    private void fail(CommandSender sender, String key, Throwable error) {
        sender.sendMessage(Component.text(messages.text(sender, key), NamedTextColor.RED));
        if (error != null) logger.warning("Anomaly review failed closed: " + error.getMessage());
    }

    private UUID parseUuid(CommandSender sender, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text(
                    messages.text(sender, "admin.anomaly-invalid-id"), NamedTextColor.RED));
            return null;
        }
    }

    private Integer parseCount(CommandSender sender, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.anomaly-usage"),
                    NamedTextColor.YELLOW));
            return null;
        }
    }

    private static String format(Instant instant) {
        try {
            return instant.toString();
        } catch (DateTimeParseException exception) {
            return "—";
        }
    }

    /** 与其它管理子命令同一形状的操作者归一化（非法字符替换、上限 64）。 */
    private static String safeActor(String value) {
        String normalized = java.util.Objects.requireNonNullElse(value, "console")
                .replaceAll("[^A-Za-z0-9._:-]", "_");
        return normalized.isBlank() ? "console" : normalized.substring(0, Math.min(64, normalized.length()));
    }

    private void runMain(Runnable task) {
        scheduler.accept(task);
    }

    /** Tab 补全：list [<offset> [<limit>]] / ack <player-uuid> CONFIRM <结论>。 */
    static List<String> complete(String[] args) {
        if (args.length == 2) return prefix(List.of("list", "ack"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("ack")) return List.of();
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) return prefix(List.of("0", "25", "50"), args[2]);
        if (args.length == 4 && args[1].equalsIgnoreCase("ack")) {
            return prefix(AdminConfirmation.completions(args[3]), args[3]);
        }
        return List.of();
    }

    private static List<String> prefix(List<String> values, String input) {
        String normalized = input == null ? "" : input.toLowerCase(java.util.Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(java.util.Locale.ROOT).startsWith(normalized)) result.add(value);
        }
        return result;
    }
}
