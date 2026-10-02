package cn.originalrpg.dungeons;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.SeasonExpeditionCompletion;
import cn.originalrpg.api.model.SeasonExpeditionLeaderboardEntry;
import cn.originalrpg.api.model.SeasonExpeditionProfile;
import cn.originalrpg.api.model.SeasonExpeditionResult;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.SeasonExpeditionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

final class SeasonExpeditionController {
    private static final int MAXIMUM_SETTLEMENT_ATTEMPTS = 3;
    private static final long[] RETRY_TICKS = {20L, 60L};

    private final Plugin plugin;
    private final SeasonExpeditionService service;
    private final ChapterService chapters;
    private final MessageService messages;
    private final SeasonExpeditionRotationPolicy rotations;
    private final boolean enabled;
    private final int minimumChapterSequence;
    private final Clock clock;

    SeasonExpeditionController(
            Plugin plugin,
            SeasonExpeditionService service,
            ChapterService chapters,
            MessageService messages
    ) {
        this(plugin, service, chapters, messages, Clock.systemUTC());
    }

    SeasonExpeditionController(
            Plugin plugin,
            SeasonExpeditionService service,
            ChapterService chapters,
            MessageService messages,
            Clock clock
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.chapters = java.util.Objects.requireNonNull(chapters, "chapters");
        this.messages = java.util.Objects.requireNonNull(messages, "messages");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        enabled = plugin.getConfig().getBoolean("season-expedition.enabled", true);
        minimumChapterSequence = Math.clamp(
                plugin.getConfig().getInt("season-expedition.minimum-chapter-sequence", 0), 0, 100);
        ZoneId zone = ZoneId.of(plugin.getConfig().getString(
                "season-expedition.timezone", "Asia/Shanghai"));
        LocalDate epoch = LocalDate.parse(plugin.getConfig().getString(
                "season-expedition.rotation-epoch-monday", "2026-08-03"));
        rotations = new SeasonExpeditionRotationPolicy(zone, epoch, clock);
    }

    Optional<SeasonExpeditionRotation> current() {
        if (!enabled) return Optional.empty();
        ChapterState chapter = chapters.current();
        if (chapter.sequence() < minimumChapterSequence) return Optional.empty();
        return Optional.of(rotations.current(chapter.seasonId()));
    }

    boolean remainsActive(SeasonExpeditionRotation rotation) {
        if (rotation == null || !enabled) return false;
        ChapterState chapter = chapters.current();
        return chapter.sequence() >= minimumChapterSequence
                && chapter.seasonId().equals(rotation.seasonId())
                && rotations.isActive(rotation, clock.instant());
    }

