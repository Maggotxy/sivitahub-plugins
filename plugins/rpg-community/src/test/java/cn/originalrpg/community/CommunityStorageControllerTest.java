package cn.originalrpg.community;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CommunityStorageRequest;
import cn.originalrpg.api.model.CommunityStorageResult;
import cn.originalrpg.api.service.CommunityStorageService;
import cn.originalrpg.api.service.CommunityStorageSessionService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual command adapter and deferred callbacks without claiming a Paper client run. */
final class CommunityStorageControllerTest {
    @Test
    void authenticationAndBothFeatureGatesPrecedeEveryStorageMutation() {
        Fixture fixture = new Fixture();
        fixture.command("withdraw", UUID.randomUUID().toString());
        assertTrue(fixture.requests.isEmpty());
        fixture.authenticate();
        fixture.localEnabled = false;
        fixture.command("deposit");
        fixture.command("withdraw", UUID.randomUUID().toString());
        assertEquals(0, fixture.deposits);
        assertTrue(fixture.requests.isEmpty());
        fixture.localEnabled = true;
        fixture.coreEnabled = false;
        fixture.command("withdraw", UUID.randomUUID().toString());
        assertTrue(fixture.requests.isEmpty());
        assertTrue(fixture.keys.contains("community-storage.disabled"));
    }

