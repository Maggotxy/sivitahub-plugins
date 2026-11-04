package cn.originalrpg.community;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.*;
import cn.originalrpg.api.service.CommunityLifecycleService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real command entry and scheduler boundaries without a simulated Paper server. */
final class CommunityLifecycleControllerTest {
    @Test
    void realCommunityCommandKeepsTheGovernanceSubcommandInsteadOfStrippingIt() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.command.onCommand(fixture.player, null, "community", new String[]{"roles"}));
        assertEquals(7, fixture.keys.stream().filter(key -> key.equals("community.governance.role-line")).count());
        fixture.keys.clear();
        fixture.command.onCommand(fixture.player, null, "community", new String[]{"role", "TREASURER"});
        assertEquals(1, fixture.keys.stream().filter(key -> key.equals("community.governance.role-line")).count());
        assertTrue(fixture.keys.contains("community.roles.treasurer"));
    }

    @Test
    void actualCommandEntryNeverSubmitsBeforeAuthentication() {
        Fixture fixture = new Fixture();
        fixture.command.onCommand(fixture.player, null, "community", new String[]{"succession", "propose"});
        assertTrue(fixture.service.presence.isEmpty());
        assertTrue(fixture.service.proposals.isEmpty());
        fixture.authenticate();
        fixture.command.onCommand(fixture.player, null, "community", new String[]{"succession", "propose"});
        fixture.drain();
        assertEquals(List.of(CommunityLifecycleAction.SUCCESSION), fixture.service.proposals);
    }

    @Test
    void admissionHoldsAcrossPresenceAndDatabaseWorkAndWorkerNeverReadsBukkit() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.service.deferPresence = true;
        fixture.route("succession", "propose");
        fixture.route("succession", "propose");
        assertEquals(2, fixture.service.presence.size(), "one authentication update plus one command update");
        assertTrue(fixture.service.proposals.isEmpty());
        CompletableFuture.runAsync(() -> fixture.service.presence.get(1).complete(null)).join();
        assertTrue(fixture.service.proposals.isEmpty(), "the actual mutation waits for the main-thread session check");
        fixture.drain();
        assertEquals(1, fixture.service.proposals.size());
        fixture.route("succession", "propose");
        assertEquals(1, fixture.service.proposals.size());
        CompletableFuture.runAsync(() -> fixture.service.proposalResult.complete(
                CommunityLifecycleResult.of(CommunityLifecycleResult.Status.SUCCESS))).join();
        fixture.drain();
        assertTrue(fixture.keys.contains("community-lifecycle.result.success"));
    }

    @Test
    void revokedPresenceCallbackCannotSubmitInANewConnectionWithTheSameUuid() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.service.deferPresence = true;
        fixture.route("dissolve", "propose");
        fixture.controller.onRevoked(new AuthenticatedPlayerRevokedEvent(fixture.player));
        fixture.player = fixture.newPlayer();
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(fixture.player));
        CompletableFuture.runAsync(() -> fixture.service.presence.get(1).complete(null)).join();
        fixture.drain();
        assertTrue(fixture.service.proposals.isEmpty());
    }

    @Test
    void revocationBetweenProposalLookupAndApprovalCannotCastASignature() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        UUID proposalId = UUID.randomUUID();
        fixture.route("succession", "approve", proposalId.toString());
        fixture.drain();
        assertEquals(1, fixture.service.statusCalls);
        fixture.controller.onRevoked(new AuthenticatedPlayerRevokedEvent(fixture.player));
        CompletableFuture.runAsync(() -> fixture.service.statusResult.complete(
                Optional.of(snapshot(fixture.playerId, proposalId, CommunityLifecycleAction.SUCCESSION)))).join();
        fixture.drain();
        assertTrue(fixture.service.decisions.isEmpty());
    }

    @Test
    void misleadingActionPrefixCannotExecuteADifferentKindOfProposal() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        UUID proposalId = UUID.randomUUID();
        fixture.service.statusResult.complete(Optional.of(snapshot(fixture.playerId, proposalId,
                CommunityLifecycleAction.DISSOLUTION)));
        fixture.route("succession", "execute", proposalId.toString());
        fixture.drain();
        assertTrue(fixture.service.decisions.isEmpty());
        assertTrue(fixture.keys.contains("community-lifecycle.result.not-found"));
    }

    @Test
    void committedOldSessionResultDoesNotNotifyAReconnectedPlayer() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.route("dissolve", "propose");
        fixture.drain();
        assertEquals(1, fixture.service.proposals.size());
        fixture.controller.onRevoked(new AuthenticatedPlayerRevokedEvent(fixture.player));
        fixture.player = fixture.newPlayer();
        fixture.authenticate();
        fixture.keys.clear();
        CompletableFuture.runAsync(() -> fixture.service.proposalResult.complete(
                CommunityLifecycleResult.of(CommunityLifecycleResult.Status.SUCCESS))).join();
        fixture.drain();
        assertTrue(fixture.keys.isEmpty());
    }

    @Test
    void disabledGovernanceDoesNotTurnAuthenticatedPlayersIntoInactiveOwners() {
        Fixture fixture = new Fixture();
        fixture.enabled = false;
        fixture.controller.start();
        fixture.authenticate();
        fixture.route("dissolve", "propose");
        assertTrue(fixture.keys.contains("community-lifecycle.disabled"));
        assertTrue(fixture.service.proposals.isEmpty());
        fixture.heartbeat.run();
        fixture.drain();
        assertEquals(2, fixture.service.presence.size());
    }

    @Test
    void shutdownDropsLateMutationCallbacks() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.route("succession", "propose");
        fixture.drain();
        fixture.controller.stop();
        fixture.keys.clear();
        CompletableFuture.runAsync(() -> fixture.service.proposalResult.complete(
                CommunityLifecycleResult.of(CommunityLifecycleResult.Status.SUCCESS))).join();
        fixture.drain();
        assertTrue(fixture.keys.isEmpty());
    }

    private static CommunityLifecycleSnapshot snapshot(UUID actor, UUID proposalId, CommunityLifecycleAction action) {
        UUID community = UUID.randomUUID();
        var proposal = new CommunityLifecycleProposal(proposalId, community, action, actor, UUID.randomUUID(),
                action == CommunityLifecycleAction.SUCCESSION ? Optional.of(actor) : Optional.empty(),
                CommunityLifecycleProposal.State.PENDING, Instant.EPOCH, Optional.empty(), Optional.empty(), List.of());
        return new CommunityLifecycleSnapshot(community, "Wayfarers", "WAY", actor, Instant.EPOCH, List.of(proposal));
    }

    private static final class Fixture {
        private final Thread mainThread = Thread.currentThread();
        private final UUID playerId = UUID.randomUUID();
        private final List<String> keys = new ArrayList<>();
        private final List<Runnable> queue = new ArrayList<>();
        private final DeferredService service = new DeferredService();
        private final CommunityLifecycleController controller;
        private final CommunityCommand command;
        private Player player;
        private Runnable heartbeat;
        private boolean enabled = true;

        private Fixture() {
            player = newPlayer();
            BukkitTask task = proxy(BukkitTask.class, CommunityLifecycleControllerTest::zero);
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (self, method, args) -> {
                if (method.getName().equals("runTask")) {
                    synchronized (queue) { queue.add((Runnable) args[1]); }
                    return task;
                }
                if (method.getName().equals("runTaskTimer")) {
                    assertSame(mainThread, Thread.currentThread());
                    heartbeat = (Runnable) args[1];
                    return task;
                }
                return zero(self, method, args);
            });
            Server server = proxy(Server.class, (self, method, args) -> {
                assertSame(mainThread, Thread.currentThread(), "server reads must remain on main");
                return switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "getPlayer" -> player;
                    default -> zero(self, method, args);
                };
            });
            Plugin plugin = proxy(Plugin.class, (self, method, args) -> {
                assertSame(mainThread, Thread.currentThread(), "plugin reads must remain on main");
                return switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> Logger.getLogger("community-lifecycle-test");
                    default -> zero(self, method, args);
                };
            });
            MessageService messages = proxy(MessageService.class, (self, method, args) -> {
                assertSame(mainThread, Thread.currentThread(), "localized rendering must remain on main");
                if (method.getName().equals("text")) {
                    keys.add((String) args[1]);
                    return args[1];
                }
                if (method.getName().equals("lines")) return List.of((String) args[1]);
                return zero(self, method, args);
            });
            controller = new CommunityLifecycleController(plugin, service, messages, () -> enabled);
            command = new CommunityCommand(null, null, null, null, null, messages, controller, () -> false);
        }

        private Player newPlayer() {
            return proxy(Player.class, (self, method, args) -> {
                assertSame(mainThread, Thread.currentThread(), "no Bukkit player access from JDBC continuations");
                return switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "isOnline" -> true;
                    default -> zero(self, method, args);
                };
            });
        }

        private void authenticate() {
            controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(player));
            drain();
        }

        private void route(String... args) { assertTrue(controller.route(player, args)); }

        private void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }
    }

    private static final class DeferredService implements CommunityLifecycleService {
        private final List<CompletableFuture<Void>> presence = new ArrayList<>();
        private final List<CommunityLifecycleAction> proposals = new ArrayList<>();
        private final List<String> decisions = new ArrayList<>();
        private final CompletableFuture<CommunityLifecycleResult> proposalResult = new CompletableFuture<>();
        private final CompletableFuture<Optional<CommunityLifecycleSnapshot>> statusResult = new CompletableFuture<>();
        private boolean deferPresence;
        private int statusCalls;

        @Override public boolean enabled() { return true; }
        @Override public CommunityLifecyclePolicy policy() { return CommunityLifecyclePolicy.defaults(); }
        @Override public CompletionStage<Optional<CommunityLifecycleSnapshot>> status(UUID playerId) {
            statusCalls++;
            return statusResult;
        }
        @Override public CompletionStage<Void> recordAuthenticatedActivity(UUID playerId) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            presence.add(result);
            if (!deferPresence) result.complete(null);
            return result;
        }
        @Override public CompletionStage<CommunityLifecycleResult> propose(UUID actor, CommunityLifecycleAction action, String key) {
            proposals.add(action);
            return proposalResult;
        }
        @Override public CompletionStage<CommunityLifecycleResult> approve(UUID actor, UUID proposal) {
            decisions.add("approve"); return CompletableFuture.completedFuture(CommunityLifecycleResult.of(CommunityLifecycleResult.Status.SUCCESS));
        }
        @Override public CompletionStage<CommunityLifecycleResult> execute(UUID actor, UUID proposal) {
            decisions.add("execute"); return CompletableFuture.completedFuture(CommunityLifecycleResult.of(CommunityLifecycleResult.Status.EXECUTED));
        }
        @Override public CompletionStage<CommunityLifecycleResult> cancel(UUID actor, UUID proposal) {
            decisions.add("cancel"); return CompletableFuture.completedFuture(CommunityLifecycleResult.of(CommunityLifecycleResult.Status.CANCELLED));
        }
        @Override public CompletionStage<List<CommunityLifecycleAudit>> history(UUID communityId, int limit) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object zero(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "community-lifecycle-fixture";
            default -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) yield false;
                if (type == int.class) yield 0;
                if (type == long.class) yield 0L;
                if (type == double.class) yield 0.0;
                if (type == float.class) yield 0.0f;
                yield null;
            }
        };
    }
}
