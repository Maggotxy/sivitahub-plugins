package cn.originalrpg.community;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CommunityLifecycleAction;
import cn.originalrpg.api.model.CommunityLifecycleAudit;
import cn.originalrpg.api.model.CommunityLifecycleProposal;
import cn.originalrpg.api.model.CommunityLifecycleResult;
import cn.originalrpg.api.model.CommunityLifecycleSnapshot;
import cn.originalrpg.api.service.CommunityLifecycleService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Main-thread command/presence adapter; durable authority and timing remain in Core. */
final class CommunityLifecycleController implements Listener {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final List<String> ROOTS = List.of("lifecycle", "succession", "dissolve", "history");
    private final Plugin plugin;
    private final Server server;
    private final BukkitScheduler scheduler;
    private final Logger logger;
    private final CommunityLifecycleService service;
    private final MessageService messages;
    private final BooleanSupplier localEnabled;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;
    private int cursor;
    private boolean stopped;

    CommunityLifecycleController(Plugin plugin, CommunityLifecycleService service, MessageService messages,
                                 BooleanSupplier localEnabled) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.scheduler = server.getScheduler();
        this.logger = plugin.getLogger();
        this.service = service;
        this.messages = messages;
        this.localEnabled = localEnabled;
    }

    void start() {
        if (service != null) task = scheduler.runTaskTimer(plugin, this::heartbeat, 200L, 200L);
    }

    void stop() {
        stopped = true;
        sessions.clear();
        if (task != null) task.cancel();
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Session session = new Session(event.player());
        sessions.put(event.player().getUniqueId(), session);
        recordPresence(session);
    }

    @EventHandler
    public void onRevoked(AuthenticatedPlayerRevokedEvent event) {
        remove(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    private void remove(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.player == player) sessions.remove(player.getUniqueId(), session);
    }

    boolean route(Player player, String[] args) {
        if (args.length == 0 || !ROOTS.contains(args[0].toLowerCase(Locale.ROOT))) return false;
        Session session = sessions.get(player.getUniqueId());
        if (!current(session)) return true;
        UUID actorId = player.getUniqueId();
        if (service == null || !localEnabled.getAsBoolean() || !service.enabled()) {
            send(player, "community-lifecycle.disabled", Map.of());
            return true;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("history")) {
            if (args.length > 2) help(player);
            else if (args.length == 2) {
                UUID communityId = id(args[1]);
                if (communityId == null) help(player);
                else run(session, false, () -> service.history(communityId, 12), this::showHistory);
            } else {
                run(session, false, () -> service.status(actorId).thenCompose(snapshot ->
                                snapshot.isEmpty() ? java.util.concurrent.CompletableFuture.completedFuture(List.of())
                                        : service.history(snapshot.orElseThrow().communityId(), 12)), this::showHistory);
            }
            return true;
        }
        String action = args.length == 1 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (root.equals("lifecycle") || action.equals("status") || action.equals("help")) {
            if (args.length > 2 || !List.of("status", "help").contains(action)) help(player);
            else if (action.equals("help")) help(player);
            else run(session, false, () -> service.status(actorId), (target, snapshot) -> {
                if (snapshot.isEmpty()) send(target, "community-lifecycle.result.not-member", Map.of());
                else showStatus(target, snapshot.orElseThrow());
                help(target);
            });
            return true;
        }
        CommunityLifecycleAction kind = root.equals("succession")
                ? CommunityLifecycleAction.SUCCESSION : CommunityLifecycleAction.DISSOLUTION;
        if (action.equals("propose") && args.length == 2) {
            String key = "community-lifecycle:" + UUID.randomUUID();
            run(session, true, () -> service.propose(actorId, kind, key), this::showResult);
            return true;
        }
        if (args.length != 3 || !List.of("approve", "execute", "cancel").contains(action)) {
            help(player);
            return true;
        }
        UUID proposalId = id(args[2]);
        if (proposalId == null) {
            help(player);
            return true;
        }
        run(session, true, () -> checkedDecision(session, actorId, kind, proposalId, action), this::showResult);
        return true;
    }

    private CompletionStage<CommunityLifecycleResult> checkedDecision(
            Session session, UUID actorId, CommunityLifecycleAction kind, UUID proposalId, String action
    ) {
        var result = new java.util.concurrent.CompletableFuture<CommunityLifecycleResult>();
        service.status(actorId).whenComplete((snapshot, error) -> onMain(() -> {
            if (!current(session)) {
                result.completeExceptionally(new java.util.concurrent.CancellationException("Authenticated session ended"));
                return;
            }
            if (error != null) { result.completeExceptionally(error); return; }
            boolean matches = snapshot.stream().flatMap(value -> value.proposals().stream())
                    .anyMatch(proposal -> proposal.id().equals(proposalId) && proposal.action() == kind);
            if (!matches) {
                result.complete(CommunityLifecycleResult.of(CommunityLifecycleResult.Status.NOT_FOUND));
                return;
            }
            try {
                CompletionStage<CommunityLifecycleResult> decision = switch (action) {
                    case "approve" -> service.approve(actorId, proposalId);
                    case "execute" -> service.execute(actorId, proposalId);
                    default -> service.cancel(actorId, proposalId);
                };
                decision.whenComplete((outcome, failure) -> {
                    if (failure != null) result.completeExceptionally(failure);
                    else result.complete(outcome);
                });
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }));
        return result;
    }

    List<String> complete(String[] args) {
        if (args.length == 1) return matching(ROOTS, args[0]);
        if (args.length == 2 && List.of("succession", "dissolve").contains(args[0].toLowerCase(Locale.ROOT))) {
            return matching(List.of("status", "help", "propose", "approve", "execute", "cancel"), args[1]);
        }
        return List.of();
    }

    private <T> void run(Session session, boolean mutation, Supplier<CompletionStage<T>> operation,
                         Renderer<T> render) {
        if (session.operation) {
            send(session.player, "community.operation-pending", Map.of());
            return;
        }
        session.operation = true;
        UUID playerId = session.player.getUniqueId();
        if (mutation) {
            try {
                service.recordAuthenticatedActivity(playerId).whenComplete((ignored, error) -> onMain(() -> {
                    if (!current(session)) { session.operation = false; return; }
                    if (error != null) {
                        session.operation = false;
                        failed(session.player, error);
                        return;
                    }
                    invoke(session, operation, render);
                }));
            } catch (RuntimeException failure) {
                session.operation = false;
                failed(session.player, failure);
            }
        } else {
            invoke(session, operation, render);
        }
    }

    private <T> void invoke(Session session, Supplier<CompletionStage<T>> operation, Renderer<T> render) {
        CompletionStage<T> pending;
        try {
            pending = operation.get();
        } catch (RuntimeException failure) {
            session.operation = false;
            failed(session.player, failure);
            return;
        }
        pending.whenComplete((value, error) -> onMain(() -> {
            session.operation = false;
            if (!current(session)) return;
            if (error != null) failed(session.player, error);
            else render.show(session.player, value);
        }));
    }

    private void heartbeat() {
        List<Session> online = new ArrayList<>(sessions.values());
        if (online.isEmpty()) { cursor = 0; return; }
        for (int index = 0; index < Math.min(4, online.size()); index++) {
            recordPresence(online.get(Math.floorMod(cursor++, online.size())));
        }
    }

    private void recordPresence(Session session) {
        if (service == null || session.presence || !current(session)) return;
        session.presence = true;
        UUID playerId = session.player.getUniqueId();
        try {
            service.recordAuthenticatedActivity(playerId).whenComplete((ignored, error) -> onMain(() -> {
                session.presence = false;
                if (error != null && current(session)) logger.warning("Community presence could not be persisted for "
                        + playerId + ": " + error.getMessage());
            }));
        } catch (RuntimeException failure) {
            session.presence = false;
            logger.warning("Community presence was rejected for " + playerId + ": " + failure.getMessage());
        }
    }

    private void showStatus(Player player, CommunityLifecycleSnapshot status) {
        send(player, "community-lifecycle.overview", Map.of("name", status.name(), "tag", status.tag(),
                "owner", status.ownerId(), "lastSeen", TIME.format(status.ownerLastAuthenticated())));
        player.sendMessage(Component.text(messages.text(player, "community-lifecycle.history-link"), NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/community history " + status.communityId())));
        if (status.proposals().isEmpty()) send(player, "community-lifecycle.no-proposals", Map.of());
        status.proposals().forEach(proposal -> showProposal(player, proposal));
    }

    private void showResult(Player player, CommunityLifecycleResult result) {
        send(player, "community-lifecycle.result." + result.status().name().toLowerCase(Locale.ROOT).replace('_', '-'), Map.of());
        result.proposal().ifPresent(proposal -> {
            showProposal(player, proposal);
            player.sendMessage(Component.text(messages.text(player, "community-lifecycle.history-link"), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/community history " + proposal.communityId())));
        });
    }

    private void showProposal(Player player, CommunityLifecycleProposal proposal) {
        String kind = proposal.action() == CommunityLifecycleAction.SUCCESSION ? "succession" : "dissolution";
        send(player, "community-lifecycle.proposal", Map.of("id", proposal.id(),
                "action", messages.text(player, "community-lifecycle.action." + kind),
                "state", messages.text(player, "community-lifecycle.state." + proposal.state().name().toLowerCase(Locale.ROOT)),
                "signatures", proposal.approvals().size(),
                "deadline", proposal.executeAfter().map(TIME::format)
                        .orElse(messages.text(player, "community-lifecycle.awaiting-signatures"))));
        if (proposal.state().terminal()) return;
        String command = "/community " + (proposal.action() == CommunityLifecycleAction.SUCCESSION ? "succession" : "dissolve");
        Component controls = Component.empty();
        for (String action : List.of("approve", "execute", "cancel")) {
            controls = controls.append(Component.text("[" + messages.text(player, "community-lifecycle.button." + action) + "] ",
                            NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand(command + " " + action + " " + proposal.id())));
        }
        player.sendMessage(controls);
    }

    private void showHistory(Player player, List<CommunityLifecycleAudit> history) {
        if (history.isEmpty()) send(player, "community-lifecycle.no-history", Map.of());
        for (CommunityLifecycleAudit entry : history) {
            send(player, "community-lifecycle.audit", Map.of("time", TIME.format(entry.recordedAt()),
                    "proposal", entry.proposalId(), "actor", entry.actorId(), "transition", entry.transition(),
                    "detail", entry.detail()));
        }
    }

    void help(Player player) {
        for (String line : messages.lines(player, "community-lifecycle.help")) {
            player.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        if (service == null) return;
        var policy = service.policy();
        send(player, "community-lifecycle.policy", Map.of("inactiveDays", policy.ownerInactivity().toDays(),
                "memberDays", policy.candidateMembership().toDays(), "successionHours", policy.successionCooling().toHours(),
                "dissolutionHours", policy.dissolutionCooling().toHours()));
    }

    private void send(Player player, String key, Map<String, ?> values) {
        player.sendMessage(Component.text(messages.text(player, key, values), NamedTextColor.GRAY));
    }

    private void failed(Player player, Throwable error) {
        logger.warning("Community lifecycle operation failed: " + error.getMessage());
        send(player, "community.error", Map.of());
    }

    private boolean current(Session session) {
        return !stopped && session != null && sessions.get(session.player.getUniqueId()) == session
                && server.getPlayer(session.player.getUniqueId()) == session.player && session.player.isOnline();
    }

    private void onMain(Runnable action) {
        try {
            scheduler.runTask(plugin, action);
        } catch (IllegalPluginAccessException ignored) {
            // Shutdown owns the sessions; a completed transaction must not resurrect UI.
        }
    }

    private static UUID id(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static List<String> matching(List<String> values, String prefix) {
        return values.stream().filter(value -> value.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    @FunctionalInterface
    private interface Renderer<T> { void show(Player player, T value); }

    private static final class Session {
        private final Player player;
        private boolean operation;
        private boolean presence;
        private Session(Player player) { this.player = player; }
    }
}