    @Test
    void depositUsesTheMarketPhysicalEscrowBridgeAndRejectsItsAbsence() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.command("deposit");
        assertEquals(1, fixture.deposits);
        fixture.adapterAvailable = false;
        fixture.command("deposit");
        assertEquals(1, fixture.deposits);
        assertTrue(fixture.keys.contains("community-storage.adapter-unavailable"));
        assertTrue(fixture.requests.isEmpty(), "the UI never manufactures a payload or a second inventory authority");
    }

    @Test
    void exactListingRecipientAndActorAreCapturedOnceWhileTheOperationIsPending() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        UUID listing = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        fixture.command("withdraw", listing.toString(), recipient.toString());
        fixture.command("withdraw", listing.toString(), fixture.actorId.toString());
        fixture.command("deposit");
        assertEquals(1, fixture.requests.size());
        assertEquals(fixture.actorId, fixture.requests.getFirst().actor());
        assertEquals(listing, fixture.requests.getFirst().listing());
        assertEquals(recipient, fixture.requests.getFirst().recipient());
        assertEquals(0, fixture.deposits);
        assertTrue(fixture.keys.contains("community.operation-pending"));
        CompletableFuture.runAsync(() -> fixture.results.getFirst().complete(
                CommunityStorageResult.of(CommunityStorageResult.Status.SUCCESS))).join();
        assertFalse(fixture.keys.contains("community-storage.result.success"));
        fixture.drain();
        assertTrue(fixture.keys.contains("community-storage.result.success"));
        fixture.command("withdraw", listing.toString());
        assertEquals(2, fixture.requests.size());
        assertEquals(fixture.actorId, fixture.requests.getLast().recipient());
        assertNotEquals(fixture.requests.getFirst().key(), fixture.requests.getLast().key());
    }

    @Test
    void unknownNamesAndMalformedIdsCannotInventAnOfflineRecipientOrRequest() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.command("withdraw", UUID.randomUUID().toString(), "NeverSeenPlayer");
        fixture.command("withdraw", "not-an-id", fixture.actorId.toString());
        fixture.command("approve", "not-an-id");
        assertTrue(fixture.requests.isEmpty());
        assertTrue(fixture.decisions.isEmpty());
        assertTrue(fixture.keys.contains("community-storage.invalid-target"));
    }

    @Test
    void anOldConnectionCannotSubmitOrReceiveResultsInTheNextSessionWithTheSameUuid() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.command("withdraw", UUID.randomUUID().toString());
        Player old = fixture.player;
        fixture.controller.onRevoked(new AuthenticatedPlayerRevokedEvent(old));
        fixture.player = fixture.newPlayer(fixture.actorId);
        fixture.online.put(fixture.actorId, fixture.player);
        fixture.authenticate();
        fixture.keys.clear();
        fixture.controller.onCommand(old, null, "communitystorage", new String[]{"withdraw", UUID.randomUUID().toString()});
        assertEquals(1, fixture.requests.size());
        CompletableFuture.runAsync(() -> fixture.results.getFirst().complete(
                CommunityStorageResult.of(CommunityStorageResult.Status.SUCCESS))).join();
        fixture.drain();
        assertTrue(fixture.keys.isEmpty());
        fixture.command("withdraw", UUID.randomUUID().toString());
        assertEquals(2, fixture.requests.size(), "discarding an old callback cannot block the new login");
    }

    @Test
    void completedApprovalNotifiesTheAuthenticatedRecipientRatherThanTheSigningOfficer() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        UUID recipientId = UUID.randomUUID();
        Player recipient = fixture.newPlayer(recipientId);
        fixture.online.put(recipientId, recipient);
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(recipient));
        UUID requestId = UUID.randomUUID();
        fixture.command("approve", requestId.toString());
        assertEquals(List.of("approveWithdrawal:" + fixture.actorId + ":" + requestId), fixture.decisions);
        var request = new CommunityStorageRequest(requestId, UUID.randomUUID(), UUID.randomUUID(), fixture.actorId,
                recipientId, CommunityStorageRequest.State.COMPLETED, Instant.EPOCH, Optional.of(Instant.EPOCH), List.of());
        CompletableFuture.runAsync(() -> fixture.results.getFirst().complete(new CommunityStorageResult(
                CommunityStorageResult.Status.DELIVERED, Optional.of(request)))).join();
        fixture.drain();
        assertEquals(List.of(recipientId), fixture.mailNotices);
    }

    @Test
    void shutdownDropsLateDeliveryFeedbackWithoutUndoingTheDurableOperation() {
        Fixture fixture = new Fixture();
        fixture.authenticate();
        fixture.command("approve", UUID.randomUUID().toString());
        fixture.controller.clear();
        fixture.keys.clear();
        CompletableFuture.runAsync(() -> fixture.results.getFirst().complete(
                CommunityStorageResult.of(CommunityStorageResult.Status.DELIVERED))).join();
        fixture.drain();
        assertTrue(fixture.keys.isEmpty());
        assertEquals(1, fixture.decisions.size());
    }

    private record Requested(UUID actor, UUID listing, UUID recipient, String key) {}

    private static final class Fixture {
        private final Thread main = Thread.currentThread();
        private final UUID actorId = UUID.randomUUID();
        private final List<String> keys = new ArrayList<>();
        private final List<UUID> mailNotices = new ArrayList<>();
        private final List<Runnable> queue = new ArrayList<>();
        private final List<Requested> requests = new ArrayList<>();
        private final List<String> decisions = new ArrayList<>();
        private final List<CompletableFuture<CommunityStorageResult>> results = new ArrayList<>();
        private final Map<UUID, Player> online = new HashMap<>();
        private final CommunityStorageController controller;
        private Player player;
        private boolean coreEnabled = true;
        private boolean localEnabled = true;
        private boolean adapterAvailable = true;
        private int deposits;

        private Fixture() {
            player = newPlayer(actorId);
            online.put(actorId, player);
            BukkitTask task = proxy(BukkitTask.class, CommunityStorageControllerTest::zero);
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (self, method, args) -> {
                if (method.getName().equals("runTask")) {
                    synchronized (queue) { queue.add((Runnable) args[1]); }
                    return task;
                }
                return zero(self, method, args);
            });
            Server server = proxy(Server.class, (self, method, args) -> {
                assertSame(main, Thread.currentThread(), "Bukkit server reads remain on main");
                return switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "getPlayer" -> args[0] instanceof UUID id ? online.get(id) : null;
                    case "getOnlinePlayers" -> List.copyOf(online.values());
                    default -> zero(self, method, args);
                };
            });
            Plugin plugin = proxy(Plugin.class, (self, method, args) -> {
                assertSame(main, Thread.currentThread(), "Bukkit plugin reads remain on main");
                return switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> Logger.getLogger("community-storage-test");
                    default -> zero(self, method, args);
                };
            });
            MessageService messages = proxy(MessageService.class, (self, method, args) -> {
                assertSame(main, Thread.currentThread(), "Rendering remains on main");
                if (method.getName().equals("text")) {
                    keys.add((String) args[1]);
                    if (args[1].equals("community-storage.mailbox-link")) mailNotices.add(((Player) args[0]).getUniqueId());
                    return args[1];
                }
                if (method.getName().equals("lines")) return List.of((String) args[1]);
                return zero(self, method, args);
            });
            CommunityStorageService service = proxy(CommunityStorageService.class, (self, method, args) -> {
                assertSame(main, Thread.currentThread(), "Submission is admitted in the current UI session");
                if (method.getName().equals("enabled")) return coreEnabled;
                if (method.getName().equals("requestWithdrawal")) {
                    requests.add(new Requested((UUID) args[0], (UUID) args[1], (UUID) args[2], (String) args[3]));
                    return deferred();
                }
                if (method.getName().equals("approveWithdrawal") || method.getName().equals("cancelWithdrawal")) {
                    decisions.add(method.getName() + ":" + args[0] + ":" + args[1]);
                    return deferred();
                }
                throw new AssertionError("Unexpected storage method " + method.getName());
            });
            CommunityStorageSessionService bridge = target -> { assertSame(player, target); deposits++; };
            controller = new CommunityStorageController(plugin, service, messages, () -> localEnabled,
                    () -> adapterAvailable ? bridge : null);
        }

        private CompletableFuture<CommunityStorageResult> deferred() {
            var result = new CompletableFuture<CommunityStorageResult>(); results.add(result); return result;
        }

        private Player newPlayer(UUID id) {
            return proxy(Player.class, (self, method, args) -> {
                assertSame(main, Thread.currentThread(), "No player access from JDBC continuations");
                return switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> true;
                    default -> zero(self, method, args);
                };
            });
        }

        private void authenticate() { controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(player)); }
        private void command(String... args) { assertTrue(controller.onCommand(player, null, "communitystorage", args)); }
        private void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object zero(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "community-storage-fixture";
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
