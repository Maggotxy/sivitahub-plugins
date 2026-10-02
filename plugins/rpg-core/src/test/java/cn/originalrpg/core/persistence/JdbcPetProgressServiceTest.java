package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PetProgress;
import cn.originalrpg.api.model.PetProgressResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcPetProgressServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcPetProgressService service;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pet_test_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = Executors.newFixedThreadPool(6);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_pet_progress (
                        pet_uuid VARCHAR(36) PRIMARY KEY, owner_uuid VARCHAR(36) NOT NULL,
                        species_id VARCHAR(64) NOT NULL, personality_id VARCHAR(64) NOT NULL,
                        trait_id VARCHAR(64) NOT NULL, bond_points INTEGER NOT NULL DEFAULT 0,
                        evolution_stage INTEGER NOT NULL DEFAULT 0,
                        selected_skin_id VARCHAR(64) NOT NULL DEFAULT 'natural',
                        battles BIGINT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_pet_collection (
                        player_uuid VARCHAR(36) NOT NULL, species_id VARCHAR(64) NOT NULL,
                        capture_count INTEGER NOT NULL DEFAULT 1,
                        highest_evolution_stage INTEGER NOT NULL DEFAULT 0,
                        first_captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, species_id))
                    """);
            statement.execute("""
                    CREATE TABLE rpg_pet_skins (
                        pet_uuid VARCHAR(36) NOT NULL, skin_id VARCHAR(64) NOT NULL,
                        source VARCHAR(64) NOT NULL,
                        unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (pet_uuid, skin_id))
                    """);
            statement.execute("""
                    CREATE TABLE rpg_pet_bond_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        pet_uuid VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        source VARCHAR(32) NOT NULL, amount INTEGER NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            connection.commit();
        }
        service = new JdbcPetProgressService(dataSource, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void captureIsIdempotentAndCodexCountsDistinctPetInstances() {
        UUID owner = UUID.randomUUID();
        UUID firstPet = UUID.randomUUID();
        PetProgress first = service.registerCapture(owner, firstPet, "wolf", "loyal", "ironhide")
                .toCompletableFuture().join();
        service.registerCapture(owner, firstPet, "wolf", "loyal", "ironhide").toCompletableFuture().join();
        service.registerCapture(owner, UUID.randomUUID(), "wolf", "calm", "quickstep")
                .toCompletableFuture().join();

        assertEquals("natural", first.selectedSkinId());
        assertEquals(1, first.unlockedSkinIds().size());
        assertEquals(2, service.collection(owner).toCompletableFuture().join().getFirst().captureCount());
    }

    @Test
    void bondReceiptCannotBeReplayedAndEvolutionUnlocksButDoesNotForceSkin() {
        UUID owner = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        service.registerCapture(owner, pet, "fox", "brave", "keen_eye").toCompletableFuture().join();

        PetProgressResult awarded = service.rewardBond(owner, pet, 100, "combat", "combat-receipt-0001")
                .toCompletableFuture().join();
        PetProgressResult duplicate = service.rewardBond(owner, pet, 100, "combat", "combat-receipt-0001")
                .toCompletableFuture().join();
        assertEquals(100, awarded.progress().orElseThrow().bond());
        assertEquals(PetProgressResult.Status.DUPLICATE, duplicate.status());

        service.rewardBond(owner, pet, 100, "milestone", "milestone-receipt-1").toCompletableFuture().join();
        service.rewardBond(owner, pet, 50, "feeding", "feeding-receipt-01").toCompletableFuture().join();
        PetProgressResult evolved = service.evolve(owner, pet, 0, 250, "resonant")
                .toCompletableFuture().join();
        assertEquals(PetProgressResult.Status.SUCCESS, evolved.status());
        assertTrue(evolved.progress().orElseThrow().unlockedSkinIds().contains("resonant"));
        assertEquals("natural", evolved.progress().orElseThrow().selectedSkinId());

        PetProgressResult selected = service.selectSkin(owner, pet, "resonant").toCompletableFuture().join();
        assertEquals("resonant", selected.progress().orElseThrow().selectedSkinId());
    }

    @Test
    void boundedExecutorSettlesOneHundredTwentyEightPetsWithoutQueueRunaway() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            UUID owner = UUID.randomUUID();
            List<UUID> petIds = new ArrayList<>();
            List<CompletableFuture<?>> captures = new ArrayList<>();
            for (int index = 0; index < 128; index++) {
                UUID petId = UUID.randomUUID();
                petIds.add(petId);
                captures.add(service.registerCapture(owner, petId, "wolf", "calm", "quickstep")
                        .toCompletableFuture());
            }
            CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new)).join();

            List<CompletableFuture<?>> rewards = new ArrayList<>();
            for (int index = 0; index < petIds.size(); index++) {
                UUID petId = petIds.get(index);
                rewards.add(service.rewardBond(owner, petId, 2, "combat", "load-test:" + petId)
                        .toCompletableFuture());
            }
            CompletableFuture.allOf(rewards.toArray(CompletableFuture[]::new)).join();
            assertEquals(128, service.collection(owner).toCompletableFuture().join().getFirst().captureCount());
        });
    }

    @Test
    void permanentPetCountersSaturateInsteadOfOverflowing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        service.registerCapture(owner, pet, "wolf", "calm", "quickstep").toCompletableFuture().join();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_pet_progress SET battles = 1000000000 WHERE pet_uuid = '" + pet + "'");
            statement.execute("UPDATE rpg_pet_collection SET capture_count = 1000000000 WHERE player_uuid = '"
                    + owner + "' AND species_id = 'wolf'");
            connection.commit();
        }

        PetProgressResult battle = service.rewardBond(
                owner, pet, 1, "combat", "counter-cap-battle").toCompletableFuture().join();
        service.registerCapture(owner, UUID.randomUUID(), "wolf", "brave", "keen_eye")
                .toCompletableFuture().join();

        assertEquals(PetProgressResult.Status.SUCCESS, battle.status());
        assertEquals(1_000_000_000L, battle.progress().orElseThrow().battles());
        assertEquals(1_000_000_000,
                service.collection(owner).toCompletableFuture().join().getFirst().captureCount());
    }
}
