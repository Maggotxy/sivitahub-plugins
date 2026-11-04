package cn.originalrpg.mobs;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.ProfileService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the actual flush/shutdown code; Paper objects only supply configuration and an offline session. */
final class ExperienceWriteBufferTest {
    @Test
    void retryRetainsTheFrozenIdentityAndDoesNotMergeExperienceEarnedLater() {
        UUID playerId = UUID.randomUUID();
        Player player = offlineSession(playerId);
        RecordingProfiles authority = new RecordingProfiles();
        AtomicLong clock = new AtomicLong(1_000L);
        ExperienceWriteBuffer buffer = new ExperienceWriteBuffer(plugin(), authority.service(), null, clock::get);
        assertTrue(buffer.add(player, 100L));
        buffer.flushReady();
        Attempt original = authority.attempts.getFirst();
        original.completion().completeExceptionally(new IllegalStateException("commit response lost"));

        assertTrue(buffer.add(player, 50L));
        clock.set(2_000L);
        buffer.flushReady(); // Drains the failed completion and arms its bounded retry delay.
        clock.set(2_100L);
        buffer.flushReady();

        assertEquals(2, authority.attempts.size());
        Attempt replay = authority.attempts.get(1);
        assertEquals(original.grantId(), replay.grantId());
        assertEquals(100L, replay.amount());
        replay.completion().complete(profile(playerId, 100L));
        clock.set(2_200L);
        buffer.flushReady();

        assertEquals(3, authority.attempts.size());
        Attempt fresh = authority.attempts.get(2);
        assertNotEquals(original.grantId(), fresh.grantId());
        assertEquals(50L, fresh.amount());
        assertEquals(playerId, fresh.playerId());
        fresh.completion().complete(profile(playerId, 150L));
        buffer.shutdown();
        assertEquals(3, authority.attempts.size(), "Already acknowledged grants need no shutdown replay");
    }

    @Test
    void shutdownReplaysFailedGrantAndNewExperienceWithSeparateStableIdentities() {
        UUID playerId = UUID.randomUUID();
        Player player = offlineSession(playerId);
        RecordingProfiles authority = new RecordingProfiles();
        ExperienceWriteBuffer buffer = new ExperienceWriteBuffer(plugin(), authority.service(), null);
        assertTrue(buffer.add(player, 100L));
        buffer.flushReady();
        Attempt original = authority.attempts.getFirst();
        original.completion().completeExceptionally(new IllegalStateException("committed response lost"));
        assertTrue(buffer.add(player, 25L));
        authority.completeNewAttempts = true;

        buffer.shutdown();

        assertEquals(3, authority.attempts.size());
        List<Attempt> retried = authority.attempts.stream().filter(attempt -> attempt.amount() == 100L).toList();
        assertEquals(2, retried.size());
        assertEquals(original.grantId(), retried.getLast().grantId());
        Attempt newBatch = authority.attempts.stream().filter(attempt -> attempt.amount() == 25L).findFirst().orElseThrow();
        assertNotEquals(original.grantId(), newBatch.grantId());
    }

    private static Plugin plugin() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("experience-write-buffer.retry-base-ticks", 1L);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return (Plugin) Proxy.newProxyInstance(ExperienceWriteBufferTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "getLogger" -> logger;
                    case "isEnabled" -> true;
                    case "toString" -> "XP buffer test plugin";
                    default -> throw new AssertionError("Unexpected plugin call: " + method.getName());
                });
    }

    private static Player offlineSession(UUID playerId) {
        return (Player) Proxy.newProxyInstance(ExperienceWriteBufferTest.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "isOnline" -> false;
                    case "toString" -> "offline XP test session";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("Unexpected player call: " + method.getName());
                });
    }

    private static PlayerProfile profile(UUID playerId, long experience) {
        return new PlayerProfile(playerId, Archetype.UNCHOSEN, 0, 1, experience,
                100, 100, 5, 1, Map.of(CoreAttribute.STAMINA, 0));
    }

    private record Attempt(UUID playerId, UUID grantId, long amount, CompletableFuture<PlayerProfile> completion) { }

    private static final class RecordingProfiles {
        final List<Attempt> attempts = new ArrayList<>();
        boolean completeNewAttempts;

        ProfileService service() {
            return (ProfileService) Proxy.newProxyInstance(ExperienceWriteBufferTest.class.getClassLoader(),
                    new Class<?>[]{ProfileService.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("cached")) return Optional.empty();
                        if (!method.getName().equals("grantExperienceOnce")) {
                            throw new AssertionError("Buffer used an unexpected/unsafe profile method: " + method.getName());
                        }
                        Attempt attempt = new Attempt((UUID) arguments[0], (UUID) arguments[1],
                                (Long) arguments[2], new CompletableFuture<>());
                        attempts.add(attempt);
                        if (completeNewAttempts) attempt.completion().complete(profile(attempt.playerId(), attempt.amount()));
                        return attempt.completion();
                    });
        }
    }
}
