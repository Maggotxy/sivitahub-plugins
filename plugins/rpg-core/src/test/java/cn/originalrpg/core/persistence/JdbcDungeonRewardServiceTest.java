package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.DungeonRewardDelivery;
import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.DungeonRewardSettlement;
import cn.originalrpg.api.model.DungeonTicketCandidate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class JdbcDungeonRewardServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcProfileService profiles;
    private JdbcDungeonRewardService rewards;
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:dungeon_rewards_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(12);
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_profiles (
                        player_uuid VARCHAR(36) PRIMARY KEY, archetype VARCHAR(24) NOT NULL,
                        ascension INTEGER NOT NULL, level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        energy INTEGER NOT NULL, unspent_attribute_points INTEGER NOT NULL,
                        skill_points INTEGER NOT NULL, defense INTEGER NOT NULL, strength INTEGER NOT NULL,
                        agility INTEGER NOT NULL, intelligence INTEGER NOT NULL, stamina INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_skill_point_grants (
                        milestone_key VARCHAR(160) PRIMARY KEY,
                        receipt VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL,
                        milestone_index INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_deliveries (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        item_payload TEXT NOT NULL, source VARCHAR(128) NOT NULL, state VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_dungeon_reward_receipts (
                        run_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        dungeon_id VARCHAR(64) NOT NULL, experience_awarded BIGINT NOT NULL,
                        queued_deliveries INTEGER NOT NULL, ticket_dropped BOOLEAN NOT NULL,
                        ticket_failures INTEGER NOT NULL,
                        settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (run_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_dungeon_ticket_pity (
                        player_uuid VARCHAR(36) NOT NULL, pity_group VARCHAR(64) NOT NULL,
                        failures INTEGER NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, pity_group)
                    )
                    """);
            connection.commit();
        }
        executor = Executors.newFixedThreadPool(10);
        profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        rewards = new JdbcDungeonRewardService(dataSource, executor, profiles);
        playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.WARRIOR).toCompletableFuture().join();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dataSource.close();
    }

    @Test
    void replayCannotDuplicateExperienceItemsOrPity() throws Exception {
        UUID firstRun = UUID.randomUUID();
        DungeonRewardRequest first = request(firstRun, 50L, 0.0, 2);
        DungeonRewardSettlement settled = rewards.settle(first).toCompletableFuture().join();
        DungeonRewardSettlement replayed = rewards.settle(first).toCompletableFuture().join();

        assertEquals(DungeonRewardSettlement.Status.SETTLED, settled.status());
        assertEquals(DungeonRewardSettlement.Status.REPLAYED, replayed.status());
        assertEquals(1, settled.queuedDeliveries());
        assertEquals(1, settled.ticketFailures());
        assertEquals(50L, replayed.profile().experience());
        assertEquals(1, count("rpg_dungeon_reward_receipts"));
        assertEquals(1, count("rpg_market_deliveries"));

        DungeonRewardSettlement guaranteed = rewards.settle(
                request(UUID.randomUUID(), 50L, 0.0, 2)).toCompletableFuture().join();
        assertTrue(guaranteed.ticketDropped());
        assertEquals(0, guaranteed.ticketFailures());
        assertEquals(2, guaranteed.queuedDeliveries());
        assertEquals(100L, guaranteed.profile().experience());
        assertEquals(3, count("rpg_market_deliveries"));
    }

    @Test
    void skillPointMilestonesAreGrantedOncePerDungeonRun() throws Exception {
        // 本地 1 -> 5 过 1 档（本地 3）。用足够的经验一次结算完。
        long grant = experienceToLocal(0, 1, 5);
        DungeonRewardRequest request = request(UUID.randomUUID(), grant, 0.0, 99);

        DungeonRewardSettlement settled = rewards.settle(request).toCompletableFuture().join();
        assertEquals(DungeonRewardSettlement.Status.SETTLED, settled.status());
        // 新档案自带 1 点（建表默认值），本地 3 档位再加 1 点
        assertEquals(2, settled.profile().skillPoints());
        assertEquals(1, count("rpg_skill_point_grants"));

        // 同一 run 重放：receipt 直接判定 REPLAYED，技能点与里程碑流水都不变
        DungeonRewardSettlement replayed = rewards.settle(request).toCompletableFuture().join();
        assertEquals(DungeonRewardSettlement.Status.REPLAYED, replayed.status());
        assertEquals(2, replayed.profile().skillPoints());
        assertEquals(1, count("rpg_skill_point_grants"));
    }

    @Test
    void dungeonSettlementKeepsLocalMilestonesAfterTheStageResets() throws Exception {
        // 档案先走完 0 转本地 30（十个里程碑），再由真实 AscensionService 转到 1 转本地 1。
        // 副本经验不转生，因此这里只钉住「副本结算在 1 转段内仍然照发新段里程碑」。
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_profiles SET skill_points = 11, level = 1, ascension = 1,"
                    + " experience = 0 WHERE player_uuid = '" + playerId + "'");
            connection.commit();
        }
        profiles.evict(playerId);

        // 1 转本地 1 -> 3：新段的首个里程碑必须发 1 点（旧口径 31/3=10 -> 33/3=11 也发，
        // 但 1 转本地 1 -> 2 旧口径会吞：31/3=10 -> 32/3=10，这里把新口径钉住）
        DungeonRewardSettlement settled = rewards.settle(
                request(UUID.randomUUID(), experienceToLocal(1, 1, 3), 0.0, 99))
                .toCompletableFuture().join();

        assertEquals(1, settled.profile().ascension());
        assertEquals(3, settled.profile().level());
        assertEquals(12, settled.profile().skillPoints(), "1 转本地 3 是新段首个里程碑");
        assertEquals(1, count("rpg_skill_point_grants"));
    }

    /** 累计恰好升到同段目标本地等级所需的经验。 */
    private static long experienceToLocal(int ascension, int fromLocal, int targetLocal) {
        long needed = 0L;
        for (int local = fromLocal; local < targetLocal; local++) {
            needed += cn.originalrpg.api.rules.ProgressionRules.experienceToNextLocalLevel(ascension, local);
        }
        return needed;
    }

    @Test
    void completionEvidenceComesOnlyFromDurableReceipt() {
        assertFalse(rewards.hasAnyCompletion(playerId).toCompletableFuture().join());

        rewards.settle(request(UUID.randomUUID(), 25L, 0.0, 5)).toCompletableFuture().join();

        assertTrue(rewards.hasAnyCompletion(playerId).toCompletableFuture().join());
        assertFalse(rewards.hasAnyCompletion(UUID.randomUUID()).toCompletableFuture().join());
        assertFalse(rewards.hasAnyCompletion(null).toCompletableFuture().join());
    }

    @Test
    void concurrentReplayHasOneAuthoritativeMutation() {
        DungeonRewardRequest request = request(UUID.randomUUID(), 75L, 0.0, 5);
        List<CompletableFuture<DungeonRewardSettlement>> calls = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(rewards.settle(request).toCompletableFuture());
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, calls.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == DungeonRewardSettlement.Status.SETTLED).count());
        assertEquals(75L, profiles.get(playerId).toCompletableFuture().join().experience());
        assertEquals(1, countUnchecked("rpg_dungeon_reward_receipts"));
        assertEquals(1, countUnchecked("rpg_market_deliveries"));
    }

    @Test
    void deliveryCollisionRollsBackProfilePityAndReceipt() throws Exception {
        DungeonRewardRequest request = request(UUID.randomUUID(), 60L, 0.0, 3);
        DungeonRewardDelivery delivery = request.deliveries().getFirst();
        try (Connection connection = dataSource.getConnection(); var insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, 'different', 'collision', 'PENDING')
                """)) {
            insert.setString(1, delivery.deliveryId().toString());
            insert.setString(2, playerId.toString());
            insert.executeUpdate();
            connection.commit();
        }

        assertThrows(RuntimeException.class, () -> rewards.settle(request).toCompletableFuture().join());
        assertEquals(0L, profiles.get(playerId).toCompletableFuture().join().experience());
        assertEquals(0, count("rpg_dungeon_reward_receipts"));
        assertEquals(0, count("rpg_dungeon_ticket_pity"));
    }

    private DungeonRewardRequest request(UUID runId, long experience, double chance, int guarantee) {
        DungeonRewardDelivery material = new DungeonRewardDelivery(
                stable(runId, "material"), "material-payload", "dungeon:ancient-ruins:material");
        DungeonRewardDelivery ticket = new DungeonRewardDelivery(
                stable(runId, "ticket"), "ticket-payload", "dungeon:ancient-ruins:hidden-ticket");
        return new DungeonRewardRequest(
                runId,
                "ancient-ruins",
                playerId,
                experience,
                List.of(material),
                Optional.of(new DungeonTicketCandidate(
                        ticket, "ancient-ruins-hidden-ticket", chance, guarantee)));
    }

    private UUID stable(UUID runId, String kind) {
        return UUID.nameUUIDFromBytes((runId + ":" + playerId + ":" + kind).getBytes());
    }

    private int count(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private int countUnchecked(String table) {
        try {
            return count(table);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
