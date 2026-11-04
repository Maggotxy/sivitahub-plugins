package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.ProgressionRules;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JdbcExperienceGrantServiceTest {
    @Test
    void commitThenLostResponseReplaysWithoutAwardingExperienceOrSkillPointsTwice() throws Exception {
        try (RuntimeDatabaseFixture fixture = new RuntimeDatabaseFixture()) {
            fixture.database.migrate();
            UUID player = UUID.randomUUID();
            UUID grant = UUID.randomUUID();
            long amount = milestoneXp();
            JdbcProfileService failing = new JdbcProfileService(
                    failFirstCommit(fixture.dataSource, true), Runnable::run, 100, 2);

            assertThrows(CompletionException.class,
                    () -> failing.grantExperienceOnce(player, grant, amount).toCompletableFuture().join());
            assertEquals(1L, fixture.count("rpg_experience_grants"), "The injected error occurs after the real commit");
            assertEquals(1L, fixture.count("rpg_skill_point_grants"));

            JdbcProfileService restarted = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            PlayerProfile replay = restarted.grantExperienceOnce(player, grant, amount).toCompletableFuture().join();
            assertEquals(3, replay.level());
            assertEquals(17L, replay.experience());
            assertEquals(2, replay.skillPoints());
            assertEquals(replay, restarted.grantExperienceOnce(player, grant, amount).toCompletableFuture().join());
            assertEquals(1L, fixture.count("rpg_experience_grants"));
            assertEquals(1L, fixture.count("rpg_skill_point_grants"));
        }
    }

    @Test
    void failureBeforeCommitRollsBackTheReceiptProfileAndMilestonesTogether() throws Exception {
        try (RuntimeDatabaseFixture fixture = new RuntimeDatabaseFixture()) {
            fixture.database.migrate();
            UUID player = UUID.randomUUID();
            UUID grant = UUID.randomUUID();
            JdbcProfileService failing = new JdbcProfileService(
                    failFirstCommit(fixture.dataSource, false), Runnable::run, 100, 2);

            assertThrows(CompletionException.class,
                    () -> failing.grantExperienceOnce(player, grant, milestoneXp()).toCompletableFuture().join());
            assertEquals(0L, fixture.count("rpg_experience_grants"));
            assertEquals(0L, fixture.count("rpg_skill_point_grants"));
            assertEquals(0L, fixture.count("rpg_profiles"));

            PlayerProfile retried = failing.grantExperienceOnce(player, grant, milestoneXp()).toCompletableFuture().join();
            assertEquals(3, retried.level());
            assertEquals(17L, retried.experience());
            assertEquals(2, retried.skillPoints());
            assertEquals(1L, fixture.count("rpg_experience_grants"));
        }
    }

    @Test
    void reusedGrantIdentityRejectsChangedAmountOrPlayerWithoutChangingBalances() throws Exception {
        try (RuntimeDatabaseFixture fixture = new RuntimeDatabaseFixture()) {
            fixture.database.migrate();
            JdbcProfileService profiles = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            UUID player = UUID.randomUUID();
            UUID grant = UUID.randomUUID();
            PlayerProfile first = profiles.grantExperienceOnce(player, grant, 12L).toCompletableFuture().join();

            assertThrows(CompletionException.class,
                    () -> profiles.grantExperienceOnce(player, grant, 13L).toCompletableFuture().join());
            assertThrows(CompletionException.class,
                    () -> profiles.grantExperienceOnce(UUID.randomUUID(), grant, 12L).toCompletableFuture().join());

            profiles.evict(player);
            assertEquals(first, profiles.get(player).toCompletableFuture().join());
            assertEquals(1L, fixture.count("rpg_profiles"));
            assertEquals(1L, fixture.count("rpg_experience_grants"));
        }
    }

    @Test
    void concurrentCopiesOfOneGrantAndANewGrantHaveOnlyTwoEffects() throws Exception {
        try (RuntimeDatabaseFixture fixture = new RuntimeDatabaseFixture();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.database.migrate();
            JdbcProfileService profiles = new JdbcProfileService(fixture.dataSource, executor, 100, 2);
            UUID player = UUID.randomUUID();
            UUID grant = UUID.randomUUID();
            profiles.get(player).toCompletableFuture().join();
            CompletableFuture<?>[] copies = IntStream.range(0, 20)
                    .mapToObj(ignored -> profiles.grantExperienceOnce(player, grant, 4_000L).toCompletableFuture())
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<PlayerProfile> fresh = profiles.grantExperienceOnce(
                    player, UUID.randomUUID(), 80L).toCompletableFuture();
            CompletableFuture.allOf(copies).join();
            fresh.join();
            profiles.evict(player);
            PlayerProfile after = profiles.get(player).toCompletableFuture().join();

            assertEquals(4_080L, ProgressionRules.stageExperienceSpent(after.ascension(), after.level(), after.experience()));
            assertEquals(1 + after.level() / ProgressionRules.LOCAL_LEVELS_PER_SKILL_POINT, after.skillPoints());
            assertEquals(2L, fixture.count("rpg_experience_grants"));
        }
    }

    private static long milestoneXp() {
        return ProgressionRules.experienceToNextLocalLevel(0, 1)
                + ProgressionRules.experienceToNextLocalLevel(0, 2) + 17L;
    }

    private static DataSource failFirstCommit(DataSource real, boolean afterCommit) {
        AtomicBoolean fail = new AtomicBoolean(true);
        return (DataSource) Proxy.newProxyInstance(JdbcExperienceGrantServiceTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
                    Object value = invoke(real, method, arguments);
                    if (!(value instanceof Connection connection)) return value;
                    return Proxy.newProxyInstance(JdbcExperienceGrantServiceTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (connectionProxy, operation, parameters) -> {
                                if (operation.getName().equals("commit") && fail.compareAndSet(true, false)) {
                                    if (afterCommit) invoke(connection, operation, parameters);
                                    throw new SQLException(afterCommit ? "Committed but response was lost" : "Commit rejected");
                                }
                                return invoke(connection, operation, parameters);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