    void announceStart(Iterable<Player> players, SeasonExpeditionRotation rotation) {
        for (Player player : players) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.season.started", Map.of(
                    "rotation", rotation.rotationId(),
                    "dungeon", messages.text(player, "dungeon.names." + rotation.dungeonId()),
                    "affixes", affixNames(player, rotation))), NamedTextColor.LIGHT_PURPLE));
        }
    }

    void settle(SeasonExpeditionCompletion completion) {
        settle(completion, ignored -> { });
    }

    void settle(SeasonExpeditionCompletion completion, Consumer<Boolean> acknowledgement) {
        settle(completion, 1, java.util.Objects.requireNonNull(acknowledgement, "acknowledgement"));
    }

    private void settle(
            SeasonExpeditionCompletion completion, int attempt, Consumer<Boolean> acknowledgement
    ) {
        service.complete(completion).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            if (error != null) {
                if (attempt < MAXIMUM_SETTLEMENT_ATTEMPTS) {
                    long delay = RETRY_TICKS[attempt - 1];
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> settle(completion, attempt + 1, acknowledgement), delay);
                    return;
                }
                plugin.getLogger().severe("Season expedition settlement failed after " + attempt
                        + " idempotent attempts; run=" + completion.runId() + ": " + error.getMessage());
                acknowledgement.accept(false);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) return;
                showSettlement(completion, result);
                acknowledgement.accept(result.status() == SeasonExpeditionResult.Status.SUCCESS
                        || result.status() == SeasonExpeditionResult.Status.DUPLICATE);
            });
        });
    }

    private void showSettlement(SeasonExpeditionCompletion completion, SeasonExpeditionResult result) {
        if (result.status() == SeasonExpeditionResult.Status.DUPLICATE) {
            plugin.getLogger().info("Season expedition retry was already settled: " + completion.runId());
            return;
        }
        if (result.status() != SeasonExpeditionResult.Status.SUCCESS) {
            plugin.getLogger().warning("Season expedition settlement was rejected (" + result.status()
                    + "): " + completion.runId());
            return;
        }
        result.players().forEach(playerResult -> {
            Player player = Bukkit.getPlayer(playerResult.playerId());
            if (player == null || !player.isOnline()) return;
            if (playerResult.weeklyClaimedNow()) {
                player.sendMessage(Component.text(messages.text(player, "dungeon.season.reward", Map.of(
                        "honor", playerResult.honorGranted(),
                        "total", playerResult.totalHonor(),
                        "stamps", playerResult.collectionStamps())), NamedTextColor.GOLD));
            } else {
                player.sendMessage(Component.text(messages.text(player, "dungeon.season.repeat"),
                        NamedTextColor.GRAY));
            }
            if (playerResult.newPersonalBest()) {
                player.sendMessage(Component.text(messages.text(player, "dungeon.season.personal-best", Map.of(
                        "duration", formatDuration(playerResult.personalBestMillis()))), NamedTextColor.AQUA));
            }
        });
        if (result.serverFirst()) {
            String names = completion.participantIds().stream().map(this::playerName)
                    .collect(java.util.stream.Collectors.joining(", "));
            Bukkit.getOnlinePlayers().forEach(audience -> audience.sendMessage(Component.text(messages.text(
                    audience, "dungeon.season.server-first", Map.of("players", names,
                            "dungeon", messages.text(audience, "dungeon.names." + completion.dungeonId()),
                            "duration", formatDuration(completion.durationMillis()))), NamedTextColor.LIGHT_PURPLE)));
        }
    }

    void showStatus(Player player, int partySize) {
        Optional<SeasonExpeditionRotation> current = current();
        if (current.isEmpty()) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.season.locked"), NamedTextColor.YELLOW));
            return;
        }
        SeasonExpeditionRotation rotation = current.get();
        service.profile(player.getUniqueId(), rotation.seasonId(), rotation.rotationId(), partySize)
                .thenCombine(service.firstClear(rotation.seasonId(), rotation.rotationId()), StatusView::new)
                .whenComplete((view, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!currentSession(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "dungeon.season.query-failed"),
                                NamedTextColor.RED));
                        return;
                    }
                    renderStatus(player, rotation, partySize, view.profile(), view.firstClear());
                });
                });
    }

    private void renderStatus(
            Player player,
            SeasonExpeditionRotation rotation,
            int partySize,
            SeasonExpeditionProfile profile,
            Optional<SeasonExpeditionLeaderboardEntry> firstClear
    ) {
        player.sendMessage(Component.text(messages.text(player, "dungeon.season.title"), NamedTextColor.GOLD));
        player.sendMessage(Component.text(messages.text(player, "dungeon.season.rotation", Map.of(
                "dungeon", messages.text(player, "dungeon.names." + rotation.dungeonId()),
                "affixes", affixNames(player, rotation),
                "reset", resetTime(rotation))), NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(Component.text(messages.text(player, "dungeon.season.progress", Map.of(
                "honor", profile.honor(), "stamps", profile.collectionStamps(),
                "runs", profile.completedRuns(),
                "claimed", messages.text(player, profile.currentRotationClaimed()
                        ? "dungeon.season.claimed.yes" : "dungeon.season.claimed.no"))), NamedTextColor.AQUA));
        String personalBest = profile.personalBestMillis().isPresent()
                ? formatDuration(profile.personalBestMillis().getAsLong())
                : messages.text(player, "dungeon.season.no-record");
        player.sendMessage(Component.text(messages.text(player, "dungeon.season.division", Map.of(
                "party", partySize, "best", personalBest)), NamedTextColor.GRAY));
        if (firstClear.isPresent()) {
            SeasonExpeditionLeaderboardEntry record = firstClear.get();
            String names = record.memberIds().stream().map(this::playerName)
                    .collect(java.util.stream.Collectors.joining(", "));
            player.sendMessage(Component.text(messages.text(player, "dungeon.season.first-clear", Map.of(
                    "players", names, "duration", formatDuration(record.durationMillis()))),
                    NamedTextColor.LIGHT_PURPLE));
        } else {
            player.sendMessage(Component.text(messages.text(player, "dungeon.season.first-clear-open"),
                    NamedTextColor.LIGHT_PURPLE));
        }
        player.sendMessage(Component.text(messages.text(player, "dungeon.season.enter-action"), NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/dungeon season enter"))
                .hoverEvent(HoverEvent.showText(Component.text(
                        messages.text(player, "dungeon.season.enter-hover"), NamedTextColor.GRAY))));
        player.sendMessage(Component.text(messages.text(player, "dungeon.season.top-action", Map.of(
                        "party", partySize)), NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/dungeon season top " + partySize)));
    }

    void showLeaderboard(Player player, int partySize) {
        Optional<SeasonExpeditionRotation> current = current();
        if (current.isEmpty()) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.season.locked"), NamedTextColor.YELLOW));
            return;
        }
        SeasonExpeditionRotation rotation = current.get();
        service.leaderboard(rotation.seasonId(), rotation.rotationId(), partySize, 10)
                .whenComplete((entries, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!currentSession(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "dungeon.season.query-failed"),
                                NamedTextColor.RED));
                        return;
                    }
                    player.sendMessage(Component.text(messages.text(player, "dungeon.season.top-title", Map.of(
                            "party", partySize)), NamedTextColor.GOLD));
                    if (entries.isEmpty()) {
                        player.sendMessage(Component.text(messages.text(player, "dungeon.season.top-empty"),
                                NamedTextColor.GRAY));
                        return;
                    }
                    entries.forEach(entry -> player.sendMessage(Component.text(messages.text(player,
                            "dungeon.season.top-row", Map.of("rank", entry.rank(),
                                    "duration", formatDuration(entry.durationMillis()),
                                    "players", entry.memberIds().stream().map(this::playerName)
                                            .collect(java.util.stream.Collectors.joining(", ")))),
                            NamedTextColor.AQUA)));
                });
                });
    }

    private String affixNames(Player player, SeasonExpeditionRotation rotation) {
        return rotation.affixes().stream().map(affix -> messages.text(player,
                        "dungeon.season.affixes." + affix.key() + ".name"))
                .collect(java.util.stream.Collectors.joining(" + "));
    }

    private String resetTime(SeasonExpeditionRotation rotation) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(rotations.zone());
        Duration remaining = Duration.between(clock.instant(), rotation.endsAt());
        long hours = Math.max(0L, remaining.toHours());
        return formatter.format(rotation.endsAt()) + " (" + hours + "h)";
    }

    private String playerName(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        String name = player.getName();
        return name == null ? playerId.toString().substring(0, 8) : name;
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    static String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis) / 1_000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long millis = Math.max(0L, durationMillis) % 1_000L;
        return "%02d:%02d.%03d".formatted(minutes, seconds, millis);
    }

    private record StatusView(
            SeasonExpeditionProfile profile, Optional<SeasonExpeditionLeaderboardEntry> firstClear
    ) {
    }
}
