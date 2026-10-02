package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.CommerceResult;
import cn.originalrpg.api.model.CommerceStatus;
import cn.originalrpg.api.model.ActivityRewardResult;
import cn.originalrpg.api.model.ActivityRewardRequest;
import cn.originalrpg.api.model.CommercePurchaseResult;
import cn.originalrpg.api.model.CreditResult;
import cn.originalrpg.api.model.CurrencyOperationResult;
import cn.originalrpg.api.model.CurrencyExchangeQuote;
import cn.originalrpg.api.model.CurrencyExchangeResult;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.EconomyPurchaseResult;
import cn.originalrpg.api.model.RewardClaimResult;
import cn.originalrpg.api.model.MarketOperationResult;
import cn.originalrpg.api.model.TransactionResult;
import cn.originalrpg.api.model.SkillBookRedemption;
import cn.originalrpg.api.model.SkillProgress;
import cn.originalrpg.api.model.SupplyPurchaseResult;
import cn.originalrpg.api.model.MerchantTransactionResult;
import cn.originalrpg.api.model.ClassBranchResult;
import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.model.ChapterDefinition;
import cn.originalrpg.api.model.ChapterTransitionResult;
import cn.originalrpg.api.model.CommissionEvidenceClaimResult;
import cn.originalrpg.api.model.AscensionEvidence;
import cn.originalrpg.api.model.AscensionResult;
import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.model.HiddenPathActivationResult;
import cn.originalrpg.api.service.DungeonEntryService;
import cn.originalrpg.api.service.ProfileService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.ZoneId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcServicesTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:rpg_test_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
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
                        source VARCHAR(96) NOT NULL, idempotency_key VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL, from_local_level INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (source, idempotency_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_chapter_state (
                        singleton_id INTEGER PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        chapter_id VARCHAR(32) NOT NULL, chapter_sequence INTEGER NOT NULL,
                        max_open_ascension INTEGER NOT NULL, revision BIGINT NOT NULL,
                        changed_by VARCHAR(64) NOT NULL, reason VARCHAR(128) NOT NULL,
                        changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_chapter_history (
                        id VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        chapter_id VARCHAR(32) NOT NULL, chapter_sequence INTEGER NOT NULL,
                        max_open_ascension INTEGER NOT NULL, revision BIGINT NOT NULL,
                        changed_by VARCHAR(64) NOT NULL, reason VARCHAR(128) NOT NULL,
                        changed_at TIMESTAMP NOT NULL,
                        UNIQUE (season_id, revision)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_accounts (
                        player_uuid VARCHAR(36) PRIMARY KEY, balance DECIMAL(19, 2) NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36), amount DECIMAL(19, 2) NOT NULL,
                        fee DECIMAL(19, 2) NOT NULL, reason VARCHAR(128) NOT NULL,
                        reversal_of VARCHAR(96) UNIQUE,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_commerce_accounts (
                        player_uuid VARCHAR(36) PRIMARY KEY, lifetime_paid DECIMAL(19, 2) NOT NULL, credits BIGINT NOT NULL,
                        member_until TIMESTAMP NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_credit_ledger (
                        id VARCHAR(36) PRIMARY KEY, external_reference VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, delta BIGINT NOT NULL,
                        operator_name VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_starlight_operation_limits (
                        scope_id VARCHAR(16) NOT NULL, period_type VARCHAR(8) NOT NULL,
                        period_key VARCHAR(16) NOT NULL, granted BIGINT NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (scope_id, period_type, period_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_commerce_purchases (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, product_id VARCHAR(64) NOT NULL,
                        period_key VARCHAR(32) NOT NULL DEFAULT 'legacy',
                        credit_cost BIGINT NOT NULL, gold_cost DECIMAL(19, 2) NOT NULL DEFAULT 0,
                        delivery_id VARCHAR(36) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_payment_ledger (
                        id VARCHAR(36) PRIMARY KEY, external_reference VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, amount DECIMAL(19, 2) NOT NULL,
                        membership_days INTEGER NOT NULL, operator_name VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_commerce_reward_claims (
                        player_uuid VARCHAR(36) NOT NULL, reward_id VARCHAR(64) NOT NULL,
                        required_lifetime_paid DECIMAL(19, 2) NOT NULL,
                        claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, reward_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_listings (
                        id VARCHAR(36) PRIMARY KEY, seller_uuid VARCHAR(36) NOT NULL,
                        item_payload TEXT NOT NULL, price DECIMAL(19, 2) NOT NULL,
                        fee_rate DECIMAL(8, 6) NOT NULL, state VARCHAR(16) NOT NULL,
                        buyer_uuid VARCHAR(36), item_identity VARCHAR(36), open_item_identity VARCHAR(36),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        completed_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("CREATE UNIQUE INDEX uq_test_market_open_item ON rpg_market_listings (open_item_identity)");
            statement.execute("""
                    CREATE TABLE rpg_market_deliveries (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        item_payload TEXT NOT NULL, source VARCHAR(96) NOT NULL,
                        state VARCHAR(16) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        delivered_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_equipment_states (
                        item_id VARCHAR(36) PRIMARY KEY, state VARCHAR(24) NOT NULL,
                        owner_uuid VARCHAR(36), last_operation VARCHAR(24),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_equipment_operations (
                        operation_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        kind VARCHAR(24) NOT NULL, item_id VARCHAR(36), input_fingerprint VARCHAR(64) NOT NULL,
                        request_payload TEXT NOT NULL, gold_cost DECIMAL(19, 2) NOT NULL,
                        debit_key VARCHAR(96) UNIQUE NOT NULL, state VARCHAR(40) NOT NULL,
                        queued_deliveries INTEGER NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        settled_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_progression_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, merit BIGINT NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_activity_daily (
                        player_uuid VARCHAR(36) NOT NULL, activity VARCHAR(24) NOT NULL, activity_date DATE NOT NULL,
                        gold_earned DECIMAL(19, 2) NOT NULL, merit_earned BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, activity, activity_date)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_activity_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, activity VARCHAR(24) NOT NULL,
                        requested_gold DECIMAL(19, 2) NOT NULL, granted_gold DECIMAL(19, 2) NOT NULL,
                        requested_merit BIGINT NOT NULL, granted_merit BIGINT NOT NULL,
                        reason VARCHAR(128) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_commission_evidence_daily (
                        player_uuid VARCHAR(36) NOT NULL, evidence_date DATE NOT NULL, earned INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, evidence_date)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_commission_evidence_sources (
                        player_uuid VARCHAR(36) NOT NULL, evidence_date DATE NOT NULL,
                        source VARCHAR(64) NOT NULL, earned INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, evidence_date, source)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_commission_claims (
                        receipt_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        evidence_date DATE NOT NULL, commission_id VARCHAR(64) NOT NULL,
                        evidence_cost INTEGER NOT NULL, state VARCHAR(12) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_activity_purchases (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, gold_cost DECIMAL(19, 2) NOT NULL,
                        merit_cost BIGINT NOT NULL, reason VARCHAR(128) NOT NULL, delivery_id VARCHAR(36) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_merit_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, amount BIGINT NOT NULL,
                        direction VARCHAR(8) NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_skills (
                        player_uuid VARCHAR(36) NOT NULL, skill_id VARCHAR(64) NOT NULL,
                        level INTEGER NOT NULL, point_cost INTEGER NOT NULL DEFAULT 0,
                        variant VARCHAR(64), PRIMARY KEY (player_uuid, skill_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_skill_book_uses (
                        instance_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        book_id VARCHAR(64) NOT NULL, limit_group VARCHAR(64), points_granted INTEGER NOT NULL,
                        used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_energy_recovery_quotas (
                        player_uuid VARCHAR(36) NOT NULL, source_id VARCHAR(64) NOT NULL,
                        period_key VARCHAR(32) NOT NULL, uses INTEGER NOT NULL,
                        energy_restored INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, source_id, period_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_dungeon_energy_reservations (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        energy_cost INTEGER NOT NULL, state VARCHAR(16) NOT NULL,
                        owner_node VARCHAR(64), owner_process VARCHAR(36),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, settled_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_dungeon_ticket_escrows (
                        reservation_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        ticket_id VARCHAR(64) NOT NULL, item_payload TEXT NOT NULL,
                        state VARCHAR(16) NOT NULL, delivery_id VARCHAR(36),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, settled_at TIMESTAMP NULL,
                        FOREIGN KEY (reservation_id) REFERENCES rpg_dungeon_energy_reservations(id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_node_leases (
                        node_id VARCHAR(64) PRIMARY KEY, process_token VARCHAR(36) NOT NULL,
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_merchant_limits (
                        player_uuid VARCHAR(36) NOT NULL, offer_id VARCHAR(64) NOT NULL,
                        period_key VARCHAR(32) NOT NULL, direction VARCHAR(12) NOT NULL,
                        used_units INTEGER NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, offer_id, period_key, direction)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_merchant_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(80) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, offer_id VARCHAR(64) NOT NULL,
                        period_key VARCHAR(32) NOT NULL, direction VARCHAR(12) NOT NULL,
                        units INTEGER NOT NULL, gold_delta DECIMAL(19, 2) NOT NULL,
                        delivery_id VARCHAR(36), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_class_branches (
                        player_uuid VARCHAR(36) NOT NULL, stage INTEGER NOT NULL,
                        branch_id VARCHAR(64) NOT NULL, selected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, stage)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_ascension_receipts (
                        player_uuid VARCHAR(36) NOT NULL, target_ascension INTEGER NOT NULL,
                        archetype VARCHAR(24) NOT NULL, previous_branch_selected BOOLEAN NOT NULL,
                        journey_complete BOOLEAN NOT NULL, equipment_score INTEGER NOT NULL,
                        equipment_ids VARCHAR(640) NOT NULL, learned_skills INTEGER NOT NULL,
                        dungeon_clears BIGINT NOT NULL, merit_spent BIGINT NOT NULL,
                        ascended_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, target_ascension)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_dungeon_reward_receipts (
                        run_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        dungeon_id VARCHAR(64) NOT NULL, experience_awarded BIGINT NOT NULL,
                        queued_deliveries INTEGER NOT NULL, ticket_dropped BOOLEAN NOT NULL,
                        ticket_failures INTEGER NOT NULL, settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (run_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_hidden_path_caps (
                        path VARCHAR(32) PRIMARY KEY, capacity INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_hidden_paths (
                        player_uuid VARCHAR(36) PRIMARY KEY, path VARCHAR(32) NOT NULL,
                        activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_hidden_path_audit (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        path VARCHAR(32) NOT NULL, action VARCHAR(24) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_currency_exchange_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(80) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, route_id VARCHAR(48) NOT NULL,
                        units BIGINT NOT NULL, gold_delta DECIMAL(19, 2) NOT NULL,
                        merit_delta BIGINT NOT NULL, starlight_delta BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_currency_exchange_limits (
                        player_uuid VARCHAR(36) NOT NULL, route_id VARCHAR(48) NOT NULL,
                        period_type VARCHAR(8) NOT NULL, period_key VARCHAR(16) NOT NULL,
                        used_units BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, route_id, period_type, period_key)
                    )
                    """);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void profileMutationsArePersistedAndEnergyIsAtomic() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();

        var created = profiles.get(playerId).toCompletableFuture().join();
        assertEquals(Archetype.UNCHOSEN, created.archetype());
        assertEquals(100, created.energy());

        var allocated = profiles.allocateAttribute(playerId, CoreAttribute.STAMINA).toCompletableFuture().join();
        assertEquals(1, allocated.attribute(CoreAttribute.STAMINA));
        assertEquals(102, allocated.energyCap());
        assertTrue(profiles.spendEnergy(playerId, 90).toCompletableFuture().join());
        assertFalse(profiles.spendEnergy(playerId, 20).toCompletableFuture().join());
        assertEquals(10, profiles.get(playerId).toCompletableFuture().join().energy());
    }

    @Test
    void starterBuildCorrectionIsAtomicIdempotentAndPreservesEarnedPoints() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID mage = UUID.randomUUID();
        profiles.chooseArchetype(mage, Archetype.MAGE).toCompletableFuture().join();
        for (int index = 0; index < 5; index++) {
            profiles.allocateAttribute(mage, CoreAttribute.STRENGTH).toCompletableFuture().join();
        }

        var corrected = profiles.prepareStarterBuild(mage).toCompletableFuture().join();
        assertEquals(4, corrected.attribute(CoreAttribute.INTELLIGENCE));
        assertEquals(1, corrected.attribute(CoreAttribute.STAMINA));
        assertEquals(0, corrected.attribute(CoreAttribute.STRENGTH));
        assertEquals(0, corrected.unspentAttributePoints());
        var repeated = profiles.prepareStarterBuild(mage).toCompletableFuture().join();
        assertEquals(4, repeated.attribute(CoreAttribute.INTELLIGENCE));
        assertEquals(1, repeated.attribute(CoreAttribute.STAMINA));
        assertEquals(0, repeated.attribute(CoreAttribute.STRENGTH));
        assertEquals(0, repeated.unspentAttributePoints());

        UUID warrior = UUID.randomUUID();
        profiles.chooseArchetype(warrior, Archetype.WARRIOR).toCompletableFuture().join();
        for (int index = 0; index < 5; index++) {
            profiles.allocateAttribute(warrior, CoreAttribute.DEFENSE).toCompletableFuture().join();
        }
        profiles.grantExperience(warrior,
                cn.originalrpg.api.rules.ProgressionRules.experienceToNextLevel(0, 1))
                .toCompletableFuture().join();
        profiles.allocateAttribute(warrior, CoreAttribute.DEFENSE).toCompletableFuture().join();
        var earnedPointPreserved = profiles.prepareStarterBuild(warrior).toCompletableFuture().join();
        assertEquals(3, earnedPointPreserved.attribute(CoreAttribute.STRENGTH));
        assertEquals(2, earnedPointPreserved.attribute(CoreAttribute.STAMINA));
        assertEquals(0, earnedPointPreserved.attribute(CoreAttribute.DEFENSE));
        assertEquals(1, earnedPointPreserved.unspentAttributePoints());
    }

    @Test
    void periodEnergyQuotaDiminishesPersistsAndDoesNotSpendWhileFull() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService first = new JdbcProfileService(dataSource, executor, 100, 2);
        first.get(playerId).toCompletableFuture().join();

        var whileFull = first.restoreEnergyWithQuota(
                playerId, "bloodpact-raw-meat", "2026-08-12", 8, 2, 2, 2, 40)
                .toCompletableFuture().join();
        assertEquals(cn.originalrpg.api.model.EnergyQuotaResult.Status.ENERGY_FULL, whileFull.status());
        assertEquals(0, whileFull.uses());

        assertTrue(first.spendEnergy(playerId, 100).toCompletableFuture().join());
        List<Integer> grants = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            grants.add(first.restoreEnergyWithQuota(
                    playerId, "bloodpact-raw-meat", "2026-08-12", 8, 2, 2, 2, 40)
                    .toCompletableFuture().join().energyGranted());
        }
        assertEquals(List.of(8, 8, 6, 6, 4, 4, 2, 2), grants);
        assertEquals(40, first.get(playerId).toCompletableFuture().join().energy());

        first.evict(playerId);
        JdbcProfileService afterRestart = new JdbcProfileService(dataSource, executor, 100, 2);
        var limited = afterRestart.restoreEnergyWithQuota(
                playerId, "bloodpact-raw-meat", "2026-08-12", 8, 2, 2, 2, 40)
                .toCompletableFuture().join();
        assertEquals(cn.originalrpg.api.model.EnergyQuotaResult.Status.PERIOD_LIMIT, limited.status());
        assertEquals(40, limited.grantedThisPeriod());

        var tomorrow = afterRestart.restoreEnergyWithQuota(
                playerId, "bloodpact-raw-meat", "2026-08-13", 8, 2, 2, 2, 40)
                .toCompletableFuture().join();
        assertEquals(cn.originalrpg.api.model.EnergyQuotaResult.Status.SUCCESS, tomorrow.status());
        assertEquals(8, tomorrow.energyGranted());
    }

    @Test
    void concurrentPeriodEnergyQuotaCannotExceedItsLimit() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        profiles.get(playerId).toCompletableFuture().join();
        assertTrue(profiles.spendEnergy(playerId, 100).toCompletableFuture().join());

        List<CompletableFuture<cn.originalrpg.api.model.EnergyQuotaResult>> attempts = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            attempts.add(profiles.restoreEnergyWithQuota(
                    playerId, "bloodpact-raw-meat", "2026-08-12", 8, 2, 2, 2, 40)
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
        assertEquals(40, attempts.stream().map(CompletableFuture::join)
                .mapToInt(cn.originalrpg.api.model.EnergyQuotaResult::energyGranted).sum());
        assertEquals(40, profiles.get(playerId).toCompletableFuture().join().energy());
    }

    @Test
    void hiddenPathRequiresTheWholeRouteAndSerializesTheFinalSeat() throws Exception {
        JdbcHiddenPathService paths = new JdbcHiddenPathService(dataSource, executor);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        seedBloodpactFacts(first, true);
        seedBloodpactFacts(second, true);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_hidden_path_caps (path, capacity) VALUES ('BLOODPACT', 1)");
            connection.commit();
        }

        var missing = paths.eligibility(UUID.randomUUID(), HiddenPath.BLOODPACT).toCompletableFuture().join();
        assertFalse(missing.eligible());
        assertTrue(missing.missingRequirements().contains("profile"));

        CompletableFuture<HiddenPathActivationResult> firstAttempt = paths.activate(first, HiddenPath.BLOODPACT)
                .toCompletableFuture();
        CompletableFuture<HiddenPathActivationResult> secondAttempt = paths.activate(second, HiddenPath.BLOODPACT)
                .toCompletableFuture();
        CompletableFuture.allOf(firstAttempt, secondAttempt).join();
        long activated = List.of(firstAttempt.join(), secondAttempt.join()).stream()
                .filter(result -> result.status() == HiddenPathActivationResult.Status.ACTIVATED).count();
        long full = List.of(firstAttempt.join(), secondAttempt.join()).stream()
                .filter(result -> result.status() == HiddenPathActivationResult.Status.CAPACITY_FULL).count();
        assertEquals(1L, activated);
        assertEquals(1L, full);
        assertTrue(paths.path(first).toCompletableFuture().join().isPresent()
                || paths.path(second).toCompletableFuture().join().isPresent());
    }

    @Test
    void hiddenPathRequiresTheMatchingRedeemedHiddenBossBook() throws Exception {
        JdbcHiddenPathService paths = new JdbcHiddenPathService(dataSource, executor);
        UUID player = UUID.randomUUID();
        seedBloodpactFacts(player, false);

        var missing = paths.eligibility(player, HiddenPath.BLOODPACT).toCompletableFuture().join();
        assertFalse(missing.eligible());
        assertTrue(missing.missingRequirements().contains("skill_book"));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO rpg_skill_book_uses (
                        instance_id, player_uuid, book_id, limit_group, points_granted
                    ) VALUES ('%s', '%s', 'silent-thunder', NULL, 3)
                    """.formatted(UUID.randomUUID(), player));
            connection.commit();
        }
        var ready = paths.eligibility(player, HiddenPath.BLOODPACT).toCompletableFuture().join();
        assertTrue(ready.eligible());
    }

    @Test
    void concurrentEnergySpendsCannotOverspendOneProfile() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();

        List<CompletableFuture<Boolean>> spends = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            spends.add(profiles.spendEnergy(playerId, 10).toCompletableFuture());
        }
        CompletableFuture.allOf(spends.toArray(CompletableFuture[]::new)).join();

        assertEquals(10, spends.stream().filter(CompletableFuture::join).count());
        assertEquals(0, profiles.get(playerId).toCompletableFuture().join().energy());
    }

    @Test
    void dungeonEnergyReservationIsIdempotentAndCommittedEntriesAreNotRecovered() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease lease = acquiredLease("test-dungeon-commit");
        JdbcDungeonEntryService entries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, lease);
        UUID playerId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(reservationId, playerId, 30).toCompletableFuture().join());
        assertEquals(DungeonEntryService.ReservationStatus.DUPLICATE,
                entries.reserve(reservationId, playerId, 30).toCompletableFuture().join());
        assertEquals(70, profiles.get(playerId).toCompletableFuture().join().energy());
        assertTrue(entries.commit(reservationId, playerId).toCompletableFuture().join());
        assertTrue(entries.commit(reservationId, playerId).toCompletableFuture().join());
        assertEquals(0, entries.recoverAll().toCompletableFuture().join().reservations());
        assertEquals(70, profiles.get(playerId).toCompletableFuture().join().energy());
    }

    @Test
    void dungeonPartyCommitIsAllOrNothing() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease lease = acquiredLease("test-dungeon-party-commit");
        JdbcDungeonEntryService entries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, lease);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID firstReservation = UUID.randomUUID();
        UUID secondReservation = UUID.randomUUID();

        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(firstReservation, firstPlayer, 20).toCompletableFuture().join());
        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(secondReservation, secondPlayer, 30).toCompletableFuture().join());
        assertFalse(entries.commitAll(Map.of(firstPlayer, firstReservation,
                secondPlayer, UUID.randomUUID())).toCompletableFuture().join());
        assertEquals(DungeonEntryService.PartyCommitStatus.RESERVED,
                entries.partyCommitStatus(Map.of(firstPlayer, firstReservation,
                        secondPlayer, secondReservation)).toCompletableFuture().join());
        assertTrue(entries.commitAll(Map.of(firstPlayer, firstReservation,
                secondPlayer, secondReservation)).toCompletableFuture().join());
        assertTrue(entries.commitAll(Map.of(firstPlayer, firstReservation,
                secondPlayer, secondReservation)).toCompletableFuture().join());
        assertEquals(DungeonEntryService.PartyCommitStatus.COMMITTED,
                entries.partyCommitStatus(Map.of(firstPlayer, firstReservation,
                        secondPlayer, secondReservation)).toCompletableFuture().join());
        assertEquals(DungeonEntryService.PartyCommitStatus.INVALID,
                entries.partyCommitStatus(Map.of(firstPlayer, firstReservation,
                        secondPlayer, UUID.randomUUID())).toCompletableFuture().join());
        assertEquals(0, entries.recoverAll().toCompletableFuture().join().reservations());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT COUNT(*) FROM rpg_dungeon_energy_reservations WHERE state = 'COMMITTED'
                     """)) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
            connection.commit();
        }
    }

    @Test
    void dungeonTicketEscrowCommitsOrReturnsThroughOneIdempotentMailboxReceipt() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease lease = acquiredLease("test-dungeon-ticket-escrow");
        JdbcDungeonEntryService entries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, lease);
        UUID committedPlayer = UUID.randomUUID();
        UUID committedReservation = UUID.randomUUID();
        UUID refundedPlayer = UUID.randomUUID();
        UUID refundedReservation = UUID.randomUUID();

        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(committedReservation, committedPlayer, 20).toCompletableFuture().join());
        assertTrue(entries.escrowTicket(committedReservation, committedPlayer,
                "ancient-ruins-hidden", "committed-ticket").toCompletableFuture().join());
        assertTrue(entries.escrowTicket(committedReservation, committedPlayer,
                "ancient-ruins-hidden", "committed-ticket").toCompletableFuture().join());
        assertFalse(entries.escrowTicket(committedReservation, committedPlayer,
                "ancient-ruins-hidden", "different-ticket").toCompletableFuture().join());
        assertTrue(entries.commitAll(Map.of(committedPlayer, committedReservation))
                .toCompletableFuture().join());

        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(refundedReservation, refundedPlayer, 30).toCompletableFuture().join());
        assertTrue(entries.escrowTicket(refundedReservation, refundedPlayer,
                "storm-vault-hidden", "refunded-ticket").toCompletableFuture().join());
        assertEquals(30, entries.refund(refundedReservation, refundedPlayer).toCompletableFuture().join());
        assertEquals(0, entries.refund(refundedReservation, refundedPlayer).toCompletableFuture().join());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT state FROM rpg_dungeon_ticket_escrows
                    WHERE reservation_id = '%s'
                    """.formatted(committedReservation))) {
                assertTrue(result.next());
                assertEquals("CONSUMED", result.getString(1));
            }
            try (var result = statement.executeQuery("""
                    SELECT t.state, d.player_uuid, d.item_payload, d.state
                    FROM rpg_dungeon_ticket_escrows t
                    JOIN rpg_market_deliveries d ON d.id = t.delivery_id
                    WHERE t.reservation_id = '%s'
                    """.formatted(refundedReservation))) {
                assertTrue(result.next());
                assertEquals("RETURNED", result.getString(1));
                assertEquals(refundedPlayer.toString(), result.getString(2));
                assertEquals("refunded-ticket", result.getString(3));
                assertEquals("PENDING", result.getString(4));
            }
            connection.commit();
        }
    }

    @Test
    void orphanRecoveryReturnsHeldDungeonTicketWithEnergy() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease originalLease = acquiredLease("test-dungeon-ticket-restart");
        JdbcDungeonEntryService original = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, originalLease);
        UUID playerId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                original.reserve(reservationId, playerId, 35).toCompletableFuture().join());
        assertTrue(original.escrowTicket(reservationId, playerId,
                "sulfur-depths-hidden", "held-ticket").toCompletableFuture().join());

        originalLease.release();
        JdbcNodeLease replacementLease = acquiredLease("test-dungeon-ticket-restart");
        JdbcDungeonEntryService replacement = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, replacementLease);
        var recovery = replacement.recoverAll().toCompletableFuture().join();
        assertEquals(1, recovery.reservations());
        assertEquals(35, recovery.energy());
        assertEquals(0, replacement.recoverAll().toCompletableFuture().join().reservations());
        var durableEscrow = replacement.ticketEscrow(reservationId, playerId)
                .toCompletableFuture().join().orElseThrow();
        assertEquals("sulfur-depths-hidden", durableEscrow.ticketId());
        assertEquals("held-ticket", durableEscrow.itemPayload());
        assertTrue(replacement.ticketEscrow(reservationId, UUID.randomUUID())
                .toCompletableFuture().join().isEmpty());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT t.state, d.item_payload, d.state
                     FROM rpg_dungeon_ticket_escrows t
                     JOIN rpg_market_deliveries d ON d.id = t.delivery_id
                     WHERE t.reservation_id = '%s'
                     """.formatted(reservationId))) {
            assertTrue(result.next());
            assertEquals("RETURNED", result.getString(1));
            assertEquals("held-ticket", result.getString(2));
            assertEquals("PENDING", result.getString(3));
            connection.commit();
        }
    }

    @Test
    void replayedDungeonCommitSelfHealsAStillHeldTicketEscrow() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease lease = acquiredLease("test-dungeon-ticket-commit-replay");
        JdbcDungeonEntryService entries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, lease);
        UUID playerId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(reservationId, playerId, 20).toCompletableFuture().join());
        assertTrue(entries.escrowTicket(reservationId, playerId,
                "ancient-ruins-hidden", "self-heal-ticket").toCompletableFuture().join());
        assertTrue(entries.commit(reservationId, playerId).toCompletableFuture().join());

        // Simulate a legacy row or an externally interrupted historical
        // deployment in which the reservation is authoritative COMMITTED but
        // its ticket settlement was left HELD.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("""
                    UPDATE rpg_dungeon_ticket_escrows SET state = 'HELD', settled_at = NULL
                    WHERE reservation_id = '%s'
                    """.formatted(reservationId)));
            connection.commit();
        }

        assertTrue(entries.commit(reservationId, playerId).toCompletableFuture().join());
        assertEquals("CONSUMED", dungeonTicketState(reservationId));
    }

    @Test
    void concurrentDungeonCommitAndRefundSettleEnergyAndTicketExactlyOnce() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease lease = acquiredLease("test-dungeon-commit-refund-race");
        JdbcDungeonEntryService entries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, lease);

        for (int index = 0; index < 20; index++) {
            UUID playerId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                    entries.reserve(reservationId, playerId, 30).toCompletableFuture().join());
            assertTrue(entries.escrowTicket(reservationId, playerId,
                    "storm-vault-hidden", "race-ticket-" + index).toCompletableFuture().join());

            CompletableFuture<Boolean> commit = entries.commit(reservationId, playerId).toCompletableFuture();
            CompletableFuture<Integer> refund = entries.refund(reservationId, playerId).toCompletableFuture();
            CompletableFuture.allOf(commit, refund).join();

            String reservationState = dungeonReservationState(reservationId);
            String ticketState = dungeonTicketState(reservationId);
            if (commit.join()) {
                assertEquals(0, refund.join());
                assertEquals("COMMITTED", reservationState);
                assertEquals("CONSUMED", ticketState);
                assertEquals(70, profiles.get(playerId).toCompletableFuture().join().energy());
                assertEquals(0, dungeonTicketDeliveryCount(reservationId));
            } else {
                assertEquals(30, refund.join());
                assertEquals("REFUNDED", reservationState);
                assertEquals("RETURNED", ticketState);
                assertEquals(100, profiles.get(playerId).toCompletableFuture().join().energy());
                assertEquals(1, dungeonTicketDeliveryCount(reservationId));
            }
        }
    }

    @Test
    void dungeonReservationRefundAndStartupRecoveryCannotRestoreTwice() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease firstProcess = acquiredLease("test-dungeon-restart");
        JdbcDungeonEntryService entries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, firstProcess);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID firstReservation = UUID.randomUUID();

        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(firstReservation, first, 35).toCompletableFuture().join());
        assertEquals(35, entries.refund(firstReservation, first).toCompletableFuture().join());
        assertEquals(0, entries.refund(firstReservation, first).toCompletableFuture().join());
        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(UUID.randomUUID(), first, 20).toCompletableFuture().join());
        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                entries.reserve(UUID.randomUUID(), second, 25).toCompletableFuture().join());

        firstProcess.release();
        JdbcNodeLease replacement = acquiredLease("test-dungeon-restart");
        JdbcDungeonEntryService restartedEntries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, replacement);
        var recovery = restartedEntries.recoverAll().toCompletableFuture().join();
        assertEquals(2, recovery.reservations());
        assertEquals(45, recovery.energy());
        assertEquals(0, restartedEntries.recoverAll().toCompletableFuture().join().reservations());
        assertEquals(100, profiles.get(first).toCompletableFuture().join().energy());
        assertEquals(100, profiles.get(second).toCompletableFuture().join().energy());
    }

    @Test
    void concurrentDungeonReservationReplayOnlySpendsEnergyOnce() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease lease = acquiredLease("test-dungeon-concurrency");
        JdbcDungeonEntryService entries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, lease);
        UUID playerId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<CompletableFuture<DungeonEntryService.ReservationStatus>> attempts = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            attempts.add(entries.reserve(reservationId, playerId, 10).toCompletableFuture());
        }
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, attempts.stream().map(CompletableFuture::join)
                .filter(DungeonEntryService.ReservationStatus.SUCCESS::equals).count());
        assertEquals(19, attempts.stream().map(CompletableFuture::join)
                .filter(DungeonEntryService.ReservationStatus.DUPLICATE::equals).count());
        assertEquals(90, profiles.get(playerId).toCompletableFuture().join().energy());
    }

    @Test
    void liveReservationOnAnotherPaperNodeIsNeverRecovered() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcNodeLease realmNode = acquiredLease("realm-a-01");
        JdbcNodeLease dungeonNode = acquiredLease("dungeon-01");
        JdbcDungeonEntryService realmEntries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, realmNode);
        JdbcDungeonEntryService recoveryWorker = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, dungeonNode);
        UUID playerId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                realmEntries.reserve(reservationId, playerId, 30).toCompletableFuture().join());
        assertEquals(0, recoveryWorker.recoverAll().toCompletableFuture().join().reservations());
        assertEquals(70, profiles.get(playerId).toCompletableFuture().join().energy());

        realmNode.release();
        var recovery = recoveryWorker.recoverAll().toCompletableFuture().join();
        assertEquals(1, recovery.reservations());
        assertEquals(30, recovery.energy());
        assertEquals(100, profiles.get(playerId).toCompletableFuture().join().energy());
        assertThrows(CompletionException.class,
                () -> realmEntries.commit(reservationId, playerId).toCompletableFuture().join());
    }

    @Test
    void expiredNodeTokenCannotReviveOrSettleAfterReplacement() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        JdbcNodeLease first = new JdbcNodeLease(dataSource, executor, "realm-a-02",
                UUID.randomUUID(), Duration.ofSeconds(10), clock);
        JdbcNodeLease replacement = new JdbcNodeLease(dataSource, executor, "realm-a-02",
                UUID.randomUUID(), Duration.ofSeconds(10), clock);
        assertTrue(first.acquire());
        assertFalse(replacement.acquire());

        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcDungeonEntryService firstEntries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, first);
        UUID playerId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        assertEquals(DungeonEntryService.ReservationStatus.SUCCESS,
                firstEntries.reserve(reservationId, playerId, 25).toCompletableFuture().join());

        clock.advance(Duration.ofSeconds(11));
        assertFalse(first.renewAsync().toCompletableFuture().join());
        assertTrue(replacement.acquire());
        JdbcDungeonEntryService replacementEntries = new JdbcDungeonEntryService(
                dataSource, executor, profiles, 100, 2, replacement);
        assertEquals(1, replacementEntries.recoverAll().toCompletableFuture().join().reservations());
        assertThrows(CompletionException.class,
                () -> firstEntries.commit(reservationId, playerId).toCompletableFuture().join());
        assertEquals(100, profiles.get(playerId).toCompletableFuture().join().energy());
    }

    @Test
    void localH2OwnerCanRenewAfterLongJvmPauseBecauseFileLockIsTheExternalFence() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        JdbcNodeLease localOwner = new JdbcNodeLease(dataSource, executor, "standalone-h2",
                UUID.randomUUID(), Duration.ofSeconds(10), clock, true);
        assertTrue(localOwner.acquire(true));

        clock.advance(Duration.ofSeconds(11));

        assertTrue(localOwner.renew());
        assertTrue(localOwner.mayStillBeValid());
        assertTrue(localOwner.release());
    }

    @Test
    void retryBudgetFailsClosedBeforePostgresTokenActuallyExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        JdbcNodeLease lease = new JdbcNodeLease(dataSource, executor, "realm-retry-budget",
                UUID.randomUUID(), Duration.ofSeconds(60), clock);
        assertTrue(lease.acquire());

        clock.advance(Duration.ofSeconds(36));

        assertTrue(lease.mayStillBeValid());
        assertFalse(lease.mayStillBeValidFor(Duration.ofSeconds(25)));
        assertTrue(lease.release());
    }

    @Test
    void cleanReleaseAllowsImmediateRestartAndLocalFileLockMayFenceAStaleToken() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        JdbcNodeLease first = new JdbcNodeLease(dataSource, executor, "standalone-restart",
                UUID.randomUUID(), Duration.ofSeconds(60), clock);
        JdbcNodeLease replacement = new JdbcNodeLease(dataSource, executor, "standalone-restart",
                UUID.randomUUID(), Duration.ofSeconds(60), clock);

        assertTrue(first.acquire());
        assertFalse(replacement.acquire());
        assertTrue(first.release());
        assertTrue(replacement.acquire());
        assertTrue(replacement.release());

        JdbcNodeLease stale = new JdbcNodeLease(dataSource, executor, "standalone-restart",
                UUID.randomUUID(), Duration.ofSeconds(60), clock);
        JdbcNodeLease externallyFenced = new JdbcNodeLease(dataSource, executor, "standalone-restart",
                UUID.randomUUID(), Duration.ofSeconds(60), clock);
        assertTrue(stale.acquire());
        assertTrue(externallyFenced.acquire(true));
        assertFalse(stale.mayStillBeValid() && stale.renewAsync().toCompletableFuture().join());
        assertTrue(externallyFenced.release());
    }

    @Test
    void concurrentExperienceGrantsPreserveEveryMutation() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();

        List<CompletableFuture<cn.originalrpg.api.model.PlayerProfile>> grants = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            grants.add(profiles.grantExperience(playerId, 1).toCompletableFuture());
        }
        CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)).join();

        assertEquals(20, profiles.get(playerId).toCompletableFuture().join().experience());
    }

    @Test
    void concurrentSkillBookReplayGrantsPointsExactlyOnce() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        UUID bookInstance = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();

        List<CompletableFuture<SkillBookRedemption>> redemptions = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            redemptions.add(profiles.redeemSkillBook(
                    playerId, bookInstance, "common-insight", 1, "common-skill-points", 5).toCompletableFuture());
        }
        CompletableFuture.allOf(redemptions.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, redemptions.stream().filter(result ->
                result.join().status() == SkillBookRedemption.Status.SUCCESS).count());
        assertEquals(19, redemptions.stream().filter(result ->
                result.join().status() == SkillBookRedemption.Status.DUPLICATE).count());
        assertEquals(2, profiles.cached(playerId).orElseThrow().skillPoints());
    }

    @Test
    void commonSkillBooksRespectAccountLimitWhileAncientBooksDoNot() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();

        for (int index = 0; index < 5; index++) {
            SkillBookRedemption result = profiles.redeemSkillBook(
                    playerId, UUID.randomUUID(), "common-insight", 1, "common-skill-points", 5)
                    .toCompletableFuture().join();
            assertEquals(SkillBookRedemption.Status.SUCCESS, result.status());
        }
        SkillBookRedemption limited = profiles.redeemSkillBook(
                playerId, UUID.randomUUID(), "common-insight", 1, "common-skill-points", 5)
                .toCompletableFuture().join();
        assertEquals(SkillBookRedemption.Status.LIMIT_REACHED, limited.status());

        for (int index = 0; index < 8; index++) {
            SkillBookRedemption result = profiles.redeemSkillBook(
                    playerId, UUID.randomUUID(), "ancient-echo", 3, null, 0).toCompletableFuture().join();
            assertEquals(SkillBookRedemption.Status.SUCCESS, result.status());
        }
        assertEquals(30, profiles.get(playerId).toCompletableFuture().join().skillPoints());
    }

    @Test
    void skillPointLimitDoesNotConsumeTheBookInstanceAndCanBeRetried() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_profiles SET skill_points = 1000000000 WHERE player_uuid = '"
                    + playerId + "'");
            connection.commit();
        }
        profiles.evict(playerId);

        SkillBookRedemption rejected = profiles.redeemSkillBook(
                playerId, instanceId, "ancient-echo", 3, null, 0).toCompletableFuture().join();
        assertEquals(SkillBookRedemption.Status.POINT_LIMIT, rejected.status());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_skill_book_uses WHERE instance_id = '"
                + instanceId + "'"));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_profiles SET skill_points = 999999997 WHERE player_uuid = '"
                    + playerId + "'");
            connection.commit();
        }
        profiles.evict(playerId);
        SkillBookRedemption retried = profiles.redeemSkillBook(
                playerId, instanceId, "ancient-echo", 3, null, 0).toCompletableFuture().join();
        assertEquals(SkillBookRedemption.Status.SUCCESS, retried.status());
        assertEquals(1_000_000_000, retried.profile().skillPoints());
    }

    // --- G-107 下半：技能点本地里程碑（幂等 + 跨段不吞点）行为断言 ---

    @Test
    void skillPointMilestonesFollowLocalLevelsInsideAStage() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();

        // 新档案自带 1 点技能点（rpg_profiles 建表默认值，不属于本次改动）
        assertEquals(1, profiles.get(playerId).toCompletableFuture().join().skillPoints());

        // 0 转本地 1 -> 5：过 1 档（本地 3），1 + 1 = 2 点
        cn.originalrpg.api.model.PlayerProfile atFive = profiles
                .grantExperience(playerId, experienceToLocal(0, 5)).toCompletableFuture().join();
        assertEquals(5, atFive.level());
        assertEquals(2, atFive.skillPoints());

        // 5 -> 13：再过 3 档（6/9/12），累计 1 + 4 = 5 点
        cn.originalrpg.api.model.PlayerProfile atThirteen = profiles
                .grantExperience(playerId, experienceToLocal(0, 5, 13)).toCompletableFuture().join();
        assertEquals(13, atThirteen.level());
        assertEquals(5, atThirteen.skillPoints());

        // 13 -> 本地满级 30：累计 1 + 10 = 11 点（每转十个里程碑）
        cn.originalrpg.api.model.PlayerProfile atCap = profiles
                .grantExperience(playerId, experienceToLocal(0, 13, 30) + 10_000_000L)
                .toCompletableFuture().join();
        assertEquals(30, atCap.level());
        assertEquals(11, atCap.skillPoints());
        // 本地 3/6/.../30 共十个里程碑，各写一行流水
        assertEquals(10, scalar("SELECT COUNT(*) FROM rpg_skill_point_grants"));
        assertEquals(10, milestoneRows("local-milestone:0:%"));
    }

    @Test
    void ascensionDoesNotSwallowTheNextStagesLocalMilestones() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.WARRIOR).toCompletableFuture().join();

        profiles.grantExperience(playerId, Long.MAX_VALUE).toCompletableFuture().join();
        assertEquals(11, profiles.get(playerId).toCompletableFuture().join().skillPoints());

        ascendThroughService(profiles, ascensions, playerId, 1);
        // 转生只改段，不改本地等级也不改技能点：0转30 -> 1转1 不吞点
        assertEquals(1, profiles.get(playerId).toCompletableFuture().join().level());
        assertEquals(11, profiles.get(playerId).toCompletableFuture().join().skillPoints());

        // 1 转本地 1 -> 3：旧口径（31/3=10 -> 33/3=11）恰好也是 1 点，但跨段吞点发生在
        // 1转1->2（31/3=10 -> 32/3=10 吞掉）与 0转30->1转2，这里钉住新口径必然发点
        cn.originalrpg.api.model.PlayerProfile two = profiles
                .grantExperience(playerId, experienceToLocal(1, 1, 2) + 1).toCompletableFuture().join();
        assertEquals(2, two.level());
        assertEquals(11, two.skillPoints(), "本地 1->2 不过档位，不发点");
        cn.originalrpg.api.model.PlayerProfile three = profiles
                .grantExperience(playerId, experienceToLocal(1, 2, 3) + 1).toCompletableFuture().join();
        assertEquals(3, three.level());
        assertEquals(12, three.skillPoints(), "本地 3 是新段的首个里程碑，跨段后不吞点");

        // 走完 1 转：累计 20 点，并且流水里两段各有自己的档位 id
        profiles.grantExperience(playerId, Long.MAX_VALUE).toCompletableFuture().join();
        assertEquals(21, profiles.get(playerId).toCompletableFuture().join().skillPoints());
        assertEquals(10, milestoneRows("local-milestone:0:%"), "0 转十个里程碑");
        assertEquals(10, milestoneRows("local-milestone:1:%"), "1 转十个里程碑（跨段后重新累计）");
    }

    @Test
    void skillPointMilestonesAreIdempotentForOneReceiptAndIndependentAcrossReceipts() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        UUID firstRun = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();

        long grant = experienceToLocal(0, 1, 5);
        cn.originalrpg.api.model.PlayerProfile first = inTransaction(connection ->
                profiles.grantExperienceInTransaction(connection, playerId, grant, firstRun, firstRun.toString()));
        assertEquals(2, first.skillPoints());

        // 同一 receipt 重放：技能点不再增加，且不写第二行流水
        cn.originalrpg.api.model.PlayerProfile replayed = inTransaction(connection ->
                profiles.grantExperienceInTransaction(connection, playerId, grant, firstRun, firstRun.toString()));
        // 经验/等级的幂等由调用方的业务 receipt 负责（副本用 rpg_dungeon_reward_receipts，
        // 同一 run 直接返回 REPLAYED，不会二次进入本方法）。
        // 本层保证的是：即使被重复调用，技能点里程碑也绝不重复发。
        assertEquals(1, replayed.skillPoints() - first.skillPoints(), "重放只允许再产生 0 点技能点");
        assertEquals(1, milestoneRows("local-milestone:0:1|source:" + firstRun + "%"),
                "同一 receipt 只允许一行里程碑流水");

        // 同一 inc/milestone 被另一个 receipt 复用：不同 source ⇒ 允许（那是另一条结算事实）
        UUID secondRun = UUID.randomUUID();
        cn.originalrpg.api.model.PlayerProfile other = inTransaction(connection ->
                profiles.grantExperienceInTransaction(connection, playerId, 0 + grant, secondRun, secondRun.toString()));
        assertEquals(3, other.skillPoints());
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_skill_point_grants"));
    }

    @Test
    void skillPointMilestoneReplayKeepsTheFirstReceiptRowUnchanged() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        UUID playerId = UUID.randomUUID();
        profiles.get(playerId).toCompletableFuture().join();

        // 同一个业务的两次结算用同一个 receipt：第一次到本地 5（过 3 档），
        // 第二次到本地 6（再过一个档位）。唯一键把首次写入的 3 档行钉住，
        // 已在库里的档位不重复发点，但新跨过的 6 档必须照发。
        UUID runId = UUID.randomUUID();
        cn.originalrpg.api.model.PlayerProfile first = inTransaction(connection ->
                profiles.grantExperienceInTransaction(
                        connection, playerId, experienceToLocal(0, 1, 5), runId, runId.toString()));
        assertEquals(2, first.skillPoints());

        // 先让档案回退，模拟「同一 receipt 被重放且档案状态与首次进入时一致」
        resetProfileTo(playerId, 0, 1);
        profiles.evict(playerId);
        cn.originalrpg.api.model.PlayerProfile replayed = inTransaction(connection ->
                profiles.grantExperienceInTransaction(
                        connection, playerId, experienceToLocal(0, 1, 5), runId, runId.toString()));
        assertEquals(1, replayed.skillPoints() - 1, "重放同一 receipt 不得重复发点（档案基线 1 点）");
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_skill_point_grants"),
                "本地 3 只有首次一行，重放不写第二行");
        assertEquals(3, scalar("SELECT to_local_level FROM rpg_skill_point_grants"),
                "唯一键把首次写入的档位行钉住，重放不得改写");
    }

    /** 把档案重置到指定的 (转, 本地等级)，用于模拟重放时档案状态与首次进入时一致。 */
    private void resetProfileTo(UUID playerId, int ascension, int localLevel) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_profiles SET ascension = " + ascension + ", level = " + localLevel
                    + ", experience = 0 WHERE player_uuid = '" + playerId + "'");
            connection.commit();
        }
    }

    @Test
    void classBranchesRequireMatchingClassAndOpenedAscensionStage() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcClassBranchService branches = new JdbcClassBranchService(dataSource, executor);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.MAGE).toCompletableFuture().join();

        ClassBranchResult locked = branches.choose(playerId, Archetype.MAGE, 1, "pyromancer")
                .toCompletableFuture().join();
        ClassBranchResult mismatch = branches.choose(playerId, Archetype.WARRIOR, 1, "berserker")
                .toCompletableFuture().join();
        assertEquals(ClassBranchResult.Status.STAGE_LOCKED, locked.status());
        assertEquals(ClassBranchResult.Status.CLASS_MISMATCH, mismatch.status());

        ascendThroughService(profiles, ascensions, playerId, 1);
        ClassBranchResult selected = branches.choose(playerId, Archetype.MAGE, 1, "pyromancer")
                .toCompletableFuture().join();
        ClassBranchResult duplicate = branches.choose(playerId, Archetype.MAGE, 1, "arcanist")
                .toCompletableFuture().join();
        assertEquals(ClassBranchResult.Status.SUCCESS, selected.status());
        assertEquals(ClassBranchResult.Status.ALREADY_CHOSEN, duplicate.status());
        assertEquals("pyromancer", branches.branches(playerId).toCompletableFuture().join().get(1));
    }

    @Test
    void higherClassBranchesEnforceRouteInsideTheSelectionTransaction() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcClassBranchService branches = new JdbcClassBranchService(dataSource, executor);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.MAGE).toCompletableFuture().join();
        ascendThroughService(profiles, ascensions, playerId, 1);

        assertEquals(ClassBranchResult.Status.SUCCESS, branches.choose(
                playerId, Archetype.MAGE, 1, "pyromancer", java.util.Set.of())
                .toCompletableFuture().join().status());
        ascendThroughService(profiles, ascensions, playerId, 2);
        ClassBranchResult wrongRoute = branches.choose(
                playerId, Archetype.MAGE, 2, "chronomancer", java.util.Set.of("arcanist"))
                .toCompletableFuture().join();
        assertEquals(ClassBranchResult.Status.PREREQUISITE_MISMATCH, wrongRoute.status());
        assertFalse(branches.branches(playerId).toCompletableFuture().join().containsKey(2));

        assertEquals(ClassBranchResult.Status.SUCCESS, branches.choose(
                playerId, Archetype.MAGE, 2, "ash-shaper", java.util.Set.of("pyromancer"))
                .toCompletableFuture().join().status());
        ascendThroughService(profiles, ascensions, playerId, 3);
        assertEquals(ClassBranchResult.Status.SUCCESS, branches.choose(
                playerId, Archetype.MAGE, 3, "first-principle",
                java.util.Set.of("ash-shaper", "chronomancer"))
                .toCompletableFuture().join().status());
    }

    @Test
    void thirdAscensionMergeAcceptsEitherLegalSecondAscensionRoute() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcClassBranchService branches = new JdbcClassBranchService(dataSource, executor);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.MAGE).toCompletableFuture().join();
        ascendThroughService(profiles, ascensions, playerId, 1);

        branches.choose(playerId, Archetype.MAGE, 1, "arcanist").toCompletableFuture().join();
        ascendThroughService(profiles, ascensions, playerId, 2);
        branches.choose(playerId, Archetype.MAGE, 2, "chronomancer", java.util.Set.of("arcanist"))
                .toCompletableFuture().join();
        ascendThroughService(profiles, ascensions, playerId, 3);
        ClassBranchResult result = branches.choose(
                playerId, Archetype.MAGE, 3, "first-principle",
                java.util.Set.of("ash-shaper", "chronomancer"))
                .toCompletableFuture().join();

        assertEquals(ClassBranchResult.Status.SUCCESS, result.status());
    }

    @Test
    void concurrentBranchSelectionPersistsOnlyOneChoice() {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcClassBranchService branches = new JdbcClassBranchService(dataSource, executor);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.WARRIOR).toCompletableFuture().join();
        ascendThroughService(profiles, ascensions, playerId, 1);

        CompletableFuture<ClassBranchResult> guardian = branches.choose(
                playerId, Archetype.WARRIOR, 1, "guardian").toCompletableFuture();
        CompletableFuture<ClassBranchResult> berserker = branches.choose(
                playerId, Archetype.WARRIOR, 1, "berserker").toCompletableFuture();
        CompletableFuture.allOf(guardian, berserker).join();

        long successes = java.util.stream.Stream.of(guardian.join(), berserker.join())
                .filter(result -> result.status() == ClassBranchResult.Status.SUCCESS).count();
        assertEquals(1L, successes);
        assertEquals(1, branches.branches(playerId).toCompletableFuture().join().size());
    }

    /**
     * Drives one ascension through the only legal entry point.
     * Seeds the authored facts AscensionRules requires at each stage, then asserts SUCCESS.
     * Branch prerequisites are not seeded here: callers choose them through the real service.
     */
    private void ascendThroughService(
            JdbcProfileService profiles, JdbcAscensionService ascensions, UUID player, int stage
    ) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            int skills = switch (stage) {
                case 1 -> 2;
                case 2 -> 5;
                default -> 7;
            };
            int clears = switch (stage) {
                case 1 -> 1;
                case 2 -> 10;
                default -> 30;
            };
            long merit = switch (stage) {
                case 1 -> 20L;
                case 2 -> 100L;
                default -> 300L;
            };
            String[] dungeons = switch (stage) {
                case 1 -> new String[]{"ancient-ruins"};
                case 2 -> new String[]{"sulfur-depths", "storm-vault", "storm-vault-hidden"};
                default -> new String[]{"storm-vault", "storm-vault-hidden"};
            };
            for (int index = 0; index < skills; index++) {
                statement.execute("INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost) VALUES ('"
                        + player + "', 'ascend_skill_" + index + "', 1, 0) ON CONFLICT DO NOTHING");
            }
            for (int index = 0; index < clears; index++) {
                statement.execute("INSERT INTO rpg_dungeon_reward_receipts (run_id, player_uuid, dungeon_id, "
                        + "experience_awarded, queued_deliveries, ticket_dropped, ticket_failures) VALUES ('"
                        + UUID.randomUUID() + "', '" + player + "', '" + dungeons[index % dungeons.length]
                        + "', 1, 0, FALSE, 0)");
            }
            statement.execute("DELETE FROM rpg_progression_wallets WHERE player_uuid = '" + player + "'");
            statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES ('"
                    + player + "', " + merit + ")");
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not seed ascension facts for stage " + stage, exception);
        }
        profiles.grantExperience(player, Long.MAX_VALUE).toCompletableFuture().join();
        int equipmentScore = switch (stage) {
            case 1 -> 40;
            case 2 -> 160;
            default -> 300;
        };
        boolean journey = stage == 1;
        AscensionResult result = ascensions.ascend(player,
                        new AscensionEvidence(stage, journey, equipmentScore, Set.of(UUID.randomUUID())))
                .toCompletableFuture().join();
        assertEquals(AscensionResult.Status.SUCCESS, result.status(), "stage " + stage + " ascension");
        assertEquals(stage, result.profile().ascension());
    }

    @Test
    void transferChargesFeeOnceAndRejectsDuplicateReplay() {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, new BigDecimal("100.00"));
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        TransactionResult first = economy.transfer(sender, receiver, new BigDecimal("20.00"),
                new BigDecimal("0.10"), "market-order-1", "market-sale").toCompletableFuture().join();
        TransactionResult replay = economy.transfer(sender, receiver, new BigDecimal("20.00"),
                new BigDecimal("0.10"), "market-order-1", "market-sale").toCompletableFuture().join();

        assertEquals(TransactionResult.Status.SUCCESS, first.status());
        assertEquals(new BigDecimal("78.00"), first.senderBalance());
        assertEquals(new BigDecimal("2.00"), first.fee());
        assertEquals(new BigDecimal("120.00"), economy.balance(receiver).toCompletableFuture().join());
        assertEquals(TransactionResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("78.00"), economy.balance(sender).toCompletableFuture().join());
    }

    @Test
    void explicitCreditAndBurnAreAuditedAndIdempotent() {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();

        var credit = economy.credit(player, new BigDecimal("500.00"), "quest:credit-1", "quest")
                .toCompletableFuture().join();
        var duplicate = economy.credit(player, new BigDecimal("500.00"), "quest:credit-1", "quest")
                .toCompletableFuture().join();
        var burn = economy.burn(player, new BigDecimal("125.00"), "forge:burn-1", "forge")
                .toCompletableFuture().join();

        assertEquals(CurrencyOperationResult.Status.SUCCESS, credit.status());
        assertEquals(CurrencyOperationResult.Status.DUPLICATE, duplicate.status());
        assertEquals(CurrencyOperationResult.Status.SUCCESS, burn.status());
        assertEquals(new BigDecimal("375.00"), economy.balance(player).toCompletableFuture().join());
    }

    @Test
    void creditMayReachButNeverExceedTheGoldBalanceLimit() throws Exception {
        JdbcEconomyService economy = new JdbcEconomyService(
                dataSource, executor, new BigDecimal("999999999999.99"));
        UUID player = UUID.randomUUID();

        CurrencyOperationResult boundary = economy.credit(
                player, new BigDecimal("0.01"), "credit:balance-boundary", "test")
                .toCompletableFuture().join();
        CurrencyOperationResult rejected = economy.credit(
                player, new BigDecimal("0.01"), "credit:balance-overflow", "test")
                .toCompletableFuture().join();

        assertEquals(CurrencyOperationResult.Status.SUCCESS, boundary.status());
        assertEquals(new BigDecimal("1000000000000.00"), boundary.balance());
        assertEquals(CurrencyOperationResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(new BigDecimal("1000000000000.00"), rejected.balance());
        assertEquals(new BigDecimal("1000000000000.00"),
                economy.balance(player).toCompletableFuture().join());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key = 'credit:balance-overflow'"));
    }

    @Test
    void concurrentCreditsSerializeAtTheGoldBalanceLimit() throws Exception {
        JdbcEconomyService economy = new JdbcEconomyService(
                dataSource, executor, new BigDecimal("999999999999.99"));
        UUID player = UUID.randomUUID();
        List<CompletableFuture<CurrencyOperationResult>> credits = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            credits.add(economy.credit(player, new BigDecimal("0.01"),
                    "credit:balance-race-" + index, "test").toCompletableFuture());
        }
        CompletableFuture.allOf(credits.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, credits.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CurrencyOperationResult.Status.SUCCESS).count());
        assertEquals(19, credits.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CurrencyOperationResult.Status.BALANCE_LIMIT).count());
        assertEquals(new BigDecimal("1000000000000.00"),
                economy.balance(player).toCompletableFuture().join());
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key LIKE 'credit:balance-race-%'"));
    }

    @Test
    void transferChecksTheLockedReceiverBalanceBeforeDebitingOrWritingLedger() throws Exception {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        economy.credit(sender, new BigDecimal("100.00"), "seed:transfer-limit-sender", "test")
                .toCompletableFuture().join();
        economy.credit(receiver, new BigDecimal("999999999999.99"),
                        "seed:transfer-limit-receiver", "test")
                .toCompletableFuture().join();

        TransactionResult boundary = economy.transfer(sender, receiver, new BigDecimal("0.01"), BigDecimal.ZERO,
                "transfer:balance-boundary", "test").toCompletableFuture().join();
        TransactionResult rejected = economy.transfer(sender, receiver, new BigDecimal("0.01"), BigDecimal.ZERO,
                "transfer:balance-overflow", "test").toCompletableFuture().join();

        assertEquals(TransactionResult.Status.SUCCESS, boundary.status());
        assertEquals(TransactionResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(new BigDecimal("99.99"), rejected.senderBalance());
        assertEquals(new BigDecimal("99.99"), economy.balance(sender).toCompletableFuture().join());
        assertEquals(new BigDecimal("1000000000000.00"),
                economy.balance(receiver).toCompletableFuture().join());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key = 'transfer:balance-overflow'"));
    }

    @Test
    void concurrentTransfersCannotRaceOneReceiverPastTheGoldBalanceLimit() throws Exception {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID receiver = UUID.randomUUID();
        economy.credit(receiver, new BigDecimal("999999999999.99"),
                        "seed:transfer-race-receiver", "test")
                .toCompletableFuture().join();
        List<UUID> senders = new ArrayList<>();
        List<CompletableFuture<TransactionResult>> transfers = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            UUID sender = UUID.randomUUID();
            senders.add(sender);
            economy.credit(sender, BigDecimal.ONE, "seed:transfer-race-sender-" + index, "test")
                    .toCompletableFuture().join();
            transfers.add(economy.transfer(sender, receiver, new BigDecimal("0.01"), BigDecimal.ZERO,
                    "transfer:balance-race-" + index, "test").toCompletableFuture());
        }
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, transfers.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == TransactionResult.Status.SUCCESS).count());
        assertEquals(11, transfers.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == TransactionResult.Status.BALANCE_LIMIT).count());
        assertEquals(new BigDecimal("1000000000000.00"),
                economy.balance(receiver).toCompletableFuture().join());
        BigDecimal remaining = senders.stream()
                .map(sender -> economy.balance(sender).toCompletableFuture().join())
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        assertEquals(new BigDecimal("11.99"), remaining);
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key LIKE 'transfer:balance-race-%'"));
    }

    @Test
    void refundRequiresOneMatchingPlayerDebitAndDoesNotDistortEconomicFlow() {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertEquals(CurrencyOperationResult.Status.REFUND_REJECTED,
                economy.refund(player, new BigDecimal("125.00"), "forge:missing",
                                "refund:missing", "forge-refund")
                        .toCompletableFuture().join().status());
        economy.credit(player, new BigDecimal("500.00"), "seed:refund-test", "test-seed")
                .toCompletableFuture().join();
        economy.burn(player, new BigDecimal("125.00"), "forge:refund-test", "equipment-forge")
                .toCompletableFuture().join();

        assertEquals(CurrencyOperationResult.Status.REFUND_REJECTED,
                economy.refund(other, new BigDecimal("125.00"), "forge:refund-test",
                                "refund:wrong-owner", "forge-refund")
                        .toCompletableFuture().join().status());
        assertEquals(CurrencyOperationResult.Status.REFUND_REJECTED,
                economy.refund(player, new BigDecimal("124.99"), "forge:refund-test",
                                "refund:wrong-amount", "forge-refund")
                        .toCompletableFuture().join().status());

        CurrencyOperationResult refunded = economy.refund(player, new BigDecimal("125.00"),
                "forge:refund-test", "refund:refund-test", "equipment-forge-refund")
                .toCompletableFuture().join();
        assertEquals(CurrencyOperationResult.Status.SUCCESS, refunded.status());
        assertEquals(new BigDecimal("500.00"), refunded.balance());
        assertEquals(CurrencyOperationResult.Status.DUPLICATE,
                economy.refund(player, new BigDecimal("125.00"), "forge:refund-test",
                                "refund:refund-test", "equipment-forge-refund")
                        .toCompletableFuture().join().status());
        assertEquals(CurrencyOperationResult.Status.REFUND_REJECTED,
                economy.refund(player, new BigDecimal("125.00"), "forge:refund-test",
                                "refund:second-key", "equipment-forge-refund")
                        .toCompletableFuture().join().status());

        var report = new JdbcEconomyAnalyticsService(dataSource, executor)
                .dailyReport().toCompletableFuture().join();
        assertEquals(new BigDecimal("500.00"), report.totalGold());
        assertEquals(new BigDecimal("500.00"), report.mintedGold());
        assertEquals(new BigDecimal("0.00"), report.burnedGold());
        assertTrue(report.topSinks7d().stream()
                .noneMatch(reason -> reason.reason().equals("equipment-forge")));
    }

    @Test
    void economyReportSaturatesAppendOnlyCurrencyTotalsInsteadOfFailing() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES ('"
                    + first + "', 1000000000), ('" + second + "', 1000000000)");
            statement.execute("INSERT INTO rpg_commerce_accounts "
                    + "(player_uuid, lifetime_paid, credits) VALUES ('" + first
                    + "', 0, 1000000000), ('" + second + "', 0, 1000000000)");
            statement.execute("INSERT INTO rpg_merit_ledger "
                    + "(id, idempotency_key, player_uuid, amount, direction, reason) VALUES "
                    + "('" + UUID.randomUUID() + "', 'analytics-merit-one', '" + first
                    + "', 9223372036854775807, 'CREDIT', 'test'), "
                    + "('" + UUID.randomUUID() + "', 'analytics-merit-two', '" + second
                    + "', 9223372036854775807, 'CREDIT', 'test')");
            statement.execute("INSERT INTO rpg_credit_ledger "
                    + "(id, external_reference, player_uuid, delta, operator_name) VALUES "
                    + "('" + UUID.randomUUID() + "', 'analytics-starlight-one', '" + first
                    + "', 9223372036854775807, 'test'), "
                    + "('" + UUID.randomUUID() + "', 'analytics-starlight-two', '" + second
                    + "', 9223372036854775807, 'test')");
            connection.commit();
        }

        var report = new JdbcEconomyAnalyticsService(dataSource, executor)
                .dailyReport().toCompletableFuture().join();

        assertEquals(2_000_000_000L, report.totalMerit());
        assertEquals(2_000_000_000L, report.totalStarlight());
        assertEquals(Long.MAX_VALUE, report.mintedMerit());
        assertEquals(Long.MAX_VALUE, report.daily().mintedStarlight());
    }

    @Test
    void refundWalletLimitHasAnAccurateStatusAndKeepsTheReversalKeyRetryable() {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        economy.credit(player, new BigDecimal("100.00"), "seed:refund-cap-debit", "test")
                .toCompletableFuture().join();
        economy.burn(player, new BigDecimal("10.00"), "debit:refund-cap", "test")
                .toCompletableFuture().join();
        economy.credit(player, new BigDecimal("999999999910.00"), "seed:refund-cap-fill", "test")
                .toCompletableFuture().join();

        CurrencyOperationResult rejected = economy.refund(player, new BigDecimal("10.00"),
                "debit:refund-cap", "refund:cap-retry", "test-refund").toCompletableFuture().join();
        economy.burn(player, new BigDecimal("10.00"), "spend:refund-cap-room", "test")
                .toCompletableFuture().join();
        CurrencyOperationResult retried = economy.refund(player, new BigDecimal("10.00"),
                "debit:refund-cap", "refund:cap-retry", "test-refund").toCompletableFuture().join();

        assertEquals(CurrencyOperationResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(CurrencyOperationResult.Status.SUCCESS, retried.status());
        assertEquals(new BigDecimal("1000000000000.00"), retried.balance());
    }

    @Test
    void concurrentRefundKeysCanReverseOneDebitOnlyOnce() {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        economy.credit(player, new BigDecimal("100.00"), "seed:refund-race", "test-seed")
                .toCompletableFuture().join();
        economy.burn(player, new BigDecimal("50.00"), "refine:refund-race", "equipment-refinement")
                .toCompletableFuture().join();

        List<CompletableFuture<CurrencyOperationResult>> refunds = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            refunds.add(economy.refund(player, new BigDecimal("50.00"), "refine:refund-race",
                            "refund:race-" + index, "equipment-refinement-refund")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(refunds.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, refunds.stream().filter(result ->
                result.join().status() == CurrencyOperationResult.Status.SUCCESS).count());
        assertEquals(19, refunds.stream().filter(result ->
                result.join().status() == CurrencyOperationResult.Status.REFUND_REJECTED).count());
        assertEquals(new BigDecimal("100.00"), economy.balance(player).toCompletableFuture().join());
    }

    @Test
    void valuesThatRoundBelowOneCentCannotBecomeSuccessfulEconomicOperations() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        BigDecimal subCent = new BigDecimal("0.001");
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, new BigDecimal("100.00"));
        JdbcActivityEconomyService activity = new JdbcActivityEconomyService(
                dataSource, executor, new BigDecimal("100.00"));
        JdbcMerchantService merchant = new JdbcMerchantService(dataSource, executor, new BigDecimal("100.00"));
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, new BigDecimal("100.00"));
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, new BigDecimal("100.00"));

        assertEquals(CurrencyOperationResult.Status.INVALID_AMOUNT,
                gold.credit(player, subCent, "test:subcent-credit", "test")
                        .toCompletableFuture().join().status());
        assertEquals(CurrencyOperationResult.Status.INVALID_AMOUNT,
                gold.burn(player, subCent, "test:subcent-burn", "test")
                        .toCompletableFuture().join().status());
        assertEquals(TransactionResult.Status.INVALID_AMOUNT,
                gold.transfer(player, other, subCent, new BigDecimal("0.05"),
                                "test:subcent-transfer", "test")
                        .toCompletableFuture().join().status());
        assertEquals(ActivityRewardResult.Status.INVALID,
                activity.reward(player, EconomyActivity.GATHERING, subCent, 0L,
                                new BigDecimal("100.00"), 10L, "test:subcent-reward", "test")
                        .toCompletableFuture().join().status());
        assertEquals(EconomyPurchaseResult.Status.INVALID,
                activity.purchase(player, subCent, 0L, "payload",
                                "test:subcent-activity-purchase", "test")
                        .toCompletableFuture().join().status());
        assertEquals(ActivityRewardResult.Status.INVALID,
                activity.reward(player, EconomyActivity.GATHERING, subCent.negate(), 1L,
                                new BigDecimal("100.00"), 10L, "test:negative-subcent-reward", "test")
                        .toCompletableFuture().join().status());
        assertEquals(EconomyPurchaseResult.Status.INVALID,
                activity.purchase(player, subCent.negate(), 1L, "payload",
                                "test:negative-subcent-purchase", "test")
                        .toCompletableFuture().join().status());
        assertEquals(MerchantTransactionResult.Status.INVALID,
                merchant.purchase(player, "offer", "period", 1, 1, subCent,
                                "payload", "test-subcent-merchant")
                        .toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.INVALID,
                market.prepare(UUID.randomUUID(), player, "payload", subCent,
                                new BigDecimal("0.05"), 12)
                        .toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.INVALID,
                market.prepare(UUID.randomUUID(), player, "payload", new BigDecimal("10.00"),
                                new BigDecimal("0.049999"), 12)
                        .toCompletableFuture().join().status());
        assertEquals(CommerceResult.Status.INVALID,
                commerce.recordPayment(player, subCent, "test:subcent-payment", 31, "test")
                        .toCompletableFuture().join().status());
        assertEquals(SupplyPurchaseResult.Status.INVALID,
                commerce.purchaseSupplyWithGold(player, "torches", subCent, subCent,
                                "payload", "test:subcent-supply", "2026-08-08", 1)
                        .toCompletableFuture().join().status());
        assertEquals(new BigDecimal("100.00"), gold.balance(player).toCompletableFuture().join());
    }

    @Test
    void concurrentCreditsWithOneIdempotencyKeyApplyExactlyOnce() {
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        List<CompletableFuture<CurrencyOperationResult>> credits = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            credits.add(economy.credit(player, new BigDecimal("10.00"), "credit:same-key", "test")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(credits.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, credits.stream().filter(result ->
                result.join().status() == CurrencyOperationResult.Status.SUCCESS).count());
        assertEquals(19, credits.stream().filter(result ->
                result.join().status() == CurrencyOperationResult.Status.DUPLICATE).count());
        assertEquals(new BigDecimal("10.00"), economy.balance(player).toCompletableFuture().join());
    }

    @Test
    void activityRewardsRespectCapsAndPurchaseAtomicallyCreatesDelivery() {
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();

        var first = economy.reward(player, EconomyActivity.HUNTING, new BigDecimal("80.00"), 5,
                new BigDecimal("100.00"), 6, "hunt:reward-1", "hunt").toCompletableFuture().join();
        var second = economy.reward(player, EconomyActivity.HUNTING, new BigDecimal("50.00"), 4,
                new BigDecimal("100.00"), 6, "hunt:reward-2", "hunt").toCompletableFuture().join();
        var replay = economy.reward(player, EconomyActivity.HUNTING, new BigDecimal("50.00"), 4,
                new BigDecimal("100.00"), 6, "hunt:reward-2", "hunt").toCompletableFuture().join();
        EconomyPurchaseResult purchase = economy.purchase(player, new BigDecimal("60.00"), 4,
                "base64-payload", "shop:purchase-1", "protection-stone").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.SUCCESS, first.status());
        assertEquals(ActivityRewardResult.Status.PARTIAL, second.status());
        assertEquals(new BigDecimal("20.00"), second.goldGranted());
        assertEquals(1, second.meritGranted());
        assertEquals(ActivityRewardResult.Status.DUPLICATE, replay.status());
        assertEquals(EconomyPurchaseResult.Status.SUCCESS, purchase.status());
        assertEquals(new BigDecimal("40.00"), purchase.progress().gold());
        assertEquals(2, purchase.progress().merit());
        assertEquals(1, new JdbcMarketService(dataSource, executor, BigDecimal.ZERO)
                .pendingDeliveries(player, 10).toCompletableFuture().join().size());
        var report = new JdbcEconomyAnalyticsService(dataSource, executor).dailyReport().toCompletableFuture().join();
        assertEquals(new BigDecimal("40.00"), report.totalGold());
        assertEquals(new BigDecimal("100.00"), report.mintedGold());
        assertEquals(new BigDecimal("60.00"), report.burnedGold());
        assertEquals(6, report.mintedMerit());
        assertEquals(4, report.spentMerit());
        assertEquals(0, report.activeListingCount());
        assertEquals(1, report.pendingDeliveryCount());
        assertEquals(0, report.quarantinedListingCount());
    }

    @Test
    void exactActivityRewardNeverPartiallyConsumesACommissionQuota() {
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        economy.reward(player, EconomyActivity.GATHERING, new BigDecimal("90.00"), 4,
                new BigDecimal("100.00"), 5, "gather:initial", "gather")
                .toCompletableFuture().join();

        ActivityRewardResult rejected = economy.rewardExact(player, EconomyActivity.GATHERING,
                new BigDecimal("20.00"), 1, new BigDecimal("100.00"), 5,
                "commission:exact", "commission:test").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.DAILY_LIMIT, rejected.status());
        assertEquals(new BigDecimal("90.00"), rejected.progress().gold());
        assertEquals(4, rejected.progress().merit());
        assertEquals(new BigDecimal("0.00"), rejected.goldGranted());
        assertEquals(0, rejected.meritGranted());
    }

    @Test
    void ordinaryActivityRewardUsesOnlyRemainingWalletSpaceAndKeepsMerit() {
        BigDecimal maximum = new BigDecimal("1000000000000.00");
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(
                dataSource, executor, maximum.subtract(new BigDecimal("5.00")));
        UUID player = UUID.randomUUID();

        ActivityRewardResult first = economy.reward(player, EconomyActivity.HUNTING,
                new BigDecimal("10.00"), 2, new BigDecimal("100.00"), 10,
                "wallet-room:hunt", "hunt").toCompletableFuture().join();
        ActivityRewardResult replay = economy.reward(player, EconomyActivity.HUNTING,
                new BigDecimal("10.00"), 2, new BigDecimal("100.00"), 10,
                "wallet-room:hunt", "hunt").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.PARTIAL, first.status());
        assertEquals(new BigDecimal("5.00"), first.goldGranted());
        assertEquals(2, first.meritGranted());
        assertEquals(maximum, first.progress().gold());
        assertEquals(new BigDecimal("5.00"),
                first.progress().quota(EconomyActivity.HUNTING).goldEarned());
        assertEquals(ActivityRewardResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("5.00"), replay.goldGranted());
        assertEquals(2, replay.meritGranted());
    }

    @Test
    void zeroGoldActivityRewardStillSettlesAtTheGoldWalletLimit() {
        BigDecimal maximum = new BigDecimal("1000000000000.00");
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(dataSource, executor, maximum);
        UUID player = UUID.randomUUID();

        ActivityRewardResult result = economy.reward(player, EconomyActivity.GUIDE,
                BigDecimal.ZERO, 3, BigDecimal.ZERO, 10,
                "zero-gold:guide", "guide").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.SUCCESS, result.status());
        assertEquals(new BigDecimal("0.00"), result.goldGranted());
        assertEquals(3, result.meritGranted());
        assertEquals(maximum, result.progress().gold());
        assertEquals(3, result.progress().merit());
    }

    @Test
    void ordinaryActivityRewardUsesOnlyRemainingMeritSpaceAndKeepsGold() throws Exception {
        long maximum = 1_000_000_000L;
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(
                dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        setMerit(player, maximum - 2);

        ActivityRewardResult first = economy.reward(player, EconomyActivity.HUNTING,
                new BigDecimal("10.00"), 5, new BigDecimal("100.00"), 10,
                "merit-room:hunt", "hunt").toCompletableFuture().join();
        ActivityRewardResult replay = economy.reward(player, EconomyActivity.HUNTING,
                new BigDecimal("10.00"), 5, new BigDecimal("100.00"), 10,
                "merit-room:hunt", "hunt").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.PARTIAL, first.status());
        assertEquals(new BigDecimal("10.00"), first.goldGranted());
        assertEquals(2, first.meritGranted());
        assertEquals(maximum, first.progress().merit());
        assertEquals(2, first.progress().quota(EconomyActivity.HUNTING).meritEarned());
        assertEquals(ActivityRewardResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("10.00"), replay.goldGranted());
        assertEquals(2, replay.meritGranted());
    }

    @Test
    void zeroMeritActivityRewardStillSettlesAtTheMeritWalletLimit() throws Exception {
        long maximum = 1_000_000_000L;
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(
                dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        setMerit(player, maximum);

        ActivityRewardResult result = economy.reward(player, EconomyActivity.GUIDE,
                new BigDecimal("3.00"), 0, new BigDecimal("10.00"), 0,
                "zero-merit:guide", "guide").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.SUCCESS, result.status());
        assertEquals(new BigDecimal("3.00"), result.goldGranted());
        assertEquals(0, result.meritGranted());
        assertEquals(new BigDecimal("3.00"), result.progress().gold());
        assertEquals(maximum, result.progress().merit());
    }

    @Test
    void exactActivityRewardAtMeritLimitHasNoSideEffectsAndCanRetryAfterSpending() throws Exception {
        long maximum = 1_000_000_000L;
        JdbcActivityEconomyService activity = new JdbcActivityEconomyService(
                dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        setMerit(player, maximum);

        ActivityRewardResult rejected = activity.rewardExact(player, EconomyActivity.GATHERING,
                BigDecimal.ONE, 1, new BigDecimal("10.00"), 10,
                "merit-limit:commission", "commission:test").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.MERIT_LIMIT, rejected.status());
        assertEquals(new BigDecimal("0.00"), rejected.progress().gold());
        assertEquals(maximum, rejected.progress().merit());
        assertEquals(new BigDecimal("0.00"),
                rejected.progress().quota(EconomyActivity.GATHERING).goldEarned());
        assertEquals(0, rejected.progress().quota(EconomyActivity.GATHERING).meritEarned());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_activity_ledger "
                + "WHERE idempotency_key = 'merit-limit:commission'"));

        EconomyPurchaseResult spend = activity.purchase(player, BigDecimal.ZERO, 1,
                "payload", "merit-limit:spend", "test").toCompletableFuture().join();
        assertEquals(EconomyPurchaseResult.Status.SUCCESS, spend.status());
        ActivityRewardResult retried = activity.rewardExact(player, EconomyActivity.GATHERING,
                BigDecimal.ONE, 1, new BigDecimal("10.00"), 10,
                "merit-limit:commission", "commission:test").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.SUCCESS, retried.status());
        assertEquals(new BigDecimal("1.00"), retried.progress().gold());
        assertEquals(maximum, retried.progress().merit());
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_activity_ledger "
                + "WHERE idempotency_key = 'merit-limit:commission'"));
    }

    @Test
    void exactActivityRewardAtWalletLimitHasNoSideEffectsAndCanRetryAfterSpending() throws Exception {
        BigDecimal maximum = new BigDecimal("1000000000000.00");
        JdbcActivityEconomyService activity = new JdbcActivityEconomyService(dataSource, executor, maximum);
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();

        ActivityRewardResult rejected = activity.rewardExact(player, EconomyActivity.GATHERING,
                new BigDecimal("1.00"), 0, new BigDecimal("100.00"), 10,
                "wallet-limit:commission", "commission:test").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(maximum, rejected.progress().gold());
        assertEquals(new BigDecimal("0.00"),
                rejected.progress().quota(EconomyActivity.GATHERING).goldEarned());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_activity_ledger "
                + "WHERE idempotency_key = 'wallet-limit:commission'"));
        assertEquals(CurrencyOperationResult.Status.SUCCESS, gold.burn(player, new BigDecimal("1.00"),
                "wallet-limit:spend", "test").toCompletableFuture().join().status());

        ActivityRewardResult retried = activity.rewardExact(player, EconomyActivity.GATHERING,
                new BigDecimal("1.00"), 0, new BigDecimal("100.00"), 10,
                "wallet-limit:commission", "commission:test").toCompletableFuture().join();
        assertEquals(ActivityRewardResult.Status.SUCCESS, retried.status());
        assertEquals(maximum, retried.progress().gold());
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_activity_ledger "
                + "WHERE idempotency_key = 'wallet-limit:commission'"));
    }

    @Test
    void concurrentActivityRewardsNeverCrossWalletLimitAndRejectedKeysRemainRetryable() throws Exception {
        BigDecimal maximum = new BigDecimal("1000000000000.00");
        JdbcActivityEconomyService activity = new JdbcActivityEconomyService(
                dataSource, executor, maximum.subtract(new BigDecimal("10.00")));
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        List<CompletableFuture<ActivityRewardResult>> rewards = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            rewards.add(activity.reward(player, EconomyActivity.HUNTING, BigDecimal.ONE, 0,
                    new BigDecimal("100.00"), 10, "wallet-race:hunt-" + index, "hunt")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(rewards.toArray(CompletableFuture[]::new)).join();

        assertEquals(10, rewards.stream().filter(result ->
                result.join().status() == ActivityRewardResult.Status.SUCCESS).count());
        assertEquals(10, rewards.stream().filter(result ->
                result.join().status() == ActivityRewardResult.Status.BALANCE_LIMIT).count());
        assertEquals(maximum, activity.progress(player).toCompletableFuture().join().gold());
        assertEquals(10, scalar("SELECT COUNT(*) FROM rpg_activity_ledger "
                + "WHERE idempotency_key LIKE 'wallet-race:hunt-%'"));

        int rejectedIndex = java.util.stream.IntStream.range(0, rewards.size())
                .filter(index -> rewards.get(index).join().status() == ActivityRewardResult.Status.BALANCE_LIMIT)
                .findFirst().orElseThrow();
        assertEquals(CurrencyOperationResult.Status.SUCCESS, gold.burn(player, BigDecimal.ONE,
                "wallet-race:spend", "test").toCompletableFuture().join().status());
        ActivityRewardResult retry = activity.reward(player, EconomyActivity.HUNTING, BigDecimal.ONE, 0,
                new BigDecimal("100.00"), 10, "wallet-race:hunt-" + rejectedIndex, "hunt")
                .toCompletableFuture().join();
        assertEquals(ActivityRewardResult.Status.SUCCESS, retry.status());
        assertEquals(maximum, retry.progress().gold());
    }

    @Test
    void concurrentActivityRewardsNeverCrossMeritLimitAndRejectedKeysRemainRetryable() throws Exception {
        long maximum = 1_000_000_000L;
        JdbcActivityEconomyService activity = new JdbcActivityEconomyService(
                dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        setMerit(player, maximum - 10);
        List<CompletableFuture<ActivityRewardResult>> rewards = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            rewards.add(activity.reward(player, EconomyActivity.HUNTING, BigDecimal.ZERO, 1,
                    BigDecimal.ZERO, 100, "merit-race:hunt-" + index, "hunt")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(rewards.toArray(CompletableFuture[]::new)).join();

        assertEquals(10, rewards.stream().filter(result ->
                result.join().status() == ActivityRewardResult.Status.SUCCESS).count());
        assertEquals(10, rewards.stream().filter(result ->
                result.join().status() == ActivityRewardResult.Status.MERIT_LIMIT).count());
        assertEquals(maximum, activity.progress(player).toCompletableFuture().join().merit());
        assertEquals(10, scalar("SELECT COUNT(*) FROM rpg_activity_ledger "
                + "WHERE idempotency_key LIKE 'merit-race:hunt-%'"));

        int rejectedIndex = java.util.stream.IntStream.range(0, rewards.size())
                .filter(index -> rewards.get(index).join().status() == ActivityRewardResult.Status.MERIT_LIMIT)
                .findFirst().orElseThrow();
        assertEquals(EconomyPurchaseResult.Status.SUCCESS, activity.purchase(
                player, BigDecimal.ZERO, 1, "payload", "merit-race:spend", "test")
                .toCompletableFuture().join().status());
        ActivityRewardResult retry = activity.reward(player, EconomyActivity.HUNTING, BigDecimal.ZERO, 1,
                BigDecimal.ZERO, 100, "merit-race:hunt-" + rejectedIndex, "hunt")
                .toCompletableFuture().join();
        assertEquals(ActivityRewardResult.Status.SUCCESS, retry.status());
        assertEquals(maximum, retry.progress().merit());
    }

    @Test
    void commissionEvidenceIsCrossNodeAtomicIdempotentAndReleasedReceiptsStayDead() {
        JdbcCommissionEvidenceService nodeA = new JdbcCommissionEvidenceService(dataSource, executor);
        JdbcCommissionEvidenceService nodeB = new JdbcCommissionEvidenceService(dataSource, executor);
        UUID player = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 7);

        List<CompletableFuture<?>> grants = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            JdbcCommissionEvidenceService node = index % 2 == 0 ? nodeA : nodeB;
            grants.add(node.grant(player, date, "natural-mining", 1, 3, 4).toCompletableFuture());
        }
        CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)).join();
        assertEquals(3, nodeA.snapshot(player, date, 4).toCompletableFuture().join().earned());
        nodeB.grant(player, date, "advancement", 2, 2, 4).toCompletableFuture().join();
        assertEquals(4, nodeA.snapshot(player, date, 4).toCompletableFuture().join().earned());

        List<UUID> receiptIds = new ArrayList<>();
        List<CompletableFuture<CommissionEvidenceClaimResult>> claims = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            UUID receiptId = UUID.randomUUID();
            receiptIds.add(receiptId);
            JdbcCommissionEvidenceService node = index % 2 == 0 ? nodeA : nodeB;
            claims.add(node.claim(player, date, receiptId, "offer-" + index, 1,
                    3, 1, 4).toCompletableFuture());
        }
        CompletableFuture.allOf(claims.toArray(CompletableFuture[]::new)).join();
        assertEquals(3, claims.stream().filter(result ->
                result.join().status() == CommissionEvidenceClaimResult.Status.SUCCESS).count());
        var full = nodeA.snapshot(player, date, 4).toCompletableFuture().join();
        assertEquals(3, full.submissions());
        assertEquals(3, full.used());

        int successfulIndex = java.util.stream.IntStream.range(0, claims.size())
                .filter(index -> claims.get(index).join().status()
                        == CommissionEvidenceClaimResult.Status.SUCCESS)
                .findFirst().orElseThrow();
        UUID successfulReceipt = receiptIds.get(successfulIndex);
        String successfulOffer = "offer-" + successfulIndex;
        assertEquals(CommissionEvidenceClaimResult.Status.SUCCESS,
                nodeB.claim(player, date, successfulReceipt, successfulOffer, 1, 3, 1, 4)
                        .toCompletableFuture().join().status());
        assertTrue(nodeA.release(player, date, successfulReceipt).toCompletableFuture().join());
        assertEquals(2, nodeB.snapshot(player, date, 4).toCompletableFuture().join().submissions());
        assertEquals(CommissionEvidenceClaimResult.Status.INVALID,
                nodeB.claim(player, date, successfulReceipt, successfulOffer, 1, 3, 1, 4)
                        .toCompletableFuture().join().status());

        CommissionEvidenceClaimResult replacement = nodeA.claim(
                player, date, UUID.randomUUID(), "replacement", 1, 3, 1, 4)
                .toCompletableFuture().join();
        assertEquals(CommissionEvidenceClaimResult.Status.SUCCESS, replacement.status());
        assertEquals(3, replacement.snapshot().submissions());
    }

    @Test
    void systemDeliveryEnqueueIsIdempotentAndRejectsConflictingReplay() {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        UUID deliveryId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        assertTrue(market.enqueueDelivery(deliveryId, playerId, "payload", "commission-refund:logs")
                .toCompletableFuture().join());
        assertTrue(market.enqueueDelivery(deliveryId, playerId, "payload", "commission-refund:logs")
                .toCompletableFuture().join());
        assertFalse(market.enqueueDelivery(deliveryId, playerId, "different", "commission-refund:logs")
                .toCompletableFuture().join());
        assertEquals(1, market.pendingDeliveries(playerId, 10).toCompletableFuture().join().size());
    }

    @Test
    void merchantTransactionsEnforcePeriodLimitsAndNeverCreateBuySellArbitrageInternally() {
        JdbcMerchantService merchant = new JdbcMerchantService(dataSource, executor, new BigDecimal("1000.00"));
        UUID playerId = UUID.randomUUID();

        MerchantTransactionResult purchase = merchant.purchase(
                playerId, "mutation-shards", "2026-p15", 1, 2, new BigDecimal("200.00"),
                "payload", "merchant-buy-1").toCompletableFuture().join();
        MerchantTransactionResult buyback = merchant.buyback(
                playerId, "mutation-shards", "2026-p15", 1, 1, new BigDecimal("100.00"),
                "merchant-sell-1").toCompletableFuture().join();
        MerchantTransactionResult limited = merchant.purchase(
                playerId, "mutation-shards", "2026-p15", 2, 2, new BigDecimal("200.00"),
                "payload", "merchant-buy-2").toCompletableFuture().join();
        MerchantTransactionResult replay = merchant.buyback(
                playerId, "mutation-shards", "2026-p15", 1, 1, new BigDecimal("100.00"),
                "merchant-sell-1").toCompletableFuture().join();

        assertEquals(MerchantTransactionResult.Status.SUCCESS, purchase.status());
        assertEquals(MerchantTransactionResult.Status.SUCCESS, buyback.status());
        assertEquals(new BigDecimal("900.00"), buyback.balance());
        assertEquals(MerchantTransactionResult.Status.LIMIT_REACHED, limited.status());
        assertEquals(MerchantTransactionResult.Status.DUPLICATE, replay.status());
        var usage = merchant.usage(playerId, "2026-p15").toCompletableFuture().join();
        assertEquals(1, usage.get("mutation-shards").purchasedUnits());
        assertEquals(1, usage.get("mutation-shards").buybackUnits());
        assertEquals(1, new JdbcMarketService(dataSource, executor, BigDecimal.ZERO)
                .pendingDeliveries(playerId, 10).toCompletableFuture().join().size());
    }

    @Test
    void merchantBuybackAtWalletLimitDoesNotConsumeIssuanceOrQuotaAndCanRetry() throws Exception {
        BigDecimal maximum = new BigDecimal("1000000000000.00");
        JdbcMerchantService merchant = new JdbcMerchantService(
                dataSource, executor, maximum.subtract(new BigDecimal("50.00")));
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();

        MerchantTransactionResult rejected = merchant.buyback(player, "ruins-cores", "2026-p20",
                1, 2, new BigDecimal("100.00"), "merchant-wallet-limit")
                .toCompletableFuture().join();
        assertEquals(MerchantTransactionResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(maximum.subtract(new BigDecimal("50.00")), rejected.balance());
        assertEquals(0, rejected.usedUnits());
        assertEquals(0, merchant.usage(player, "2026-p20").toCompletableFuture().join()
                .get("ruins-cores").buybackUnits());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_merchant_ledger "
                + "WHERE idempotency_key = 'merchant-wallet-limit'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key = 'merchant:merchant-wallet-limit'"));

        assertEquals(CurrencyOperationResult.Status.SUCCESS, gold.burn(player, new BigDecimal("50.00"),
                "merchant-wallet:spend", "test").toCompletableFuture().join().status());
        MerchantTransactionResult retried = merchant.buyback(player, "ruins-cores", "2026-p20",
                1, 2, new BigDecimal("100.00"), "merchant-wallet-limit")
                .toCompletableFuture().join();
        assertEquals(MerchantTransactionResult.Status.SUCCESS, retried.status());
        assertEquals(maximum, retried.balance());
        assertEquals(1, retried.usedUnits());
    }

    @Test
    void concurrentMerchantBuybacksNeverCrossWalletLimitOrSpendRejectedKeys() throws Exception {
        BigDecimal maximum = new BigDecimal("1000000000000.00");
        JdbcMerchantService merchant = new JdbcMerchantService(
                dataSource, executor, maximum.subtract(new BigDecimal("100.00")));
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        List<CompletableFuture<MerchantTransactionResult>> attempts = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            attempts.add(merchant.buyback(player, "ruins-cores", "2026-p21", 1, 20,
                    new BigDecimal("100.00"), "merchant-wallet-race-" + index)
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, attempts.stream().filter(result ->
                result.join().status() == MerchantTransactionResult.Status.SUCCESS).count());
        assertEquals(19, attempts.stream().filter(result ->
                result.join().status() == MerchantTransactionResult.Status.BALANCE_LIMIT).count());
        assertEquals(maximum, attempts.stream().map(CompletableFuture::join)
                .map(MerchantTransactionResult::balance).max(BigDecimal::compareTo).orElseThrow());
        assertEquals(1, merchant.usage(player, "2026-p21").toCompletableFuture().join()
                .get("ruins-cores").buybackUnits());
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_merchant_ledger "
                + "WHERE idempotency_key LIKE 'merchant-wallet-race-%'"));

        int rejectedIndex = java.util.stream.IntStream.range(0, attempts.size())
                .filter(index -> attempts.get(index).join().status()
                        == MerchantTransactionResult.Status.BALANCE_LIMIT)
                .findFirst().orElseThrow();
        assertEquals(CurrencyOperationResult.Status.SUCCESS, gold.burn(player, new BigDecimal("100.00"),
                "merchant-wallet-race:spend", "test").toCompletableFuture().join().status());
        MerchantTransactionResult retry = merchant.buyback(player, "ruins-cores", "2026-p21",
                1, 20, new BigDecimal("100.00"), "merchant-wallet-race-" + rejectedIndex)
                .toCompletableFuture().join();
        assertEquals(MerchantTransactionResult.Status.SUCCESS, retry.status());
        assertEquals(maximum, retry.balance());
        assertEquals(2, retry.usedUnits());
    }

    @Test
    void chapterGateIsMonotonicAuditedAndImmediatelyControlsAscension() throws Exception {
        List<ChapterDefinition> definitions = List.of(
                new ChapterDefinition("awakening", 0, 0),
                new ChapterDefinition("resonance", 1, 1),
                new ChapterDefinition("convergence", 2, 2),
                new ChapterDefinition("transcendence", 3, 3));
        JdbcChapterService chapters = new JdbcChapterService(
                dataSource, executor, "launch-1", "awakening", definitions);
        assertEquals("awakening", chapters.initialize().toCompletableFuture().join().chapterId());

        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcAscensionService ascensions = new JdbcAscensionService(
                dataSource, executor, profiles, chapters::maximumOpenAscension);
        UUID player = UUID.randomUUID();
        profiles.chooseArchetype(player, Archetype.WARRIOR).toCompletableFuture().join();
        profiles.grantExperience(player, 1_000_000_000L).toCompletableFuture().join();
        seedAscensionFacts(player, 2, 1, 20, "ancient-ruins");
        AscensionEvidence evidence = new AscensionEvidence(
                1, true, 40, Set.of(UUID.randomUUID()));
        assertEquals(AscensionResult.Status.CHAPTER_LOCKED,
                ascensions.eligibility(player, evidence).toCompletableFuture().join().status());

        ChapterTransitionResult skipped = chapters.advance(
                "convergence", "test", "skip must fail").toCompletableFuture().join();
        assertEquals(ChapterTransitionResult.Status.NOT_NEXT_CHAPTER, skipped.status());
        ChapterTransitionResult advanced = chapters.advance(
                "resonance", "test", "balance gates passed").toCompletableFuture().join();
        assertEquals(ChapterTransitionResult.Status.SUCCESS, advanced.status());
        assertEquals(1, advanced.state().revision());
        AscensionResult ascended = ascensions.ascend(player, evidence).toCompletableFuture().join();
        assertEquals(AscensionResult.Status.SUCCESS, ascended.status());
        assertEquals(1, ascended.profile().ascension());

        ChapterTransitionResult replay = chapters.advance(
                "resonance", "other-shard", "same transition replay").toCompletableFuture().join();
        assertEquals(ChapterTransitionResult.Status.ALREADY_CURRENT, replay.status());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM rpg_chapter_history")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            connection.rollback();
        }
    }

    @Test
    void ascensionBypassEntryPointIsGoneFromTheProfileSurface() {
        // ProfileService 只能读写档案，不得再暴露转生入口（AGENTS.md §3 / C-015）。
        assertThrows(NoSuchMethodException.class,
                () -> ProfileService.class.getMethod("ascend", UUID.class));
        assertThrows(NoSuchMethodException.class,
                () -> JdbcProfileService.class.getMethod("ascend", UUID.class));
        long declarations = java.util.Arrays.stream(ProfileService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("ascend"))
                .count();
        assertEquals(0L, declarations);
    }

    @Test
    void ascensionOnlyHappensThroughTheServiceAndIsAudited() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID player = UUID.randomUUID();
        profiles.chooseArchetype(player, Archetype.WARRIOR).toCompletableFuture().join();
        profiles.grantExperience(player, 1_000_000_000L).toCompletableFuture().join();

        seedAscensionFacts(player, 2, 1, 20, "ancient-ruins");
        AscensionResult result = ascensions.ascend(player,
                        new AscensionEvidence(1, true, 40, Set.of(UUID.randomUUID())))
                .toCompletableFuture().join();
        assertEquals(AscensionResult.Status.SUCCESS, result.status());
        assertEquals(1, result.profile().ascension());
        assertEquals(1, profiles.get(player).toCompletableFuture().join().ascension());
        // 权威路径必须留下收据与功勋流水，简化旁路不会写这两张表。
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_ascension_receipts WHERE player_uuid = '" + player + "'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_merit_ledger WHERE idempotency_key = 'ascension:"
                + player + ":1'"));
    }

    @Test
    void compoundAscensionRechecksFactsDebitsMeritAndRejectsDoubleClick() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID player = UUID.randomUUID();
        profiles.chooseArchetype(player, Archetype.WARRIOR).toCompletableFuture().join();
        profiles.grantExperience(player, 1_000_000_000L).toCompletableFuture().join();
        AscensionEvidence evidence = new AscensionEvidence(
                1, true, 40, Set.of(UUID.randomUUID()));

        assertEquals(AscensionResult.Status.SKILLS_REQUIRED,
                ascensions.eligibility(player, evidence).toCompletableFuture().join().status());
        seedAscensionFacts(player, 2, 1, 20, "ancient-ruins");
        assertEquals(AscensionResult.Status.READY,
                ascensions.eligibility(player, evidence).toCompletableFuture().join().status());

        CompletableFuture<AscensionResult> first = ascensions.ascend(player, evidence).toCompletableFuture();
        CompletableFuture<AscensionResult> second = ascensions.ascend(player, evidence).toCompletableFuture();
        CompletableFuture.allOf(first, second).join();
        assertEquals(1, List.of(first.join(), second.join()).stream()
                .filter(result -> result.status() == AscensionResult.Status.SUCCESS).count());
        assertEquals(1, List.of(first.join(), second.join()).stream()
                .filter(result -> result.status() == AscensionResult.Status.STALE_REQUEST).count());
        assertEquals(1, profiles.get(player).toCompletableFuture().join().ascension());
        assertEquals(0, scalar("SELECT merit FROM rpg_progression_wallets WHERE player_uuid = '" + player + "'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_ascension_receipts WHERE player_uuid = '" + player + "'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_merit_ledger WHERE idempotency_key = 'ascension:"
                + player + ":1'"));
    }

    @Test
    void secondAscensionRequiresThePreviousBranchInsideTheTransaction() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID player = UUID.randomUUID();
        profiles.chooseArchetype(player, Archetype.RANGER).toCompletableFuture().join();
        profiles.grantExperience(player, 1_000_000_000L).toCompletableFuture().join();
        seedAscensionFacts(player, 2, 1, 20, "ancient-ruins");
        assertEquals(AscensionResult.Status.SUCCESS, ascensions.ascend(player,
                new AscensionEvidence(1, true, 40, Set.of(UUID.randomUUID())))
                .toCompletableFuture().join().status());

        profiles.grantExperience(player, 1_000_000_000L).toCompletableFuture().join();
        seedAscensionFacts(player, 5, 10, 100,
                "sulfur-depths", "sulfur-depths-hidden", "storm-vault");
        AscensionEvidence evidence = new AscensionEvidence(
                2, true, 160, Set.of(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(AscensionResult.Status.PREVIOUS_BRANCH_REQUIRED,
                ascensions.eligibility(player, evidence).toCompletableFuture().join().status());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_class_branches (player_uuid, stage, branch_id) VALUES ('"
                    + player + "', 1, 'scout')");
            connection.commit();
        }
        assertEquals(AscensionResult.Status.READY,
                ascensions.eligibility(player, evidence).toCompletableFuture().join().status());
    }

    @Test
    void laterAscensionRejectsRepeatedTutorialDungeonClears() throws Exception {
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        JdbcAscensionService ascensions = new JdbcAscensionService(dataSource, executor, profiles, () -> 3);
        UUID player = UUID.randomUUID();
        profiles.chooseArchetype(player, Archetype.MAGE).toCompletableFuture().join();
        profiles.grantExperience(player, 1_000_000_000L).toCompletableFuture().join();
        seedAscensionFacts(player, 2, 1, 20, "ancient-ruins");
        assertEquals(AscensionResult.Status.SUCCESS, ascensions.ascend(player,
                new AscensionEvidence(1, true, 40, Set.of(UUID.randomUUID())))
                .toCompletableFuture().join().status());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_class_branches (player_uuid, stage, branch_id) VALUES ('"
                    + player + "', 1, 'pyromancer')");
            connection.commit();
        }
        profiles.grantExperience(player, 1_000_000_000L).toCompletableFuture().join();
        seedAscensionFacts(player, 5, 40, 100, "ancient-ruins");
        AscensionEvidence evidence = new AscensionEvidence(
                2, true, 160, Set.of(UUID.randomUUID(), UUID.randomUUID()));
        AscensionResult rejected = ascensions.eligibility(player, evidence).toCompletableFuture().join();
        assertEquals(AscensionResult.Status.DUNGEONS_REQUIRED, rejected.status());
        assertEquals(0, rejected.dungeonClears());
        assertEquals(0, rejected.distinctDungeons());

        seedAscensionFacts(player, 5, 10, 100,
                "sulfur-depths", "sulfur-depths-hidden", "storm-vault");
        AscensionResult ready = ascensions.eligibility(player, evidence).toCompletableFuture().join();
        assertEquals(AscensionResult.Status.READY, ready.status());
        assertEquals(10, ready.dungeonClears());
        assertEquals(3, ready.distinctDungeons());
    }

    @Test
    void duplicatedMerchantIssuanceCannotMintGoldForAnotherAccount() {
        JdbcMerchantService merchant = new JdbcMerchantService(dataSource, executor, new BigDecimal("1000.00"));
        UUID firstHolder = UUID.randomUUID();
        UUID duplicateHolder = UUID.randomUUID();
        String issuanceKey = "merchant-buyback:" + UUID.randomUUID();

        MerchantTransactionResult first = merchant.buyback(
                firstHolder, "ruins-cores", "2026-p15", 1, 2, new BigDecimal("100.00"), issuanceKey)
                .toCompletableFuture().join();
        MerchantTransactionResult duplicate = merchant.buyback(
                duplicateHolder, "ruins-cores", "2026-p15", 1, 2, new BigDecimal("100.00"), issuanceKey)
                .toCompletableFuture().join();

        assertEquals(MerchantTransactionResult.Status.SUCCESS, first.status());
        assertEquals(new BigDecimal("1100.00"), first.balance());
        assertEquals(MerchantTransactionResult.Status.DUPLICATE, duplicate.status());
        assertEquals(new BigDecimal("1000.00"), duplicate.balance());
        assertTrue(merchant.usage(duplicateHolder, "2026-p15").toCompletableFuture().join().isEmpty());
    }

    @Test
    void concurrentMerchantReplayCreatesOneChargeOneDeliveryAndOneLimitUse() {
        JdbcMerchantService merchant = new JdbcMerchantService(dataSource, executor, new BigDecimal("1000.00"));
        UUID playerId = UUID.randomUUID();
        List<CompletableFuture<MerchantTransactionResult>> attempts = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            attempts.add(merchant.purchase(playerId, "ruins-cores", "2026-p16", 1, 5,
                    new BigDecimal("200.00"), "payload", "merchant-concurrent-same-key")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, attempts.stream().filter(result ->
                result.join().status() == MerchantTransactionResult.Status.SUCCESS).count());
        assertEquals(19, attempts.stream().filter(result ->
                result.join().status() == MerchantTransactionResult.Status.DUPLICATE).count());
        assertTrue(attempts.stream().allMatch(result ->
                result.join().balance().compareTo(new BigDecimal("800.00")) >= 0));
        assertEquals(1, merchant.usage(playerId, "2026-p16").toCompletableFuture().join()
                .get("ruins-cores").purchasedUnits());
        assertEquals(1, new JdbcMarketService(dataSource, executor, BigDecimal.ZERO)
                .pendingDeliveries(playerId, 10).toCompletableFuture().join().size());
    }

    @Test
    void concurrentFirstRewardsCreateOneAccountAndPreserveEveryGrant() {
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        var futures = new ArrayList<CompletableFuture<ActivityRewardResult>>();
        for (int index = 0; index < 20; index++) {
            futures.add(economy.reward(player, EconomyActivity.HUNTING, new BigDecimal("10.00"), 1,
                    new BigDecimal("1000.00"), 100, "concurrent:hunt-" + index, "hunt")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        assertTrue(futures.stream().allMatch(future -> future.join().status() == ActivityRewardResult.Status.SUCCESS));
        var progress = economy.progress(player).toCompletableFuture().join();
        assertEquals(new BigDecimal("200.00"), progress.gold());
        assertEquals(20, progress.merit());
        assertEquals(new BigDecimal("200.00"), progress.quota(EconomyActivity.HUNTING).goldEarned());
    }

    @Test
    void activityRewardBatchLocksOnceWhileKeepingPerEventIdempotency() {
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        List<ActivityRewardRequest> requests = List.of(
                new ActivityRewardRequest(new BigDecimal("40.00"), 2, "batch:hunt-1", "hunt"),
                new ActivityRewardRequest(new BigDecimal("40.00"), 2, "batch:hunt-2", "hunt"),
                new ActivityRewardRequest(new BigDecimal("40.00"), 2, "batch:hunt-3", "hunt")
        );

        ActivityRewardResult batch = economy.rewardBatch(player, EconomyActivity.HUNTING, requests,
                new BigDecimal("100.00"), 5).toCompletableFuture().join();
        ActivityRewardResult replay = economy.reward(player, EconomyActivity.HUNTING, new BigDecimal("40.00"), 2,
                new BigDecimal("100.00"), 5, "batch:hunt-2", "hunt").toCompletableFuture().join();

        assertEquals(ActivityRewardResult.Status.PARTIAL, batch.status());
        assertEquals(new BigDecimal("100.00"), batch.goldGranted());
        assertEquals(5, batch.meritGranted());
        assertEquals(ActivityRewardResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("100.00"), economy.progress(player).toCompletableFuture().join().gold());
    }

    @Test
    void concurrentActivityRewardReplayReturnsDuplicateWithoutDoubleGrant() {
        JdbcActivityEconomyService economy = new JdbcActivityEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID player = UUID.randomUUID();
        List<CompletableFuture<ActivityRewardResult>> rewards = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            rewards.add(economy.reward(player, EconomyActivity.HUNTING, new BigDecimal("25.00"), 1,
                    new BigDecimal("1000.00"), 100, "hunt:same-entity", "test")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(rewards.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, rewards.stream().filter(result ->
                result.join().status() == ActivityRewardResult.Status.SUCCESS).count());
        assertEquals(19, rewards.stream().filter(result ->
                result.join().status() == ActivityRewardResult.Status.DUPLICATE).count());
        var progress = economy.progress(player).toCompletableFuture().join();
        assertEquals(new BigDecimal("25.00"), progress.gold());
        assertEquals(1, progress.merit());
    }

    @Test
    void paymentAndCumulativeRewardAreIdempotent() {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        UUID playerId = UUID.randomUUID();

        CommerceResult payment = commerce.recordPayment(playerId, new BigDecimal("100.00"),
                "order-20260805-001", 30, "console").toCompletableFuture().join();
        CommerceResult replay = commerce.recordPayment(playerId, new BigDecimal("100.00"),
                "order-20260805-001", 30, "console").toCompletableFuture().join();
        RewardClaimResult claim = commerce.claimReward(
                        playerId, "veteran-support", new BigDecimal("100.00"), "reward-payload")
                .toCompletableFuture().join();
        RewardClaimResult duplicateClaim = commerce.claimReward(
                        playerId, "veteran-support", new BigDecimal("100.00"), "reward-payload")
                .toCompletableFuture().join();

        assertEquals(CommerceResult.Status.SUCCESS, payment.status());
        assertEquals(CommerceResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("100.00"), replay.account().lifetimePaid());
        assertTrue(payment.account().memberUntil() != null);
        assertEquals(RewardClaimResult.Status.SUCCESS, claim.status());
        assertTrue(claim.deliveryId().isPresent());
        assertEquals(RewardClaimResult.Status.ALREADY_CLAIMED, duplicateClaim.status());
        var rewardDeliveries = new JdbcMarketService(dataSource, executor, new BigDecimal("100.00"))
                .pendingDeliveries(playerId, 10).toCompletableFuture().join();
        assertEquals(1, rewardDeliveries.size());
        assertEquals("commerce-reward:veteran-support", rewardDeliveries.getFirst().source());
    }

    @Test
    void creditsUseUniqueAdjustmentsAndPurchases() throws Exception {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        UUID player = UUID.randomUUID();

        CreditResult grant = commerce.adjustCredits(player, 100, "manual-credit-001", "console")
                .toCompletableFuture().join();
        CreditResult replay = commerce.adjustCredits(player, 100, "manual-credit-001", "console")
                .toCompletableFuture().join();
        CommercePurchaseResult purchase = commerce.purchaseWithCredits(
                player, "lucky-charm", 30, "base64-payload", "credit-purchase-001")
                .toCompletableFuture().join();
        CommercePurchaseResult purchaseReplay = commerce.purchaseWithCredits(
                player, "lucky-charm", 30, "base64-payload", "credit-purchase-001")
                .toCompletableFuture().join();

        assertEquals(CreditResult.Status.SUCCESS, grant.status());
        assertEquals(CreditResult.Status.DUPLICATE, replay.status());
        assertEquals(CommercePurchaseResult.Status.SUCCESS, purchase.status());
        assertEquals(CommercePurchaseResult.Status.DUPLICATE, purchaseReplay.status());
        assertEquals(70, purchase.account().credits());
        assertTrue(purchase.deliveryId().isPresent());
        try (Connection connection = dataSource.getConnection(); var query = connection.prepareStatement("""
                SELECT COUNT(*) AS entries, COALESCE(SUM(delta), 0) AS total
                FROM rpg_credit_ledger WHERE external_reference = ? AND operator_name = 'commerce-purchase'
                """)) {
            query.setString(1, "starlight-spend:credit-purchase-001");
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("entries"));
                assertEquals(-30L, result.getLong("total"));
            }
            connection.rollback();
        }
    }

    @Test
    void starlightAdministrativeAdjustmentsCannotOverflowTheSharedWealthCeiling() {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        UUID player = UUID.randomUUID();

        CreditResult maximum = commerce.adjustCredits(
                player, 1_000_000_000L, "test-starlight-maximum", "test")
                .toCompletableFuture().join();
        CreditResult overflow = commerce.adjustCredits(
                player, 1L, "test-starlight-overflow", "test")
                .toCompletableFuture().join();

        assertEquals(CreditResult.Status.SUCCESS, maximum.status());
        assertEquals(CreditResult.Status.BALANCE_LIMIT, overflow.status());
        assertEquals(1_000_000_000L, commerce.status(player).toCompletableFuture().join().credits());
    }

    @Test
    void starlightBalanceLimitDoesNotConsumeTheAdjustmentReference() {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO,
                new OperationalStarlightPolicy(1_000_000_000L, 1_000_000_001L,
                        1_000_000_001L, ZoneId.of("UTC")));
        UUID player = UUID.randomUUID();
        commerce.adjustCredits(player, 1_000_000_000L, "test-starlight-cap-seed", "test")
                .toCompletableFuture().join();

        CreditResult rejected = commerce.adjustCredits(player, 1L,
                "test-starlight-cap-retry", "test").toCompletableFuture().join();
        commerce.adjustCredits(player, -1L, "test-starlight-cap-spend", "test")
                .toCompletableFuture().join();
        CreditResult retried = commerce.adjustCredits(player, 1L,
                "test-starlight-cap-retry", "test").toCompletableFuture().join();

        assertEquals(CreditResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(CreditResult.Status.SUCCESS, retried.status());
    }

    @Test
    void administrativeStarlightBudgetIsGlobalAtomicAndConcurrencySafe() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T12:00:00Z"));
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO,
                new OperationalStarlightPolicy(5L, 30L, 50L, ZoneId.of("Asia/Shanghai")), clock);
        List<UUID> players = java.util.stream.IntStream.range(0, 24)
                .mapToObj(ignored -> UUID.randomUUID()).toList();
        List<CompletableFuture<CreditResult>> grants = new ArrayList<>();
        for (int index = 0; index < players.size(); index++) {
            grants.add(commerce.adjustCredits(players.get(index), 5L,
                    "ops:grant:" + index, "operator-" + index % 3).toCompletableFuture());
        }
        CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)).join();

        assertEquals(6L, grants.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CreditResult.Status.SUCCESS).count());
        assertEquals(18L, grants.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CreditResult.Status.DAILY_LIMIT_REACHED).count());
        assertEquals(30L, players.stream().map(commerce::status).map(CompletionStage::toCompletableFuture)
                .map(CompletableFuture::join).mapToLong(CommerceStatus::credits).sum());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (var ledger = statement.executeQuery(
                    "SELECT COUNT(*) AS entries, COALESCE(SUM(delta), 0) AS total FROM rpg_credit_ledger")) {
                assertTrue(ledger.next());
                assertEquals(6, ledger.getInt("entries"));
                assertEquals(30L, ledger.getLong("total"));
            }
            try (var limits = statement.executeQuery("""
                    SELECT period_type, granted FROM rpg_starlight_operation_limits ORDER BY period_type
                    """)) {
                assertTrue(limits.next());
                assertEquals("DAY", limits.getString("period_type"));
                assertEquals(30L, limits.getLong("granted"));
                assertTrue(limits.next());
                assertEquals("WEEK", limits.getString("period_type"));
                assertEquals(30L, limits.getLong("granted"));
                assertFalse(limits.next());
            }
            connection.rollback();
        }
    }

    @Test
    void administrativeStarlightUsesOneReferenceAndSeparateCalendarBudgets() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T12:00:00Z"));
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO,
                new OperationalStarlightPolicy(10L, 10L, 10L, ZoneId.of("UTC")), clock);
        UUID player = UUID.randomUUID();

        CreditResult oversized = commerce.adjustCredits(player, 11L, "ops:unique:oversized", "console")
                .toCompletableFuture().join();
        CreditResult first = commerce.adjustCredits(player, 10L, "ops:unique:001", "console")
                .toCompletableFuture().join();
        CreditResult replay = commerce.adjustCredits(player, 10L, "ops:unique:001", "console")
                .toCompletableFuture().join();
        clock.advance(Duration.ofDays(1));
        CreditResult weekly = commerce.adjustCredits(player, 1L, "ops:unique:002", "console")
                .toCompletableFuture().join();
        clock.advance(Duration.ofDays(7));
        CreditResult nextWeek = commerce.adjustCredits(player, 1L, "ops:unique:003", "console")
                .toCompletableFuture().join();

        assertEquals(CreditResult.Status.PER_GRANT_LIMIT_REACHED, oversized.status());
        assertEquals(10L, oversized.maximumPerGrant());
        assertEquals(CreditResult.Status.SUCCESS, first.status());
        assertEquals(CreditResult.Status.DUPLICATE, replay.status());
        assertEquals(CreditResult.Status.WEEKLY_LIMIT_REACHED, weekly.status());
        assertEquals(CreditResult.Status.SUCCESS, nextWeek.status());
        assertEquals(11L, nextWeek.balance());
    }

    @Test
    void concurrentAdministrativeReplayWithOneReferenceSettlesExactlyOnce() {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO,
                new OperationalStarlightPolicy(10L, 100L, 500L, ZoneId.of("UTC")));
        List<UUID> players = java.util.stream.IntStream.range(0, 24)
                .mapToObj(ignored -> UUID.randomUUID()).toList();
        List<CompletableFuture<CreditResult>> grants = new ArrayList<>();
        for (UUID player : players) {
            grants.add(commerce.adjustCredits(player, 10L, "ops:callback:one-order", "console")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)).join();

        assertEquals(1L, grants.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CreditResult.Status.SUCCESS).count());
        assertEquals(23L, grants.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CreditResult.Status.DUPLICATE).count());
        assertEquals(10L, players.stream().map(commerce::status).map(CompletionStage::toCompletableFuture)
                .map(CompletableFuture::join).mapToLong(CommerceStatus::credits).sum());
    }

    @Test
    void administrativeStarlightRollsBackLedgerBalanceAndQuotaTogether() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE rpg_starlight_operation_limits
                    ADD CONSTRAINT test_starlight_quota_failure CHECK (granted <= 10)
                    """);
            connection.commit();
        }
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO,
                new OperationalStarlightPolicy(20L, 100L, 100L, ZoneId.of("UTC")));
        UUID player = UUID.randomUUID();

        assertThrows(CompletionException.class, () -> commerce.adjustCredits(
                player, 20L, "ops:rollback:001", "console").toCompletableFuture().join());
        assertEquals(0L, commerce.status(player).toCompletableFuture().join().credits());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (var ledger = statement.executeQuery("SELECT COUNT(*) FROM rpg_credit_ledger")) {
                assertTrue(ledger.next());
                assertEquals(0, ledger.getInt(1));
            }
            try (var limits = statement.executeQuery("SELECT COUNT(*) FROM rpg_starlight_operation_limits")) {
                assertTrue(limits.next());
                assertEquals(0, limits.getInt(1));
            }
            connection.rollback();
        }
    }

    @Test
    void starlightPowerProductsEnforceWeeklyLimitInsideTheAccountTransaction() {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        UUID player = UUID.randomUUID();
        commerce.adjustCredits(player, 1000, "manual-credit-weekly", "console")
                .toCompletableFuture().join();
        List<CompletableFuture<CommercePurchaseResult>> purchases = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            purchases.add(commerce.purchaseWithCredits(player, "lucky-charm", 30, "payload-" + index,
                    "weekly-purchase-" + index, "2026-W32", 2).toCompletableFuture());
        }
        CompletableFuture.allOf(purchases.toArray(CompletableFuture[]::new)).join();

        assertEquals(2, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommercePurchaseResult.Status.SUCCESS).count());
        assertEquals(8, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommercePurchaseResult.Status.LIMIT_REACHED).count());
        assertEquals(940, commerce.status(player).toCompletableFuture().join().credits());
    }

    @Test
    void earnableStarlightMembershipIsAtomicAndPeriodLimited() throws Exception {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        UUID player = UUID.randomUUID();
        commerce.adjustCredits(player, 500, "membership-credit-seed", "test")
                .toCompletableFuture().join();
        List<CompletableFuture<CommercePurchaseResult>> purchases = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            purchases.add(commerce.purchaseMembershipWithCredits(player, "membership-3-days", 90, 3,
                    "membership-buy-" + index, "2026-W32", 1).toCompletableFuture());
        }
        CompletableFuture.allOf(purchases.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommercePurchaseResult.Status.SUCCESS).count());
        assertEquals(9, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommercePurchaseResult.Status.LIMIT_REACHED).count());
        var status = commerce.status(player).toCompletableFuture().join();
        assertEquals(410, status.credits());
        assertTrue(status.memberUntil().isAfter(Instant.now().plusSeconds(2L * 24L * 60L * 60L)));
        assertTrue(new JdbcMarketService(dataSource, executor, BigDecimal.ZERO)
                .pendingDeliveries(player, 10).toCompletableFuture().join().isEmpty());
        try (Connection connection = dataSource.getConnection(); var query = connection.prepareStatement("""
                SELECT COUNT(*) AS entries, COALESCE(SUM(delta), 0) AS total
                FROM rpg_credit_ledger
                WHERE player_uuid = ? AND delta < 0 AND operator_name = 'commerce-purchase'
                """)) {
            query.setString(1, player.toString());
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("entries"));
                assertEquals(-90L, result.getLong("total"));
            }
            connection.rollback();
        }
    }

    @Test
    void membershipRemainingValidityNeverExceedsThirtyDaysUnderConcurrency() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T12:00:00Z"));
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO,
                new OperationalStarlightPolicy(2_000L, 10_000L, 10_000L, ZoneId.of("UTC")), clock);
        UUID player = UUID.randomUUID();
        commerce.adjustCredits(player, 2_000L, "membership:seed:thirty-day-cap", "test")
                .toCompletableFuture().join();
        List<CompletableFuture<CommercePurchaseResult>> purchases = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            purchases.add(commerce.purchaseMembershipWithCredits(player, "membership-3-days", 90, 3,
                    "membership-cap-" + index, "2026-W32", 100).toCompletableFuture());
        }
        CompletableFuture.allOf(purchases.toArray(CompletableFuture[]::new)).join();

        assertEquals(10L, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommercePurchaseResult.Status.SUCCESS).count());
        assertEquals(10L, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommercePurchaseResult.Status.MEMBERSHIP_LIMIT_REACHED).count());
        CommerceStatus status = commerce.status(player).toCompletableFuture().join();
        assertEquals(1_100L, status.credits());
        assertEquals(clock.instant().plus(30L, ChronoUnit.DAYS), status.memberUntil());
    }

    @Test
    void paymentMembershipRejectsSingleOrAccumulatedTimeBeyondThirtyDaysWithoutPartialLedger() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T12:00:00Z"));
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO,
                new OperationalStarlightPolicy(100L, 1_000L, 1_000L, ZoneId.of("UTC")), clock);
        UUID player = UUID.randomUUID();

        CommerceResult invalidSingle = commerce.recordPayment(player, new BigDecimal("10.00"),
                "payment:membership:31", 31, "console").toCompletableFuture().join();
        CommerceResult first = commerce.recordPayment(player, new BigDecimal("10.00"),
                "payment:membership:30", 30, "console").toCompletableFuture().join();
        CommerceResult overflow = commerce.recordPayment(player, new BigDecimal("20.00"),
                "payment:membership:overflow", 3, "console").toCompletableFuture().join();
        CommerceResult replay = commerce.recordPayment(player, new BigDecimal("10.00"),
                "payment:membership:30", 30, "console").toCompletableFuture().join();

        assertEquals(CommerceResult.Status.INVALID, invalidSingle.status());
        assertEquals(CommerceResult.Status.SUCCESS, first.status());
        assertEquals(CommerceResult.Status.MEMBERSHIP_LIMIT_REACHED, overflow.status());
        assertEquals(CommerceResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("10.00"), commerce.status(player).toCompletableFuture().join().lifetimePaid());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var ledger = statement.executeQuery("SELECT COUNT(*) FROM rpg_payment_ledger")) {
            assertTrue(ledger.next());
            assertEquals(1, ledger.getInt(1));
            connection.rollback();
        }
    }

    @Test
    void lifetimeSupportLimitHasAnAccurateRetryableResult() throws Exception {
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        UUID player = UUID.randomUUID();
        commerce.status(player).toCompletableFuture().join();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_commerce_accounts SET lifetime_paid = 999999999999.50 "
                    + "WHERE player_uuid = '" + player + "'");
            connection.commit();
        }

        CommerceResult rejected = commerce.recordPayment(player, new BigDecimal("1.00"),
                "payment:lifetime-cap-retry", 0, "console").toCompletableFuture().join();
        assertEquals(CommerceResult.Status.LIFETIME_LIMIT_REACHED, rejected.status());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_payment_ledger WHERE external_reference = "
                + "'payment:lifetime-cap-retry'"));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_commerce_accounts SET lifetime_paid = 999999999999.00 "
                    + "WHERE player_uuid = '" + player + "'");
            connection.commit();
        }
        CommerceResult retried = commerce.recordPayment(player, new BigDecimal("1.00"),
                "payment:lifetime-cap-retry", 0, "console").toCompletableFuture().join();
        assertEquals(CommerceResult.Status.SUCCESS, retried.status());
        assertEquals(new BigDecimal("1000000000000.00"), retried.account().lifetimePaid());
    }

    @Test
    void systemSuppliesChooseMembershipPriceAndEnforceDailyLimitAtomically() {
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor, BigDecimal.ZERO);
        UUID member = UUID.randomUUID();
        gold.credit(member, new BigDecimal("5000.00"), "supply-gold-seed-member", "test")
                .toCompletableFuture().join();
        commerce.recordPayment(member, new BigDecimal("1.00"), "supply-membership-seed", 3, "test")
                .toCompletableFuture().join();

        List<CompletableFuture<SupplyPurchaseResult>> purchases = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            purchases.add(commerce.purchaseSupplyWithGold(member, "torches", new BigDecimal("100.00"),
                    new BigDecimal("95.00"), "plain-payload-" + index, "supply-buy-" + index,
                    "2026-08-07", 2).toCompletableFuture());
        }
        CompletableFuture.allOf(purchases.toArray(CompletableFuture[]::new)).join();

        assertEquals(2, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == SupplyPurchaseResult.Status.SUCCESS).count());
        assertEquals(8, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == SupplyPurchaseResult.Status.LIMIT_REACHED).count());
        assertTrue(purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == SupplyPurchaseResult.Status.SUCCESS)
                .allMatch(SupplyPurchaseResult::memberDiscountApplied));
        assertEquals(new BigDecimal("4810.00"), gold.balance(member).toCompletableFuture().join());
        assertEquals(2, new JdbcMarketService(dataSource, executor, BigDecimal.ZERO)
                .pendingDeliveries(member, 10).toCompletableFuture().join().size());

        UUID ordinaryPlayer = UUID.randomUUID();
        gold.credit(ordinaryPlayer, new BigDecimal("500.00"), "supply-gold-seed-public", "test")
                .toCompletableFuture().join();
        SupplyPurchaseResult publicPurchase = commerce.purchaseSupplyWithGold(
                ordinaryPlayer, "torches", new BigDecimal("100.00"), new BigDecimal("95.00"),
                "plain-public-payload", "supply-public-buy", "2026-08-07", 2)
                .toCompletableFuture().join();
        assertEquals(SupplyPurchaseResult.Status.SUCCESS, publicPurchase.status());
        assertFalse(publicPurchase.memberDiscountApplied());
        assertEquals(new BigDecimal("100.00"), publicPurchase.chargedGold());
        assertEquals(new BigDecimal("400.00"), publicPurchase.goldBalance());
    }

    @Test
    void wealthExchangeIsAtomicLossyLimitedAndIdempotent() {
        UUID player = UUID.randomUUID();
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        JdbcActivityEconomyService activity = new JdbcActivityEconomyService(dataSource, executor, BigDecimal.ZERO);
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        gold.credit(player, new BigDecimal("5000.00"), "test:gold-seed", "test").toCompletableFuture().join();
        activity.reward(player, EconomyActivity.GUIDE, BigDecimal.ZERO, 10,
                BigDecimal.ZERO, 10, "test:merit-seed", "test").toCompletableFuture().join();
        commerce.adjustCredits(player, 10, "test-starlight-seed", "test").toCompletableFuture().join();

        JdbcCurrencyExchangeService exchange = new JdbcCurrencyExchangeService(
                dataSource, executor, BigDecimal.ZERO, ZoneId.of("Asia/Shanghai"), List.of(
                quote("gold-merit-to-starlight", "200.00", 2, 0, "0.00", 0, 1, 5, 30),
                quote("starlight-to-gold", "0.00", 0, 1, "250.00", 0, 0, 3, 12)
        ));
        CurrencyExchangeResult first = exchange.exchange(
                player, "gold-merit-to-starlight", 2, "exchange:test-001").toCompletableFuture().join();
        CurrencyExchangeResult replay = exchange.exchange(
                player, "gold-merit-to-starlight", 2, "exchange:test-001").toCompletableFuture().join();
        CurrencyExchangeResult liquidated = exchange.exchange(
                player, "starlight-to-gold", 3, "exchange:test-002").toCompletableFuture().join();
        CurrencyExchangeResult limited = exchange.exchange(
                player, "starlight-to-gold", 1, "exchange:test-003").toCompletableFuture().join();

        assertEquals(CurrencyExchangeResult.Status.SUCCESS, first.status());
        assertEquals(new BigDecimal("4600.00"), first.balance().gold());
        assertEquals(6, first.balance().merit());
        assertEquals(12, first.balance().starlight());
        assertEquals(CurrencyExchangeResult.Status.DUPLICATE, replay.status());
        assertEquals(CurrencyExchangeResult.Status.SUCCESS, liquidated.status());
        assertEquals(new BigDecimal("5350.00"), liquidated.balance().gold());
        assertEquals(9, liquidated.balance().starlight());
        assertEquals(CurrencyExchangeResult.Status.LIMIT_EXCEEDED, limited.status());
        assertEquals(liquidated.balance(), exchange.balance(player).toCompletableFuture().join());
    }

    @Test
    void concurrentWealthExchangeCannotExceedLimitsOrReplayOneKey() {
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        JdbcCurrencyExchangeService exchange = new JdbcCurrencyExchangeService(
                dataSource, executor, BigDecimal.ZERO, ZoneId.of("Asia/Shanghai"), List.of(
                quote("gold-to-starlight", "100.00", 0, 0, "0.00", 0, 1, 5, 20)
        ));

        UUID limitedPlayer = UUID.randomUUID();
        gold.credit(limitedPlayer, new BigDecimal("1000.00"), "test:concurrent-exchange-seed", "test")
                .toCompletableFuture().join();
        List<CompletableFuture<CurrencyExchangeResult>> limited = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            limited.add(exchange.exchange(limitedPlayer, "gold-to-starlight", 1,
                    "exchange:concurrent:" + index).toCompletableFuture());
        }
        CompletableFuture.allOf(limited.toArray(CompletableFuture[]::new)).join();

        assertEquals(5, limited.stream().filter(result ->
                result.join().status() == CurrencyExchangeResult.Status.SUCCESS).count());
        assertEquals(15, limited.stream().filter(result ->
                result.join().status() == CurrencyExchangeResult.Status.LIMIT_EXCEEDED).count());
        var limitedBalance = exchange.balance(limitedPlayer).toCompletableFuture().join();
        assertEquals(new BigDecimal("500.00"), limitedBalance.gold());
        assertEquals(0, limitedBalance.merit());
        assertEquals(5, limitedBalance.starlight());

        UUID replayPlayer = UUID.randomUUID();
        gold.credit(replayPlayer, new BigDecimal("1000.00"), "test:replay-exchange-seed", "test")
                .toCompletableFuture().join();
        List<CompletableFuture<CurrencyExchangeResult>> replayed = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            replayed.add(exchange.exchange(replayPlayer, "gold-to-starlight", 1,
                    "exchange:same-key").toCompletableFuture());
        }
        CompletableFuture.allOf(replayed.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, replayed.stream().filter(result ->
                result.join().status() == CurrencyExchangeResult.Status.SUCCESS).count());
        assertEquals(19, replayed.stream().filter(result ->
                result.join().status() == CurrencyExchangeResult.Status.DUPLICATE).count());
        var replayBalance = exchange.balance(replayPlayer).toCompletableFuture().join();
        assertEquals(new BigDecimal("900.00"), replayBalance.gold());
        assertEquals(0, replayBalance.merit());
        assertEquals(1, replayBalance.starlight());
    }

    @Test
    void wealthExchangeBalanceLimitDoesNotSpendInputsLimitsOrIdempotencyKey() {
        UUID player = UUID.randomUUID();
        JdbcEconomyService gold = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        JdbcCommerceService commerce = new JdbcCommerceService(dataSource, executor);
        JdbcCurrencyExchangeService exchange = new JdbcCurrencyExchangeService(
                dataSource, executor, BigDecimal.ZERO, ZoneId.of("Asia/Shanghai"), List.of(
                quote("gold-to-starlight", "100.00", 0, 0, "0.00", 0, 1, 5, 20)
        ));
        gold.credit(player, new BigDecimal("1000.00"), "test:exchange-cap-gold", "test")
                .toCompletableFuture().join();
        commerce.adjustCredits(player, 1_000_000_000L, "test-exchange-cap-starlight", "test")
                .toCompletableFuture().join();

        CurrencyExchangeResult rejected = exchange.exchange(
                player, "gold-to-starlight", 1, "exchange:cap-retry").toCompletableFuture().join();
        commerce.adjustCredits(player, -1L, "test-exchange-cap-spend", "test")
                .toCompletableFuture().join();
        CurrencyExchangeResult retried = exchange.exchange(
                player, "gold-to-starlight", 1, "exchange:cap-retry").toCompletableFuture().join();

        assertEquals(CurrencyExchangeResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(new BigDecimal("1000.00"), rejected.balance().gold());
        assertEquals(5L, rejected.dailyRemaining());
        assertEquals(CurrencyExchangeResult.Status.SUCCESS, retried.status());
        assertEquals(new BigDecimal("900.00"), retried.balance().gold());
        assertEquals(1_000_000_000L, retried.balance().starlight());
        assertEquals(4L, retried.dailyRemaining());
    }

    @Test
    void saturatedJdbcExecutorReturnsAnExceptionalFutureWithoutCallerRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor bounded = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            CompletableFuture<Integer> running = AsyncJdbc.supply(bounded, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = AsyncJdbc.supply(bounded, () -> 2);
            CompletableFuture<Integer> rejected = AsyncJdbc.supply(bounded, () -> 3);

            assertTrue(rejected.isCompletedExceptionally());
            assertThrows(CompletionException.class, rejected::join);
            release.countDown();
            assertEquals(1, running.join());
            assertEquals(2, queued.join());
        } finally {
            release.countDown();
            bounded.shutdownNow();
        }
    }

    private static CurrencyExchangeQuote quote(
            String id, String goldCost, long meritCost, long starlightCost,
            String goldReward, long meritReward, long starlightReward, long daily, long weekly
    ) {
        return new CurrencyExchangeQuote(id, new BigDecimal(goldCost), meritCost, starlightCost,
                new BigDecimal(goldReward), meritReward, starlightReward,
                daily, weekly, daily, weekly);
    }

    @Test
    void marketPurchaseMovesMoneyAndCreatesOneDurableDelivery() {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, new BigDecimal("100.00"));
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, new BigDecimal("100.00"));
        UUID listingId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();

        assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(
                listingId, seller, "base64-payload", new BigDecimal("20.00"), new BigDecimal("0.10"), 12)
                .toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.activate(listingId, seller).toCompletableFuture().join().status());
        MarketOperationResult purchase = market.purchase(listingId, buyer, "market-order-1")
                .toCompletableFuture().join();
        MarketOperationResult replay = market.purchase(listingId, buyer, "market-order-1")
                .toCompletableFuture().join();

        assertEquals(MarketOperationResult.Status.SUCCESS, purchase.status());
        assertEquals(new BigDecimal("2.00"), purchase.fee());
        assertEquals(MarketOperationResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("78.00"), economy.balance(buyer).toCompletableFuture().join());
        assertEquals(new BigDecimal("120.00"), economy.balance(seller).toCompletableFuture().join());
        var deliveries = market.pendingDeliveries(buyer, 10).toCompletableFuture().join();
        assertEquals(1, deliveries.size());
        assertEquals("base64-payload", deliveries.getFirst().itemPayload());
        assertTrue(market.markDelivered(deliveries.getFirst().id(), buyer).toCompletableFuture().join());
        assertTrue(market.pendingDeliveries(buyer, 10).toCompletableFuture().join().isEmpty());
    }

    @Test
    void escrowSaleAndCollectionAdvanceTheLifecycleAndGateOperations() throws Exception {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, new BigDecimal("1000.00"));
        JdbcEquipmentOperationService operations =
                new JdbcEquipmentOperationService(dataSource, executor, new BigDecimal("1000.00"));
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID itemIdentity = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();

        // Seller escrows unregistered gear (adopted LISTED), but a paid refine
        // cannot mutate it while it sits in market escrow (C-009).
        assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(listingId, seller, "signed-gear",
                new BigDecimal("100.00"), new BigDecimal("0.10"), 12, Optional.of(itemIdentity))
                .toCompletableFuture().join().status());
        assertEquals("LISTED", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));
        EquipmentOperationRequest refine = new EquipmentOperationRequest(
                UUID.randomUUID(), buyer, EquipmentOperationKind.REFINEMENT, Optional.of(itemIdentity),
                "b".repeat(64), "refine", new BigDecimal("250.00"), "refine:" + UUID.randomUUID(),
                List.of("result-item"), List.of("original-item"));
        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED,
                operations.settle(refine).toCompletableFuture().join().status());
        assertEquals("LISTED", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));

        // Sale settles: LISTED -> SOLD under the buyer, who then collects BOUND.
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.activate(listingId, seller).toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.purchase(listingId, buyer, "lifecycle-order").toCompletableFuture().join().status());
        assertEquals("SOLD", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));
        assertEquals(buyer.toString(), stringScalar("SELECT owner_uuid FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));
        // The earlier refusal already parked "original-item" in the buyer's
        // mailbox, so pick the purchased gear by payload, not by position.
        var delivery = market.pendingDeliveries(buyer, 10).toCompletableFuture().join().stream()
                .filter(value -> value.itemPayload().equals("signed-gear")).findFirst().orElseThrow();
        assertTrue(market.markDelivered(delivery.id(), buyer).toCompletableFuture().join());
        // Escrowed gear was unbound in PDC, so the collected identity is
        // tradeable under the buyer, not combat-bound.
        assertEquals("UNBOUND", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));
        // The buyer can resell immediately: re-escrow, activate, pull back, still UNBOUND.
        UUID resaleId = UUID.randomUUID();
        assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(resaleId, buyer, "signed-gear",
                new BigDecimal("120.00"), new BigDecimal("0.10"), 12, Optional.of(itemIdentity))
                .toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.activate(resaleId, buyer).toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.cancel(resaleId, buyer).toCompletableFuture().join().status());
        assertEquals("UNBOUND", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));

        // Now that the buyer owns the gear outright, a paid refine commits (UNBOUND -> BOUND).
        EquipmentOperationRequest owned = new EquipmentOperationRequest(
                UUID.randomUUID(), buyer, EquipmentOperationKind.REFINEMENT, Optional.of(itemIdentity),
                "b".repeat(64), "refine", new BigDecimal("250.00"), "refine:" + UUID.randomUUID(),
                List.of("result-item"), List.of("original-item"));
        assertEquals(EquipmentOperationResult.Status.COMMITTED,
                operations.settle(owned).toCompletableFuture().join().status());
        // 1000 start - 110 purchase (100 + 10 fee) - 250 refinement.
        assertEquals(new BigDecimal("640.00"),
                new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO).balance(buyer)
                        .toCompletableFuture().join());
    }

    @Test
    void cancelAndExpireReturnTheEscrowedIdentityToItsSeller() throws Exception {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        UUID seller = UUID.randomUUID();
        UUID itemIdentity = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(listingId, seller, "signed-gear",
                new BigDecimal("50.00"), new BigDecimal("0.10"), 12, Optional.of(itemIdentity))
                .toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.activate(listingId, seller).toCompletableFuture().join().status());

        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.cancel(listingId, seller).toCompletableFuture().join().status());
        assertEquals("UNBOUND", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));
        // A returned identity is tradeable again, so it can be re-escrowed.
        assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(UUID.randomUUID(), seller, "relisted",
                new BigDecimal("60.00"), new BigDecimal("0.10"), 12, Optional.of(itemIdentity))
                .toCompletableFuture().join().status());
    }

    @Test
    void quarantinedIdentityFreezesAndCannotBeMutatedByAnOperation() throws Exception {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        JdbcEquipmentOperationService operations =
                new JdbcEquipmentOperationService(dataSource, executor, new BigDecimal("1000.00"));
        UUID seller = UUID.randomUUID();
        UUID itemIdentity = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(listingId, seller, "signed-gear",
                new BigDecimal("50.00"), new BigDecimal("0.10"), 12, Optional.of(itemIdentity))
                .toCompletableFuture().join().status());

        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.quarantinePrepared(listingId, seller).toCompletableFuture().join().status());
        assertEquals("QUARANTINED", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));
        // C-013: quarantined gear is frozen for audit; no paid operation may mutate it.
        EquipmentOperationRequest salvage = new EquipmentOperationRequest(
                UUID.randomUUID(), seller, EquipmentOperationKind.SALVAGE, Optional.of(itemIdentity),
                "c".repeat(64), "salvage", BigDecimal.ZERO.setScale(2), "salvage:" + UUID.randomUUID(),
                List.of("essence"), List.of("original-item"));
        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED,
                operations.settle(salvage).toCompletableFuture().join().status());
        assertEquals("QUARANTINED", stringScalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + itemIdentity + "'"));
        // Frozen gear cannot be re-escrowed either.
        assertEquals(MarketOperationResult.Status.ITEM_NOT_TRADEABLE, market.prepare(UUID.randomUUID(), seller,
                "smuggled", new BigDecimal("50.00"), new BigDecimal("0.10"), 12, Optional.of(itemIdentity))
                .toCompletableFuture().join().status());
    }

    @Test
    void marketBalanceLimitRejectionIsAtomicAndTheSameOrderCanBeRetried() throws Exception {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID listingId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        economy.credit(seller, new BigDecimal("1000000000000.00"),
                        "seed:market-limit-seller", "test")
                .toCompletableFuture().join();
        economy.credit(buyer, new BigDecimal("100.00"), "seed:market-limit-buyer", "test")
                .toCompletableFuture().join();
        assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(listingId, seller, "payload",
                new BigDecimal("20.00"), new BigDecimal("0.10"), 12).toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.activate(listingId, seller).toCompletableFuture().join().status());

        MarketOperationResult rejected = market.purchase(listingId, buyer, "market:balance-limit")
                .toCompletableFuture().join();

        assertEquals(MarketOperationResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(new BigDecimal("100.00"), economy.balance(buyer).toCompletableFuture().join());
        assertEquals(new BigDecimal("1000000000000.00"), economy.balance(seller).toCompletableFuture().join());
        assertEquals(cn.originalrpg.api.model.MarketListing.State.ACTIVE,
                market.listing(listingId).toCompletableFuture().join().orElseThrow().state());
        assertTrue(market.pendingDeliveries(buyer, 10).toCompletableFuture().join().isEmpty());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key = 'market:balance-limit'"));

        economy.burn(seller, new BigDecimal("20.00"), "burn:market-limit-space", "test")
                .toCompletableFuture().join();
        MarketOperationResult retried = market.purchase(listingId, buyer, "market:balance-limit")
                .toCompletableFuture().join();

        assertEquals(MarketOperationResult.Status.SUCCESS, retried.status());
        assertEquals(new BigDecimal("78.00"), economy.balance(buyer).toCompletableFuture().join());
        assertEquals(new BigDecimal("1000000000000.00"), economy.balance(seller).toCompletableFuture().join());
        assertEquals(cn.originalrpg.api.model.MarketListing.State.SOLD,
                market.listing(listingId).toCompletableFuture().join().orElseThrow().state());
        assertEquals(1, market.pendingDeliveries(buyer, 10).toCompletableFuture().join().size());
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key = 'market:balance-limit'"));
    }

    @Test
    void concurrentMarketSalesCannotRaceOneSellerPastTheGoldBalanceLimit() throws Exception {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        JdbcEconomyService economy = new JdbcEconomyService(dataSource, executor, BigDecimal.ZERO);
        UUID seller = UUID.randomUUID();
        economy.credit(seller, new BigDecimal("999999999980.00"),
                        "seed:market-race-seller", "test")
                .toCompletableFuture().join();
        List<UUID> listings = new ArrayList<>();
        List<UUID> buyers = new ArrayList<>();
        List<CompletableFuture<MarketOperationResult>> purchases = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            UUID listingId = UUID.randomUUID();
            UUID buyer = UUID.randomUUID();
            listings.add(listingId);
            buyers.add(buyer);
            economy.credit(buyer, new BigDecimal("100.00"), "seed:market-race-buyer-" + index, "test")
                    .toCompletableFuture().join();
            assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(
                    listingId, seller, "payload-" + index, new BigDecimal("20.00"),
                    new BigDecimal("0.05"), 12).toCompletableFuture().join().status());
            assertEquals(MarketOperationResult.Status.SUCCESS,
                    market.activate(listingId, seller).toCompletableFuture().join().status());
            purchases.add(market.purchase(listingId, buyer, "market:balance-race-" + index)
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(purchases.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == MarketOperationResult.Status.SUCCESS).count());
        assertEquals(7, purchases.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == MarketOperationResult.Status.BALANCE_LIMIT).count());
        assertEquals(new BigDecimal("1000000000000.00"),
                economy.balance(seller).toCompletableFuture().join());
        assertEquals(1, listings.stream().map(id -> market.listing(id).toCompletableFuture().join().orElseThrow())
                .filter(listing -> listing.state() == cn.originalrpg.api.model.MarketListing.State.SOLD).count());
        assertEquals(7, listings.stream().map(id -> market.listing(id).toCompletableFuture().join().orElseThrow())
                .filter(listing -> listing.state() == cn.originalrpg.api.model.MarketListing.State.ACTIVE).count());
        assertEquals(1, buyers.stream().mapToInt(buyer ->
                market.pendingDeliveries(buyer, 10).toCompletableFuture().join().size()).sum());
        BigDecimal buyerBalances = buyers.stream()
                .map(buyer -> economy.balance(buyer).toCompletableFuture().join())
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        assertEquals(new BigDecimal("779.00"), buyerBalances);
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_ledger "
                + "WHERE idempotency_key LIKE 'market:balance-race-%'"));
    }

    @Test
    void concurrentMarketPreparesRespectDatabaseLimitAndAmbiguousEscrowCanBeQuarantined() {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        UUID seller = UUID.randomUUID();
        List<CompletableFuture<MarketOperationResult>> prepares = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            prepares.add(market.prepare(UUID.randomUUID(), seller, "payload-" + index,
                    new BigDecimal("20.00"), new BigDecimal("0.05"), 5).toCompletableFuture());
        }
        CompletableFuture.allOf(prepares.toArray(CompletableFuture[]::new)).join();

        assertEquals(5, prepares.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == MarketOperationResult.Status.SUCCESS).count());
        assertEquals(15, prepares.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == MarketOperationResult.Status.LISTING_LIMIT).count());

        UUID quarantinedId = prepares.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == MarketOperationResult.Status.SUCCESS)
                .map(result -> result.listing().id()).findFirst().orElseThrow();
        var quarantined = market.quarantinePrepared(quarantinedId, seller).toCompletableFuture().join();
        assertEquals(MarketOperationResult.Status.SUCCESS, quarantined.status());
        assertEquals(cn.originalrpg.api.model.MarketListing.State.QUARANTINED,
                quarantined.listing().state());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.prepare(UUID.randomUUID(), seller, "replacement", new BigDecimal("20.00"),
                        new BigDecimal("0.05"), 5).toCompletableFuture().join().status());
    }

    @Test
    void forgedItemIdentityCanHaveOnlyOneOpenEscrowButCanBeRelistedAfterReturn() {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        UUID seller = UUID.randomUUID();
        UUID itemIdentity = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID duplicateId = UUID.randomUUID();

        MarketOperationResult first = market.prepare(firstId, seller, "signed-item", new BigDecimal("50.00"),
                new BigDecimal("0.10"), 12, Optional.of(itemIdentity)).toCompletableFuture().join();
        MarketOperationResult duplicate = market.prepare(duplicateId, seller, "cloned-signed-item",
                new BigDecimal("50.00"), new BigDecimal("0.10"), 12, Optional.of(itemIdentity))
                .toCompletableFuture().join();

        assertEquals(MarketOperationResult.Status.SUCCESS, first.status());
        assertEquals(MarketOperationResult.Status.DUPLICATE_ITEM, duplicate.status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.activate(firstId, seller).toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.cancel(firstId, seller).toCompletableFuture().join().status());
        assertEquals(MarketOperationResult.Status.SUCCESS,
                market.prepare(UUID.randomUUID(), seller, "returned-signed-item", new BigDecimal("60.00"),
                        new BigDecimal("0.10"), 12, Optional.of(itemIdentity)).toCompletableFuture().join().status());
    }

    @Test
    void marketPagesStayBoundedAndConcurrentExpiryReturnsExactlyOnce() {
        JdbcMarketService market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        UUID seller = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID id : List.of(first, second)) {
            assertEquals(MarketOperationResult.Status.SUCCESS, market.prepare(id, seller,
                    "payload-" + id, new BigDecimal("25.00"), new BigDecimal("0.05"), 12)
                    .toCompletableFuture().join().status());
            assertEquals(MarketOperationResult.Status.SUCCESS,
                    market.activate(id, seller).toCompletableFuture().join().status());
        }

        assertEquals(1, market.activeListings(0, 1).toCompletableFuture().join().size());
        assertEquals(1, market.activeListings(1, 1).toCompletableFuture().join().size());
        assertEquals(2, market.activeListings(seller, 12).toCompletableFuture().join().size());

        Instant futureCutoff = Instant.now().plusSeconds(5);
        CompletableFuture<Integer> scanA = market.expireListings(futureCutoff, 10).toCompletableFuture();
        CompletableFuture<Integer> scanB = market.expireListings(futureCutoff, 10).toCompletableFuture();
        CompletableFuture.allOf(scanA, scanB).join();

        assertEquals(2, scanA.join() + scanB.join());
        assertTrue(market.activeListings(10).toCompletableFuture().join().isEmpty());
        assertEquals(2, market.pendingDeliveries(seller, 10).toCompletableFuture().join().size());
        assertEquals(cn.originalrpg.api.model.MarketListing.State.EXPIRED,
                market.listing(first).toCompletableFuture().join().orElseThrow().state());
    }

    @Test
    @SuppressWarnings("deprecation")
    void skillLearningSpendsPersistentPointBudgetAndStoresVariant() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        var profile = profiles.get(playerId).toCompletableFuture().join();
        JdbcSkillService skills = new JdbcSkillService(dataSource, executor);

        var learned = skills.learn(playerId, "heal", 1).toCompletableFuture().join();
        var duplicate = skills.learn(playerId, "heal", 1).toCompletableFuture().join();
        // The legacy caller balance is ignored; the transaction reads the
        // profile's real budget and cannot be tricked with an inflated value.
        var overBudget = skills.learn(playerId, "fireball", 1, 999).toCompletableFuture().join();

        assertEquals(cn.originalrpg.api.model.SkillLearnResult.Status.SUCCESS, learned.status());
        assertEquals(cn.originalrpg.api.model.SkillLearnResult.Status.ALREADY_LEARNED, duplicate.status());
        assertEquals(cn.originalrpg.api.model.SkillLearnResult.Status.INSUFFICIENT_POINTS, overBudget.status());
        assertTrue(skills.setVariant(playerId, "heal", "INVERTED").toCompletableFuture().join());
        assertEquals("INVERTED", skills.skill(playerId, "heal").toCompletableFuture().join()
                .orElseThrow().variant().orElseThrow());
        assertTrue(skills.clearVariant(playerId, "heal").toCompletableFuture().join());
        assertTrue(skills.skill(playerId, "heal").toCompletableFuture().join()
                .orElseThrow().variant().isEmpty());
        assertFalse(skills.setVariant(playerId, "heal", "UNIMPLEMENTED").toCompletableFuture().join());
        assertFalse(skills.setVariant(playerId, "fireball", "INVERTED").toCompletableFuture().join());
        assertTrue(skills.cachedSkills(playerId).isPresent());
        skills.evict(playerId);
        assertTrue(skills.cachedSkills(playerId).isEmpty());
    }

    @Test
    void skillVariantPersistenceAcceptsEveryRuntimePairAndRejectsCrossSkillPairs() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        profiles.get(playerId).toCompletableFuture().join();
        JdbcSkillService skills = new JdbcSkillService(dataSource, executor);

        for (var entry : cn.originalrpg.api.model.SkillVariantCatalog.implemented().entrySet()) {
            String skill = entry.getKey();
            String variant = entry.getValue().iterator().next();
            assertEquals(cn.originalrpg.api.model.SkillLearnResult.Status.SUCCESS,
                    skills.learn(playerId, skill, 0).toCompletableFuture().join().status(), skill);
            assertTrue(skills.setVariant(playerId, skill, variant).toCompletableFuture().join(), skill);
            assertEquals(variant, skills.skill(playerId, skill).toCompletableFuture().join()
                    .orElseThrow().variant().orElseThrow(), skill);
        }
        assertFalse(skills.setVariant(playerId, "war-cry", "ARC_BURST").toCompletableFuture().join());
        assertFalse(skills.clearVariant(playerId, "unimplemented-skill").toCompletableFuture().join());
    }

    @Test
    void concurrentSkillLearningCannotOverspendOnePersistentPoint() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        profiles.get(playerId).toCompletableFuture().join();
        JdbcSkillService skills = new JdbcSkillService(dataSource, executor);

        CompletableFuture<cn.originalrpg.api.model.SkillLearnResult> first =
                skills.learn(playerId, "heal", 1).toCompletableFuture();
        CompletableFuture<cn.originalrpg.api.model.SkillLearnResult> second =
                skills.learn(playerId, "fireball", 1).toCompletableFuture();
        CompletableFuture.allOf(first, second).join();

        long successes = List.of(first.join(), second.join()).stream()
                .filter(result -> result.status() == cn.originalrpg.api.model.SkillLearnResult.Status.SUCCESS)
                .count();
        assertEquals(1L, successes);
        assertEquals(1, skills.skills(playerId).toCompletableFuture().join().size());
    }

    @Test
    void skillUpgradeUsesOneAtomicPointBudgetAndRejectsStaleGuiReplays() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        profiles.get(playerId).toCompletableFuture().join();
        profiles.redeemSkillBook(
                playerId, UUID.randomUUID(), "test-insight", 10, null, 0)
                .toCompletableFuture().join();
        JdbcSkillService skills = new JdbcSkillService(dataSource, executor);

        assertEquals(cn.originalrpg.api.model.SkillLearnResult.Status.SUCCESS,
                skills.learn(playerId, "heal", 1).toCompletableFuture().join().status());
        // Warm the complete cache before mutation so the upgrade path must
        // replace its immutable entry rather than hiding behind a fresh read.
        assertEquals(1, skills.skills(playerId).toCompletableFuture().join().get("heal").level());

        var first = skills.upgrade(playerId, "heal", 1, 2, 1).toCompletableFuture().join();
        var replay = skills.upgrade(playerId, "heal", 1, 2, 1).toCompletableFuture().join();
        var stageCap = skills.upgrade(playerId, "heal", 2, 2, 2).toCompletableFuture().join();
        var nextStage = skills.upgrade(playerId, "heal", 2, 3, 2).toCompletableFuture().join();

        assertEquals(cn.originalrpg.api.model.SkillUpgradeResult.Status.SUCCESS, first.status());
        assertEquals(2, first.progress().level());
        assertEquals(2, first.progress().pointCost());
        assertEquals(cn.originalrpg.api.model.SkillUpgradeResult.Status.STALE_LEVEL, replay.status());
        assertEquals(cn.originalrpg.api.model.SkillUpgradeResult.Status.MAX_LEVEL, stageCap.status());
        assertEquals(cn.originalrpg.api.model.SkillUpgradeResult.Status.SUCCESS, nextStage.status());
        assertEquals(3, nextStage.progress().level());
        assertEquals(4, nextStage.progress().pointCost());
        assertEquals(nextStage.progress(), skills.cachedSkills(playerId).orElseThrow().get("heal"));
    }

    @Test
    void concurrentSkillUpgradeCannotBuyTwoLevelsFromOneRenderedClick() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        profiles.get(playerId).toCompletableFuture().join();
        JdbcSkillService skills = new JdbcSkillService(dataSource, executor);
        assertEquals(cn.originalrpg.api.model.SkillLearnResult.Status.SUCCESS,
                skills.learn(playerId, "heal", 0).toCompletableFuture().join().status());

        CompletableFuture<cn.originalrpg.api.model.SkillUpgradeResult> first =
                skills.upgrade(playerId, "heal", 1, 5, 1).toCompletableFuture();
        CompletableFuture<cn.originalrpg.api.model.SkillUpgradeResult> second =
                skills.upgrade(playerId, "heal", 1, 5, 1).toCompletableFuture();
        CompletableFuture.allOf(first, second).join();

        assertEquals(1L, List.of(first.join(), second.join()).stream()
                .filter(result -> result.status()
                        == cn.originalrpg.api.model.SkillUpgradeResult.Status.SUCCESS)
                .count());
        assertEquals(1L, List.of(first.join(), second.join()).stream()
                .filter(result -> result.status()
                        == cn.originalrpg.api.model.SkillUpgradeResult.Status.STALE_LEVEL)
                .count());
        SkillProgress upgraded = skills.skill(playerId, "heal").toCompletableFuture().join().orElseThrow();
        assertEquals(2, upgraded.level());
        assertEquals(1, upgraded.pointCost());
    }

    @Test
    void concurrentLearningAndUpgradeShareTheSamePersistentPointLock() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        profiles.get(playerId).toCompletableFuture().join();
        JdbcSkillService skills = new JdbcSkillService(dataSource, executor);
        skills.learn(playerId, "heal", 0).toCompletableFuture().join();

        CompletableFuture<cn.originalrpg.api.model.SkillUpgradeResult> upgrade =
                skills.upgrade(playerId, "heal", 1, 5, 1).toCompletableFuture();
        CompletableFuture<cn.originalrpg.api.model.SkillLearnResult> learn =
                skills.learn(playerId, "fireball", 1).toCompletableFuture();
        CompletableFuture.allOf(upgrade, learn).join();

        long successes = (upgrade.join().status()
                == cn.originalrpg.api.model.SkillUpgradeResult.Status.SUCCESS ? 1 : 0)
                + (learn.join().status()
                == cn.originalrpg.api.model.SkillLearnResult.Status.SUCCESS ? 1 : 0);
        assertEquals(1L, successes);
        assertEquals(1, skills.skills(playerId).toCompletableFuture().join().values().stream()
                .mapToInt(cn.originalrpg.api.model.SkillProgress::pointCost).sum());
    }

    @Test
    void concurrentUpgradeAndVariantMutationKeepBothFieldsInTheCompleteCache() {
        UUID playerId = UUID.randomUUID();
        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        profiles.get(playerId).toCompletableFuture().join();
        JdbcSkillService skills = new JdbcSkillService(dataSource, executor);
        skills.learn(playerId, "heal", 0).toCompletableFuture().join();
        skills.skills(playerId).toCompletableFuture().join();

        CompletableFuture<cn.originalrpg.api.model.SkillUpgradeResult> upgrade =
                skills.upgrade(playerId, "heal", 1, 5, 1).toCompletableFuture();
        CompletableFuture<Boolean> variant =
                skills.setVariant(playerId, "heal", "INVERTED").toCompletableFuture();
        CompletableFuture.allOf(upgrade, variant).join();

        assertEquals(cn.originalrpg.api.model.SkillUpgradeResult.Status.SUCCESS, upgrade.join().status());
        assertTrue(variant.join());
        SkillProgress cached = skills.cachedSkills(playerId).orElseThrow().get("heal");
        assertEquals(2, cached.level());
        assertEquals(1, cached.pointCost());
        assertEquals("INVERTED", cached.variant().orElseThrow());
        skills.evict(playerId);
        assertEquals(cached, skills.skill(playerId, "heal").toCompletableFuture().join().orElseThrow());
    }

    private void seedAscensionFacts(
            UUID player, int skills, int dungeonClears, long merit, String... dungeonIds
    ) throws Exception {
        if (dungeonIds.length == 0) throw new IllegalArgumentException("At least one dungeon ID is required");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (int index = 0; index < skills; index++) {
                statement.execute("INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost) VALUES ('"
                        + player + "', 'test_skill_" + index + "', 1, 0) ON CONFLICT DO NOTHING");
            }
            for (int index = 0; index < dungeonClears; index++) {
                String dungeonId = dungeonIds[index % dungeonIds.length];
                statement.execute("INSERT INTO rpg_dungeon_reward_receipts (run_id, player_uuid, dungeon_id, "
                        + "experience_awarded, queued_deliveries, ticket_dropped, ticket_failures) VALUES ('"
                        + UUID.randomUUID() + "', '" + player + "', '" + dungeonId + "', 1, 0, FALSE, 0)");
            }
            statement.execute("DELETE FROM rpg_progression_wallets WHERE player_uuid = '" + player + "'");
            statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES ('"
                    + player + "', " + merit + ")");
            connection.commit();
        }
    }

    private void seedBloodpactFacts(UUID player, boolean redeemedBook) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO rpg_profiles (
                        player_uuid, archetype, ascension, level, experience, energy, unspent_attribute_points,
                        skill_points, defense, strength, agility, intelligence, stamina
                    ) VALUES ('%s', 'ASSASSIN', 2, 20, 0, 100, 0, 0, 0, 0, 32, 0, 18)
                    """.formatted(player));
            statement.execute("INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost) VALUES ('"
                    + player + "', 'vanished-horizon', 1, 0)");
            statement.execute("""
                    INSERT INTO rpg_dungeon_reward_receipts (
                        run_id, player_uuid, dungeon_id, experience_awarded, queued_deliveries,
                        ticket_dropped, ticket_failures
                    ) VALUES ('%s', '%s', 'storm-vault-hidden', 1, 0, FALSE, 0)
                    """.formatted(UUID.randomUUID(), player));
            if (redeemedBook) {
                statement.execute("""
                        INSERT INTO rpg_skill_book_uses (
                            instance_id, player_uuid, book_id, limit_group, points_granted
                        ) VALUES ('%s', '%s', 'silent-thunder', NULL, 3)
                        """.formatted(UUID.randomUUID(), player));
            }
            connection.commit();
        }
    }

    private void setMerit(UUID player, long merit) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM rpg_progression_wallets WHERE player_uuid = '" + player + "'");
            statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES ('"
                    + player + "', " + merit + ")");
            connection.commit();
        }
    }

    /** 累计恰好升到本地 target 所需的经验（同段起点 fromLocal 起算）。 */
    private static long experienceToLocal(int ascension, int fromLocal, int targetLocal) {
        long needed = 0L;
        for (int local = fromLocal; local < targetLocal; local++) {
            needed += ProgressionRules.experienceToNextLocalLevel(ascension, local);
        }
        return needed;
    }

    /** 从本地 1 升到 targetLocal 所需的经验。 */
    private static long experienceToLocal(int ascension, int targetLocal) {
        return experienceToLocal(ascension, 1, targetLocal);
    }

    /** LIKE 前缀匹配（source 的实际写法是 "local-milestone:<转>:<里程碑>|source:<业务键>"）。 */
    private long milestoneRows(String sourceLike) throws Exception {
        return scalar("SELECT COUNT(*) FROM rpg_skill_point_grants WHERE source LIKE '" + sourceLike + "'");
    }

    /** 测试用事务桥：允许 lambda 抛受检 SQLException，行为与生产 inTransaction 一致。 */
    private interface TransactionOperation<T> {
        T apply(Connection connection) throws Exception;
    }

    private <T> T inTransaction(TransactionOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T value = operation.apply(connection);
                connection.commit();
                return value;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception exception) {
            throw new IllegalStateException("test transaction failed", exception);
        }
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            long value = result.getLong(1);
            connection.rollback();
            return value;
        }
    }

    private String dungeonReservationState(UUID reservationId) throws Exception {
        return stringScalar("SELECT state FROM rpg_dungeon_energy_reservations WHERE id = '"
                + reservationId + "'");
    }

    private String dungeonTicketState(UUID reservationId) throws Exception {
        return stringScalar("SELECT state FROM rpg_dungeon_ticket_escrows WHERE reservation_id = '"
                + reservationId + "'");
    }

    private long dungeonTicketDeliveryCount(UUID reservationId) throws Exception {
        return scalar("""
                SELECT COUNT(*) FROM rpg_dungeon_ticket_escrows t
                JOIN rpg_market_deliveries d ON d.id = t.delivery_id
                WHERE t.reservation_id = '%s'
                """.formatted(reservationId));
    }

    private String stringScalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            String value = result.getString(1);
            connection.rollback();
            return value;
        }
    }

    private JdbcNodeLease acquiredLease(String nodeId) {
        JdbcNodeLease lease = new JdbcNodeLease(
                dataSource, executor, nodeId, Duration.ofSeconds(60));
        assertTrue(lease.acquire());
        return lease;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneId.of("UTC").equals(zone)) throw new UnsupportedOperationException("test clock is UTC");
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
