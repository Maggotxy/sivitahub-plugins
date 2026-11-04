package cn.originalrpg.community;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CivilizationProject;
import cn.originalrpg.api.model.CivilizationSeason;
import cn.originalrpg.api.service.CivilizationHistoryService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class CivilizationArchiveCommandTest {
    @Test
    void unauthenticatedSessionsAndInvalidPagesNeverQueryHistory() {
        Fixture f = new Fixture();
        f.command.onCommand(f.player, null, "civilization", new String[0]);
        assertEquals(0, f.service.calls);
        f.ready();
        f.command.onCommand(f.player, null, "civilization", new String[]{"projects", "1001"});
        assertEquals(0, f.service.calls);
        assertTrue(f.keys.contains("civilization.usage"));
    }

    @Test
    void concurrentClicksAreBoundedAndCompletionOnlyRendersOnTheScheduledThread() {
        Fixture f = new Fixture(); f.ready();
        f.open(); f.open();
        assertEquals(1, f.service.calls);
        assertTrue(f.keys.contains("civilization.pending"));
        f.keys.clear();
        f.service.projects.complete(List.of());
        assertTrue(f.keys.isEmpty());
        assertEquals(1, f.tasks.size());
        f.drain();
        assertTrue(f.keys.contains("civilization.projects-title"));
        assertTrue(f.keys.contains("civilization.empty"));
    }

    @Test
    void revocationAndReauthenticationDiscardThePreviousRequest() {
        Fixture f = new Fixture(); f.ready(); f.open();
        f.command.onRevoked(new AuthenticatedPlayerRevokedEvent(f.player));
        f.ready();
        f.keys.clear();
        f.service.projects.complete(List.of()); f.drain();
        assertTrue(f.keys.isEmpty());
    }

    @Test
    void closeDiscardsPendingPagesWithoutInventingAnEmptySuccess() {
        Fixture f = new Fixture(); f.ready(); f.open();
        f.command.close(); f.keys.clear();
        f.service.projects.complete(List.of()); f.drain();
        assertTrue(f.keys.isEmpty());
    }

    @Test
    void databaseFailureIsVisibleAndAllowsAReadRetry() {
        Fixture f = new Fixture(); f.ready(); f.open();
        f.service.projects.completeExceptionally(new IllegalStateException("fixture database unavailable"));
        f.drain();
        assertTrue(f.keys.contains("civilization.failed"));
        assertFalse(f.keys.contains("civilization.empty"));
        f.service.projects = new CompletableFuture<>();
        f.open();
        assertEquals(2, f.service.calls);
    }

    private static final class Fixture {
        final List<String> keys = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        final FakeHistory service = new FakeHistory();
        final Player player;
        final CivilizationArchiveCommand command;
        Fixture() {
            UUID id = UUID.randomUUID();
            player = proxy(Player.class, (self, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isOnline", "hasPermission" -> true;
                case "getName" -> "archive-fixture";
                default -> defaultValue(method.getReturnType());
            });
            BukkitTask task = proxy(BukkitTask.class, (self, method, args) -> defaultValue(method.getReturnType()));
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (self, method, args) -> {
                if (method.getName().equals("runTask")) { tasks.add((Runnable) args[1]); return task; }
                return defaultValue(method.getReturnType());
            });
            Server server = proxy(Server.class, (self, method, args) -> method.getName().equals("getScheduler")
                    ? scheduler : defaultValue(method.getReturnType()));
            Plugin plugin = proxy(Plugin.class, (self, method, args) -> switch (method.getName()) {
                case "getServer" -> server;
                case "isEnabled" -> true;
                case "getLogger" -> Logger.getLogger("civilization-test");
                default -> defaultValue(method.getReturnType());
            });
            MessageService messages = proxy(MessageService.class, (self, method, args) -> {
                if (method.getName().equals("text")) { String key = (String) args[1]; keys.add(key); return key; }
                return defaultValue(method.getReturnType());
            });
            command = new CivilizationArchiveCommand(plugin, service, messages);
        }
        void ready() { command.onReady(new AuthenticatedPlayerReadyEvent(player)); }
        void open() { command.onCommand(player, null, "civilization", new String[]{"projects"}); }
        void drain() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    }

    private static final class FakeHistory implements CivilizationHistoryService {
        int calls;
        CompletableFuture<List<CivilizationProject>> projects = new CompletableFuture<>();
        @Override public CompletionStage<List<CivilizationProject>> completedProjects(int page) { calls++; return projects; }
        @Override public CompletionStage<List<CivilizationSeason>> completedSeasons(int page) {
            calls++; return CompletableFuture.completedFuture(List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
