package cn.originalrpg.world;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.FieldJournalEntry;
import cn.originalrpg.api.model.FieldJournalRecord;
import cn.originalrpg.api.model.FieldJournalUpdate;
import cn.originalrpg.api.service.FieldJournalService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Executes authentication, authoritative completion and asynchronous recovery adapters. */
final class FieldJournalLifecycleTest {
    @Test
    void authenticationReconcilesCompletedNativeGoalsAsImmutableFacts() {
        Fixture fixture = new Fixture();
        fixture.completed.add(FieldJournalEntry.FORAGER);
        fixture.completed.add(FieldJournalEntry.NATURALIST);
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(fixture.player));
        assertEquals(Set.copyOf(fixture.completed), fixture.service.calls.getFirst().completed);
        assertThrows(UnsupportedOperationException.class,
                () -> fixture.service.calls.getFirst().completed.add(FieldJournalEntry.NETHER_EXPLORER));
        fixture.service.finish(0);
        fixture.drainMain();
        assertTrue(fixture.messages.isEmpty(), "reconciliation must not spam old achievements on every login");
    }

    @Test
    void completionBeforeAuthenticationDoesNotCreateAReceipt() {
        Fixture fixture = new Fixture();
        fixture.completed.add(FieldJournalEntry.FORAGER);
        fixture.controller.onAdvancement(new PlayerAdvancementDoneEvent(fixture.player,
                fixture.registry.get(FieldJournalEntry.FORAGER)));
        assertTrue(fixture.service.calls.isEmpty());
    }

    @Test
    void aCompletionDuringAnInflightReadIsReconciledAfterThatRead() {
        Fixture fixture = new Fixture();
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(fixture.player));
        fixture.completed.add(FieldJournalEntry.NETHER_EXPLORER);
        fixture.controller.onAdvancement(new PlayerAdvancementDoneEvent(fixture.player,
                fixture.registry.get(FieldJournalEntry.NETHER_EXPLORER)));
        assertEquals(1, fixture.service.calls.size(), "one database operation per connection at a time");
        fixture.service.finish(0);
        fixture.drainMain();
        assertEquals(2, fixture.service.calls.size());
        assertEquals(Set.of(FieldJournalEntry.NETHER_EXPLORER), fixture.service.calls.get(1).completed);
        fixture.service.finish(1);
        fixture.drainMain();
        assertEquals(1, fixture.messages.size());
    }

    @Test
    void revokedConnectionCannotReceiveItsOldCompletionCallback() {
        Fixture fixture = new Fixture();
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(fixture.player));
        fixture.service.finish(0);
        fixture.drainMain();
        fixture.completed.add(FieldJournalEntry.FORAGER);
        fixture.controller.onAdvancement(new PlayerAdvancementDoneEvent(fixture.player,
                fixture.registry.get(FieldJournalEntry.FORAGER)));
        Player old = fixture.player;
        fixture.controller.onRevoked(new AuthenticatedPlayerRevokedEvent(old));
        fixture.player = fixture.newPlayer();
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(fixture.player));
        fixture.service.finish(1);
        fixture.drainMain();
        assertTrue(fixture.messages.isEmpty(), "a late old-session callback must not announce into either connection");
        assertEquals(3, fixture.service.calls.size());
    }

    @Test
    void failedStorageIsRetriedFromRealProgressWhileThePlayerStaysOnline() {
        Fixture fixture = new Fixture();
        fixture.completed.add(FieldJournalEntry.VILLAGE_DEFENDER);
        fixture.controller.start();
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(fixture.player));
        fixture.service.calls.getFirst().result.completeExceptionally(new IllegalStateException("database temporarily offline"));
        fixture.drainMain();
        fixture.heartbeat.run();
        assertEquals(2, fixture.service.calls.size());
        assertEquals(Set.of(FieldJournalEntry.VILLAGE_DEFENDER), fixture.service.calls.get(1).completed);
        fixture.service.finish(1);
        fixture.drainMain();
        assertEquals(1, fixture.messages.size());
    }

    @Test
    void asyncCompletionOnlySchedulesBackToMainAndShutdownDropsTheCallback() {
        Fixture fixture = new Fixture();
        fixture.completed.add(FieldJournalEntry.ARMOR_ARTISAN);
        fixture.controller.onAuthenticated(new AuthenticatedPlayerReadyEvent(fixture.player));
        CompletableFuture.runAsync(() -> fixture.service.finish(0)).join();
        assertEquals(1, fixture.mainQueue.size());
        fixture.controller.shutdown();
        fixture.drainMain();
        assertTrue(fixture.messages.isEmpty());
    }

    private static final class Fixture {
        private final Thread mainThread = Thread.currentThread();
        private final UUID playerId = UUID.randomUUID();
        private final Map<FieldJournalEntry, Advancement> registry = FieldJournalCatalogTest.registry(FieldJournalCatalogTest.catalog());
        private final Set<FieldJournalEntry> completed = EnumSet.noneOf(FieldJournalEntry.class);
        private final List<Runnable> mainQueue = new ArrayList<>();
        private final List<Object> messages = new ArrayList<>();
        private final DeferredService service = new DeferredService();
        private final FieldJournalController controller;
        private Player player;
        private Runnable heartbeat;

        private Fixture() {
            player = newPlayer();
            BukkitTask task = proxy(BukkitTask.class, (self, method, args) -> zero(self, method, args));
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (self, method, args) -> {
                if (method.getName().equals("runTask")) {
                    synchronized (mainQueue) { mainQueue.add((Runnable) args[1]); }
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
                assertSame(mainThread, Thread.currentThread(), "server reads must stay on the main thread");
                return switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "getPlayer" -> player;
                    case "getAdvancement" -> FieldJournalEntry.fromAdvancementKey(((NamespacedKey) args[0]).toString())
                            .map(registry::get).orElse(null);
                    default -> zero(self, method, args);
                };
            });
            YamlConfiguration config = new YamlConfiguration();
            Plugin plugin = proxy(Plugin.class, (self, method, args) -> {
                assertSame(mainThread, Thread.currentThread(), "plugin reads must stay on the main thread");
                return switch (method.getName()) {
                    case "getServer" -> server;
                    case "getConfig" -> config;
                    case "getResource" -> FieldJournalLifecycleTest.class.getResourceAsStream("/" + args[0]);
                    case "getLogger" -> Logger.getAnonymousLogger();
                    default -> zero(self, method, args);
                };
            });
            MessageService text = proxy(MessageService.class, (self, method, args) -> {
                assertSame(mainThread, Thread.currentThread(), "messages must resolve on the main thread");
                return method.getName().equals("text") ? args[1].toString() : zero(self, method, args);
            });
            controller = new FieldJournalController(plugin, text, service);
        }

        private Player newPlayer() {
            return proxy(Player.class, (self, method, args) -> {
                assertSame(mainThread, Thread.currentThread(), "player reads must stay on the main thread");
                return switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "isOnline" -> true;
                    case "getAdvancementProgress" -> {
                        Advancement advancement = (Advancement) args[0];
                        FieldJournalEntry entry = FieldJournalEntry.fromAdvancementKey(advancement.getKey().toString()).orElseThrow();
                        yield proxy(AdvancementProgress.class, (value, operation, inputs) -> {
                            assertSame(mainThread, Thread.currentThread(), "live criteria must stay on the main thread");
                            return switch (operation.getName()) {
                                case "isDone" -> completed.contains(entry);
                                case "getAwardedCriteria" -> completed.contains(entry) ? advancement.getCriteria() : Set.of();
                                default -> zero(value, operation, inputs);
                            };
                        });
                    }
                    case "sendMessage" -> { messages.add(args[0]); yield null; }
                    default -> zero(self, method, args);
                };
            });
        }

        private void drainMain() {
            while (!mainQueue.isEmpty()) mainQueue.removeFirst().run();
        }
    }

    private static final class DeferredService implements FieldJournalService {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public CompletionStage<List<FieldJournalRecord>> records(UUID playerId) {
            throw new AssertionError("controller must reconcile authoritative facts before displaying its snapshot");
        }

        @Override
        public CompletionStage<FieldJournalUpdate> recordCompleted(UUID playerId, Set<FieldJournalEntry> completed) {
            Call call = new Call(completed, new CompletableFuture<>());
            calls.add(call);
            return call.result;
        }

        private void finish(int index) {
            Call call = calls.get(index);
            call.result.complete(new FieldJournalUpdate(call.completed.stream()
                    .map(entry -> new FieldJournalRecord(entry, Instant.EPOCH)).toList(), call.completed));
        }
    }

    private record Call(Set<FieldJournalEntry> completed, CompletableFuture<FieldJournalUpdate> result) {
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object zero(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "field-journal-fixture";
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
