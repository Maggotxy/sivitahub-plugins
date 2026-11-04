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
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the actual core receipt adapter; the durable queue, not this UI adapter, owns retries. */
final class ExperienceWriteBufferTest {
    @Test
    void anUncertainCommitCanOnlyBeRetriedWithTheJournalOwnedIdentityAndAmount() {
        UUID playerId = UUID.randomUUID();
        Player player = offlineSession(playerId);
        RecordingProfiles authority = new RecordingProfiles();
        ExperienceWriteBuffer buffer = new ExperienceWriteBuffer(plugin(), authority.service(), null);
        MobRewardIntent first = MobRewardFixtures.reward(playerId, 100L, 0);
        MobRewardIntent later = MobRewardFixtures.reward(playerId, 50L, 0);
        CompletableFuture<Boolean> uncertain = buffer.apply(first, player).toCompletableFuture();
        Attempt original = authority.attempts.getFirst();
        original.completion().completeExceptionally(new IllegalStateException("commit response lost"));
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class, uncertain::join);
        CompletableFuture<Boolean> retried = buffer.apply(first, player).toCompletableFuture();
        Attempt replay = authority.attempts.getLast();
        assertEquals(first.experienceGrantId(), original.grantId());
        assertEquals(original.grantId(), replay.grantId());
        assertEquals(100L, replay.amount());
        replay.completion().complete(profile(playerId, 100L));
        assertTrue(retried.join());
        CompletableFuture<Boolean> fresh = buffer.apply(later, player).toCompletableFuture();
        Attempt next = authority.attempts.getLast();
        assertNotEquals(original.grantId(), next.grantId());
        assertEquals(later.experienceGrantId(), next.grantId());
        assertEquals(50L, next.amount());
        next.completion().complete(profile(playerId, 150L));
        assertTrue(fresh.join());
        buffer.drainFeedback(); // Offline sessions cannot receive stale notifications.
        buffer.shutdown();
        assertEquals(3, authority.attempts.size(), "Shutdown must not invent an independent memory-only replay");
    }

    @Test
    void unresolvedOrRejectedQualificationNeverCallsTheXpAuthority() {
        UUID playerId = UUID.randomUUID();
        RecordingProfiles authority = new RecordingProfiles();
        ExperienceWriteBuffer buffer = new ExperienceWriteBuffer(plugin(), authority.service(), null);
        MobRewardIntent candidate = MobRewardFixtures.unknown(MobRewardFixtures.reward(playerId, 100L, 1));
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class,
                () -> buffer.apply(candidate, null).toCompletableFuture().join());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class,
                () -> buffer.apply(candidate.resolve(100), null).toCompletableFuture().join());
        assertTrue(authority.attempts.isEmpty());
        buffer.shutdown();
    }

    @Test
    void revokedFeedbackSessionAndShutdownCannotTriggerNewAuthorityWork() {
        UUID playerId = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return playerId;
                    throw new AssertionError("Revoked session must never be probed by a late UI callback: " + method.getName());
                });
        RecordingProfiles authority = new RecordingProfiles();
        ExperienceWriteBuffer buffer = new ExperienceWriteBuffer(plugin(), authority.service(), null);
        MobRewardIntent intent = MobRewardFixtures.reward(playerId, 25L, 0);
        CompletableFuture<Boolean> first = buffer.apply(intent, player).toCompletableFuture();
        buffer.forgetSession(player);
        authority.attempts.getFirst().completion().complete(profile(playerId, 25L));
        assertTrue(first.join(), "Session revocation does not revoke an accepted durable reward");
        buffer.drainFeedback();
        CompletableFuture<Boolean> offlineReplay = buffer.apply(intent, null).toCompletableFuture();
        buffer.shutdown();
        authority.attempts.getLast().completion().complete(profile(playerId, 25L));
        assertTrue(offlineReplay.join());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class,
                () -> buffer.apply(intent, null).toCompletableFuture().join());
        assertEquals(2, authority.attempts.size());
        org.junit.jupiter.api.Assertions.assertFalse(ExperienceWriteBuffer.sameSession(player, offlineSession(playerId), true));
    }

    @Test
    void anAuthorityResponseForAnotherPlayerIsNotAnAcknowledgement() {
        UUID playerId = UUID.randomUUID();
        RecordingProfiles authority = new RecordingProfiles();
        ExperienceWriteBuffer buffer = new ExperienceWriteBuffer(plugin(), authority.service(), null);
        CompletableFuture<Boolean> result = buffer.apply(MobRewardFixtures.reward(playerId, 10L, 0), null).toCompletableFuture();
        authority.attempts.getFirst().completion().complete(profile(UUID.randomUUID(), 10L));
        org.junit.jupiter.api.Assertions.assertFalse(result.join());
        buffer.shutdown();
    }

    private static Plugin plugin() {
        YamlConfiguration config = new YamlConfiguration();
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
                        return attempt.completion();
                    });
        }
    }
}
