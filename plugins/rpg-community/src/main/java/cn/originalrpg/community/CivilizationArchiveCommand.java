package cn.originalrpg.community;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.service.CivilizationHistoryService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Authenticated, read-only exhibition of community and season history. */
final class CivilizationArchiveCommand implements CommandExecutor, TabCompleter, Listener {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private final Plugin plugin;
    private final CivilizationHistoryService history;
    private final MessageService messages;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean closed;

    CivilizationArchiveCommand(Plugin plugin, CivilizationHistoryService history, MessageService messages) {
        this.plugin = plugin;
        this.history = history;
        this.messages = messages;
    }

    @EventHandler
    public void onReady(AuthenticatedPlayerReadyEvent event) {
        if (!closed) sessions.put(event.player().getUniqueId(), new Session(event.player()));
    }

    @EventHandler
    public void onRevoked(AuthenticatedPlayerRevokedEvent event) {
        remove(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    void close() {
        closed = true;
        sessions.clear();
    }

    private void remove(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.player == player) sessions.remove(player.getUniqueId(), session);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(messages.text(sender, "civilization.player-only")));
            return true;
        }
        Session session = sessions.get(player.getUniqueId());
        if (!current(session) || session.player != player) {
            say(player, "civilization.not-ready", Map.of());
            return true;
        }
        String mode = args.length == 0 ? "projects" : args[0].toLowerCase(Locale.ROOT);
        int page;
        try {
            page = args.length < 2 ? 1 : Integer.parseInt(args[1]);
            if (page < 1 || page > CivilizationHistoryService.MAX_PAGE) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            say(player, "civilization.usage", Map.of());
            return true;
        }
        if (args.length > 2 || !List.of("projects", "seasons").contains(mode)) {
            say(player, "civilization.usage", Map.of());
            return true;
        }
        if (session.pending) {
            say(player, "civilization.pending", Map.of());
            return true;
        }
        session.pending = true;
        try {
            if (mode.equals("projects")) submit(session, history.completedProjects(page), rows -> {
                say(player, "civilization.projects-title", Map.of("page", page));
                if (rows.isEmpty()) say(player, "civilization.empty", Map.of());
                rows.forEach(row -> {
                    say(player, "civilization.project", Map.of("name", row.name(), "tag", row.tag(),
                            "period", row.periodKey(), "gold", row.goldContributed().toPlainString(),
                            "contributors", row.contributors(), "recruits", row.verifiedRecruits(),
                            "honor", row.honorAwarded(), "date", DATE.format(row.completedAt())));
                    if (row.communityDissolved()) say(player, "civilization.dissolved", Map.of());
                });
                navigation(player, mode, page, rows.size());
            });
            else submit(session, history.completedSeasons(page), rows -> {
                say(player, "civilization.seasons-title", Map.of("page", page));
                if (rows.isEmpty()) say(player, "civilization.empty", Map.of());
                rows.forEach(row -> say(player, "civilization.season", Map.of(
                        "season", row.seasonId(), "date", DATE.format(row.completedAt()))));
                navigation(player, mode, page, rows.size());
            });
        } catch (RuntimeException failure) {
            session.pending = false;
            say(player, "civilization.failed", Map.of());
            plugin.getLogger().warning("Civilization history request failed: " + failure.getMessage());
        }
        return true;
    }

    private <T> void submit(Session session, CompletionStage<T> stage, Consumer<T> success) {
        stage.whenComplete((result, failure) -> {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!current(session)) return;
                    session.pending = false;
                    if (failure != null) {
                        say(session.player, "civilization.failed", Map.of());
                        plugin.getLogger().warning("Civilization history query failed: " + failure.getMessage());
                    } else success.accept(result);
                });
            } catch (IllegalPluginAccessException ignored) {
                // An old connection or stopped plugin never receives a stale page.
            }
        });
    }

    private void navigation(Player player, String mode, int page, int count) {
        say(player, "civilization.scope", Map.of());
        if (page > 1) link(player, "civilization.previous", "/civilization " + mode + " " + (page - 1));
        if (count == CivilizationHistoryService.PAGE_SIZE && page < CivilizationHistoryService.MAX_PAGE) {
            link(player, "civilization.next", "/civilization " + mode + " " + (page + 1));
        }
        String other = mode.equals("projects") ? "seasons" : "projects";
        link(player, "civilization.show-" + other, "/civilization " + other);
    }

    private void link(Player player, String key, String command) {
        player.sendMessage(Component.text(messages.text(player, key), NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command)));
    }

    private boolean current(Session session) {
        return !closed && plugin.isEnabled() && session != null && session.player.isOnline()
                && sessions.get(session.id) == session;
    }

    private void say(Player player, String key, Map<String, ?> values) {
        player.sendMessage(Component.text(messages.text(player, key, values), NamedTextColor.GOLD));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("projects", "seasons").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private static final class Session {
        final Player player;
        final UUID id;
        boolean pending;
        Session(Player player) { this.player = player; this.id = player.getUniqueId(); }
    }
}
