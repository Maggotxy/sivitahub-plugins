package cn.originalrpg.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import cn.originalrpg.api.model.PersistenceSnapshot;
import org.bukkit.configuration.file.FileConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;

public final class Database implements AutoCloseable {
    // Two signed 32-bit advisory-lock keys keep the lock scoped to SivitaHub's
    // RPG schema without depending on PostgreSQL's installation-specific OIDs.
    private static final int MIGRATION_LOCK_NAMESPACE = 0x53495649; // "SIVI"
    private static final int MIGRATION_LOCK_RESOURCE = 0x52504731; // "RPG1"
    private final HikariDataSource dataSource;
    private final HikariDataSource nodeLeaseDataSource;
    private final boolean localH2;
    private final boolean postgres;
    private final long migrationLockTimeoutMs;

    private Database(
            HikariDataSource dataSource, HikariDataSource nodeLeaseDataSource,
            boolean localH2, boolean postgres, long migrationLockTimeoutMs
    ) {
        this.dataSource = dataSource;
        this.nodeLeaseDataSource = nodeLeaseDataSource;
        this.localH2 = localH2;
        this.postgres = postgres;
        this.migrationLockTimeoutMs = migrationLockTimeoutMs;
    }

    public static Database open(FileConfiguration config) {
        String jdbcUrl = environmentOrConfig("RPG_DATABASE_URL", config.getString("database.jdbc-url"));
        HikariConfig hikari = hikariConfig(config, jdbcUrl, "rpg-database",
                Math.clamp(config.getInt("database.maximum-pool-size", 6), 2, 32));
        HikariConfig leaseHikari = hikariConfig(config, jdbcUrl, "rpg-node-lease-database", 1);
        long leaseConnectionTimeout = Math.clamp(
                config.getLong("network.node-lease-connection-timeout-ms", 2_000L), 250L, 5_000L);
        leaseHikari.setConnectionTimeout(leaseConnectionTimeout);
        leaseHikari.setValidationTimeout(Math.min(leaseConnectionTimeout,
                Math.max(250L, config.getLong("database.validation-timeout-ms", 2_000L))));
        long migrationLockTimeoutMs = Math.clamp(
                config.getLong("database.migration-lock-timeout-ms", 30_000L), 1_000L, 300_000L);
        HikariDataSource gameplayDataSource = null;
        try {
            gameplayDataSource = new HikariDataSource(hikari);
            HikariDataSource leaseDataSource = new HikariDataSource(leaseHikari);
            return new Database(gameplayDataSource, leaseDataSource,
                    jdbcUrl.startsWith("jdbc:h2:"), jdbcUrl.startsWith("jdbc:postgresql:"),
                    migrationLockTimeoutMs);
        } catch (RuntimeException exception) {
            if (gameplayDataSource != null) gameplayDataSource.close();
            throw exception;
        }
    }

    static HikariConfig hikariConfig(
            FileConfiguration config, String jdbcUrl, String poolName, int maximumPoolSize
    ) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(poolName);
        hikari.setJdbcUrl(jdbcUrl);
        String configuredDriver = config.getString("database.driver-class-name", "");
        String driverClassName = configuredDriver == null || configuredDriver.isBlank()
                ? driverClassFor(jdbcUrl) : configuredDriver.trim();
        if (driverClassName != null) hikari.setDriverClassName(driverClassName);
        hikari.setUsername(environmentOrConfig("RPG_DATABASE_USER", config.getString("database.username", "")));
        hikari.setPassword(environmentOrConfig("RPG_DATABASE_PASSWORD", config.getString("database.password", "")));
        hikari.setMaximumPoolSize(maximumPoolSize);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(Math.max(1000L, config.getLong("database.connection-timeout-ms", 5000L)));
        hikari.setValidationTimeout(Math.max(250L, config.getLong("database.validation-timeout-ms", 2000L)));
        hikari.setIdleTimeout(Math.max(10_000L, config.getLong("database.idle-timeout-ms", 600_000L)));
        hikari.setMaxLifetime(Math.max(30_000L, config.getLong("database.max-lifetime-ms", 1_800_000L)));
        long leakDetection = config.getLong("database.leak-detection-threshold-ms", 15_000L);
        if (leakDetection > 0) hikari.setLeakDetectionThreshold(Math.max(2000L, leakDetection));
        hikari.setAutoCommit(false);
        hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        return hikari;
    }

    static String driverClassFor(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        if (jdbcUrl.startsWith("jdbc:h2:")) return "org.h2.Driver";
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
        return null;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** A physically separate single-connection pool reserved for node fencing. */
    public DataSource nodeLeaseDataSource() {
        return nodeLeaseDataSource;
    }

    /**
     * Local file-backed H2 obtains an operating-system file lock before this
     * object is returned, so a second live Paper process cannot share it.
     */
    public boolean localH2() {
        return localH2;
    }

    public PersistenceSnapshot metrics(ThreadPoolExecutor executor) {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null || executor == null) return PersistenceSnapshot.unavailable();
        return new PersistenceSnapshot(
                pool.getActiveConnections(), pool.getIdleConnections(), pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(), executor.getActiveCount(), executor.getQueue().size(),
                executor.getQueue().remainingCapacity());
    }

    public void migrate() {
        String[] migrations = {
                """
                CREATE TABLE IF NOT EXISTS rpg_profiles (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    archetype VARCHAR(24) NOT NULL,
                    ascension INTEGER NOT NULL,
                    level INTEGER NOT NULL,
                    experience BIGINT NOT NULL,
                    energy INTEGER NOT NULL,
                    unspent_attribute_points INTEGER NOT NULL,
                    skill_points INTEGER NOT NULL,
                    defense INTEGER NOT NULL,
                    strength INTEGER NOT NULL,
                    agility INTEGER NOT NULL,
                    intelligence INTEGER NOT NULL,
                    stamina INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_chapter_state (
                    singleton_id INTEGER PRIMARY KEY,
                    season_id VARCHAR(32) NOT NULL,
                    chapter_id VARCHAR(32) NOT NULL,
                    chapter_sequence INTEGER NOT NULL,
                    max_open_ascension INTEGER NOT NULL,
                    revision BIGINT NOT NULL,
                    changed_by VARCHAR(64) NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_chapter_history (
                    id VARCHAR(36) PRIMARY KEY,
                    season_id VARCHAR(32) NOT NULL,
                    chapter_id VARCHAR(32) NOT NULL,
                    chapter_sequence INTEGER NOT NULL,
                    max_open_ascension INTEGER NOT NULL,
                    revision BIGINT NOT NULL,
                    changed_by VARCHAR(64) NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    changed_at TIMESTAMP NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_accounts (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    balance DECIMAL(19, 2) NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    sender_uuid VARCHAR(36),
                    receiver_uuid VARCHAR(36),
                    amount DECIMAL(19, 2) NOT NULL,
                    fee DECIMAL(19, 2) NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    reversal_of VARCHAR(96),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                "ALTER TABLE rpg_ledger ADD COLUMN IF NOT EXISTS reversal_of VARCHAR(96)",
                """
                CREATE TABLE IF NOT EXISTS rpg_progression_wallets (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    merit BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_activity_daily (
                    player_uuid VARCHAR(36) NOT NULL,
                    activity VARCHAR(24) NOT NULL,
                    activity_date DATE NOT NULL,
                    gold_earned DECIMAL(19, 2) NOT NULL,
                    merit_earned BIGINT NOT NULL,
                    PRIMARY KEY (player_uuid, activity, activity_date)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_activity_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    activity VARCHAR(24) NOT NULL,
                    requested_gold DECIMAL(19, 2) NOT NULL,
                    granted_gold DECIMAL(19, 2) NOT NULL,
                    requested_merit BIGINT NOT NULL,
                    granted_merit BIGINT NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_daily_item_rewards (
                    player_uuid VARCHAR(36) NOT NULL,
                    reward_id VARCHAR(64) NOT NULL,
                    reward_date DATE NOT NULL,
                    used INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, reward_id, reward_date)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_daily_item_reward_receipts (
                    idempotency_key VARCHAR(96) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    reward_id VARCHAR(64) NOT NULL,
                    reward_date DATE NOT NULL,
                    requested INTEGER NOT NULL,
                    granted INTEGER NOT NULL,
                    used_after INTEGER NOT NULL,
                    daily_cap INTEGER NOT NULL,
                    request_hash VARCHAR(64) NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_commission_evidence_daily (
                    player_uuid VARCHAR(36) NOT NULL,
                    evidence_date DATE NOT NULL,
                    earned INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, evidence_date)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_commission_evidence_sources (
                    player_uuid VARCHAR(36) NOT NULL,
                    evidence_date DATE NOT NULL,
                    source VARCHAR(64) NOT NULL,
                    earned INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, evidence_date, source)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_commission_claims (
                    receipt_id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    evidence_date DATE NOT NULL,
                    commission_id VARCHAR(64) NOT NULL,
                    evidence_cost INTEGER NOT NULL,
                    state VARCHAR(12) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_activity_purchases (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    gold_cost DECIMAL(19, 2) NOT NULL,
                    merit_cost BIGINT NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    delivery_id VARCHAR(36) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_incident_compensations (
                    incident_key VARCHAR(96) PRIMARY KEY,
                    request_hash VARCHAR(64) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    activity_date DATE NOT NULL,
                    experience BIGINT NOT NULL,
                    total_gold DECIMAL(19, 2) NOT NULL,
                    total_merit BIGINT NOT NULL,
                    before_profile TEXT NOT NULL,
                    after_profile TEXT NOT NULL,
                    actor VARCHAR(64) NOT NULL,
                    reason VARCHAR(256) NOT NULL,
                    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_incident_compensation_lines (
                    incident_key VARCHAR(96) NOT NULL,
                    line_kind VARCHAR(16) NOT NULL,
                    line_id VARCHAR(128) NOT NULL,
                    activity VARCHAR(24),
                    gold DECIMAL(19, 2) NOT NULL DEFAULT 0,
                    merit BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (incident_key, line_kind, line_id),
                    FOREIGN KEY (incident_key) REFERENCES rpg_incident_compensations(incident_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_merit_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    amount BIGINT NOT NULL,
                    direction VARCHAR(8) NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_market_listings (
                    id VARCHAR(36) PRIMARY KEY,
                    seller_uuid VARCHAR(36) NOT NULL,
                    item_payload TEXT NOT NULL,
                    price DECIMAL(19, 2) NOT NULL,
                    fee_rate DECIMAL(8, 6) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    buyer_uuid VARCHAR(36),
                    item_identity VARCHAR(36),
                    open_item_identity VARCHAR(36),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP NULL
                )
                """,
                "ALTER TABLE rpg_market_listings ADD COLUMN IF NOT EXISTS item_identity VARCHAR(36)",
                "ALTER TABLE rpg_market_listings ADD COLUMN IF NOT EXISTS open_item_identity VARCHAR(36)",
                """
                CREATE TABLE IF NOT EXISTS rpg_market_deliveries (
                    id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    item_payload TEXT NOT NULL,
                    source VARCHAR(96) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    delivered_at TIMESTAMP NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_equipment_operations (
                    operation_id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    kind VARCHAR(24) NOT NULL,
                    item_id VARCHAR(36),
                    input_fingerprint VARCHAR(64) NOT NULL,
                    request_payload TEXT NOT NULL,
                    gold_cost DECIMAL(19, 2) NOT NULL,
                    debit_key VARCHAR(96) UNIQUE NOT NULL,
                    state VARCHAR(40) NOT NULL,
                    queued_deliveries INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    settled_at TIMESTAMP NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_skills (
                    player_uuid VARCHAR(36) NOT NULL,
                    skill_id VARCHAR(64) NOT NULL,
                    level INTEGER NOT NULL,
                    point_cost INTEGER NOT NULL DEFAULT 0,
                    variant VARCHAR(64),
                    PRIMARY KEY (player_uuid, skill_id)
                )
                """,
                "ALTER TABLE rpg_skills ADD COLUMN IF NOT EXISTS point_cost INTEGER NOT NULL DEFAULT 0",
                """
                CREATE TABLE IF NOT EXISTS rpg_skill_mutation_receipts (
                    idempotency_key VARCHAR(96) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    skill_id VARCHAR(64) NOT NULL,
                    operation VARCHAR(12) NOT NULL,
                    expected_variant VARCHAR(64),
                    candidate_variant VARCHAR(64),
                    gold_cost DECIMAL(19, 2) NOT NULL,
                    outcome VARCHAR(16) NOT NULL,
                    resulting_variant VARCHAR(64),
                    settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_admin_test_profiles (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    archetype VARCHAR(24) NOT NULL,
                    ascension INTEGER NOT NULL,
                    level INTEGER NOT NULL,
                    experience BIGINT NOT NULL,
                    energy INTEGER NOT NULL,
                    unspent_attribute_points INTEGER NOT NULL,
                    skill_points INTEGER NOT NULL,
                    defense INTEGER NOT NULL,
                    strength INTEGER NOT NULL,
                    agility INTEGER NOT NULL,
                    intelligence INTEGER NOT NULL,
                    stamina INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_admin_test_skills (
                    player_uuid VARCHAR(36) NOT NULL,
                    skill_id VARCHAR(64) NOT NULL,
                    level INTEGER NOT NULL,
                    point_cost INTEGER NOT NULL,
                    variant VARCHAR(64),
                    PRIMARY KEY (player_uuid, skill_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_skill_book_uses (
                    instance_id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    book_id VARCHAR(64) NOT NULL,
                    limit_group VARCHAR(64),
                    points_granted INTEGER NOT NULL,
                    used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_energy_recovery_quotas (
                    player_uuid VARCHAR(36) NOT NULL,
                    source_id VARCHAR(64) NOT NULL,
                    period_key VARCHAR(32) NOT NULL,
                    uses INTEGER NOT NULL,
                    energy_restored INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, source_id, period_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_hidden_path_caps (
                    path VARCHAR(32) PRIMARY KEY,
                    capacity INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_hidden_paths (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    path VARCHAR(32) NOT NULL,
                    activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_hidden_path_audit (
                    id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    path VARCHAR(32) NOT NULL,
                    action VARCHAR(24) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_dungeon_energy_reservations (
                    id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    energy_cost INTEGER NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    owner_node VARCHAR(64),
                    owner_process VARCHAR(36),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    settled_at TIMESTAMP NULL
                )
                """,
                "ALTER TABLE rpg_dungeon_energy_reservations ADD COLUMN IF NOT EXISTS owner_node VARCHAR(64)",
                "ALTER TABLE rpg_dungeon_energy_reservations ADD COLUMN IF NOT EXISTS owner_process VARCHAR(36)",
                """
                CREATE TABLE IF NOT EXISTS rpg_dungeon_ticket_escrows (
                    reservation_id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    ticket_id VARCHAR(64) NOT NULL,
                    item_payload TEXT NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    delivery_id VARCHAR(36),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    settled_at TIMESTAMP NULL,
                    FOREIGN KEY (reservation_id) REFERENCES rpg_dungeon_energy_reservations(id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_dungeon_reward_receipts (
                    run_id VARCHAR(36) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    dungeon_id VARCHAR(64) NOT NULL,
                    experience_awarded BIGINT NOT NULL,
                    queued_deliveries INTEGER NOT NULL,
                    ticket_dropped BOOLEAN NOT NULL,
                    ticket_failures INTEGER NOT NULL,
                    settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (run_id, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_dungeon_ticket_pity (
                    player_uuid VARCHAR(36) NOT NULL,
                    pity_group VARCHAR(64) NOT NULL,
                    failures INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, pity_group)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_season_rotation_state (
                    season_id VARCHAR(32) NOT NULL,
                    rotation_id VARCHAR(32) NOT NULL,
                    dungeon_id VARCHAR(48) NOT NULL,
                    first_run_id VARCHAR(36),
                    first_completed_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (season_id, rotation_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_season_runs (
                    run_id VARCHAR(36) PRIMARY KEY,
                    season_id VARCHAR(32) NOT NULL,
                    rotation_id VARCHAR(32) NOT NULL,
                    dungeon_id VARCHAR(48) NOT NULL,
                    party_size INTEGER NOT NULL,
                    duration_ms BIGINT NOT NULL,
                    team_key VARCHAR(160) NOT NULL,
                    member_ids VARCHAR(160) NOT NULL,
                    server_first BOOLEAN NOT NULL,
                    completed_at TIMESTAMP NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_season_run_members (
                    run_id VARCHAR(36) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    PRIMARY KEY (run_id, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_season_player_progress (
                    season_id VARCHAR(32) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    honor BIGINT NOT NULL DEFAULT 0,
                    collection_stamps INTEGER NOT NULL DEFAULT 0,
                    completed_runs INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (season_id, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_season_rotation_claims (
                    season_id VARCHAR(32) NOT NULL,
                    rotation_id VARCHAR(32) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    run_id VARCHAR(36) NOT NULL,
                    honor_awarded INTEGER NOT NULL,
                    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (season_id, rotation_id, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_season_personal_bests (
                    season_id VARCHAR(32) NOT NULL,
                    rotation_id VARCHAR(32) NOT NULL,
                    party_size INTEGER NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    run_id VARCHAR(36) NOT NULL,
                    duration_ms BIGINT NOT NULL,
                    completed_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (season_id, rotation_id, party_size, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_node_leases (
                    node_id VARCHAR(64) PRIMARY KEY,
                    process_token VARCHAR(36) NOT NULL,
                    lease_expires_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_merchant_limits (
                    player_uuid VARCHAR(36) NOT NULL,
                    offer_id VARCHAR(64) NOT NULL,
                    period_key VARCHAR(32) NOT NULL,
                    direction VARCHAR(12) NOT NULL,
                    used_units INTEGER NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, offer_id, period_key, direction)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_merchant_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(80) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    offer_id VARCHAR(64) NOT NULL,
                    period_key VARCHAR(32) NOT NULL,
                    direction VARCHAR(12) NOT NULL,
                    units INTEGER NOT NULL,
                    gold_delta DECIMAL(19, 2) NOT NULL,
                    delivery_id VARCHAR(36),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_class_branches (
                    player_uuid VARCHAR(36) NOT NULL,
                    stage INTEGER NOT NULL,
                    branch_id VARCHAR(64) NOT NULL,
                    selected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, stage)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_ascension_receipts (
                    player_uuid VARCHAR(36) NOT NULL,
                    target_ascension INTEGER NOT NULL,
                    archetype VARCHAR(24) NOT NULL,
                    previous_branch_selected BOOLEAN NOT NULL,
                    journey_complete BOOLEAN NOT NULL,
                    equipment_score INTEGER NOT NULL,
                    equipment_ids VARCHAR(640) NOT NULL,
                    learned_skills INTEGER NOT NULL,
                    dungeon_clears BIGINT NOT NULL,
                    merit_spent BIGINT NOT NULL,
                    ascended_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, target_ascension)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_pet_progress (
                    pet_uuid VARCHAR(36) PRIMARY KEY,
                    owner_uuid VARCHAR(36) NOT NULL,
                    species_id VARCHAR(64) NOT NULL,
                    personality_id VARCHAR(64) NOT NULL,
                    trait_id VARCHAR(64) NOT NULL,
                    bond_points INTEGER NOT NULL DEFAULT 0,
                    evolution_stage INTEGER NOT NULL DEFAULT 0,
                    selected_skin_id VARCHAR(64) NOT NULL DEFAULT 'natural',
                    battles BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_pet_collection (
                    player_uuid VARCHAR(36) NOT NULL,
                    species_id VARCHAR(64) NOT NULL,
                    capture_count INTEGER NOT NULL DEFAULT 1,
                    highest_evolution_stage INTEGER NOT NULL DEFAULT 0,
                    first_captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, species_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_pet_skins (
                    pet_uuid VARCHAR(36) NOT NULL,
                    skin_id VARCHAR(64) NOT NULL,
                    source VARCHAR(64) NOT NULL,
                    unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (pet_uuid, skin_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_pet_bond_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    pet_uuid VARCHAR(36) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    source VARCHAR(32) NOT NULL,
                    amount INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_pet_revival_intents (
                    operation_id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    pet_uuid VARCHAR(36) NOT NULL,
                    active_pet_uuid VARCHAR(36) UNIQUE,
                    gold_cost DECIMAL(19, 2) NOT NULL,
                    debit_key VARCHAR(96) UNIQUE NOT NULL,
                    state VARCHAR(24) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP NULL,
                    failure_reason VARCHAR(128)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_commerce_accounts (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    lifetime_paid DECIMAL(19, 2) NOT NULL,
                    credits BIGINT NOT NULL DEFAULT 0,
                    member_until TIMESTAMP NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                "ALTER TABLE rpg_commerce_accounts ADD COLUMN IF NOT EXISTS credits BIGINT NOT NULL DEFAULT 0",
                """
                CREATE TABLE IF NOT EXISTS rpg_payment_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    external_reference VARCHAR(96) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    amount DECIMAL(19, 2) NOT NULL,
                    membership_days INTEGER NOT NULL,
                    operator_name VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_credit_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    external_reference VARCHAR(96) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    delta BIGINT NOT NULL,
                    operator_name VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_starlight_operation_limits (
                    scope_id VARCHAR(16) NOT NULL,
                    period_type VARCHAR(8) NOT NULL,
                    period_key VARCHAR(16) NOT NULL,
                    granted BIGINT NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (scope_id, period_type, period_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_commerce_purchases (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    product_id VARCHAR(64) NOT NULL,
                    period_key VARCHAR(32) NOT NULL DEFAULT 'legacy',
                    credit_cost BIGINT NOT NULL,
                    gold_cost DECIMAL(19, 2) NOT NULL DEFAULT 0,
                    delivery_id VARCHAR(36) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                "ALTER TABLE rpg_commerce_purchases ADD COLUMN IF NOT EXISTS period_key VARCHAR(32) NOT NULL DEFAULT 'legacy'",
                "ALTER TABLE rpg_commerce_purchases ADD COLUMN IF NOT EXISTS gold_cost DECIMAL(19, 2) NOT NULL DEFAULT 0",
                """
                CREATE TABLE IF NOT EXISTS rpg_commerce_reward_claims (
                    player_uuid VARCHAR(36) NOT NULL,
                    reward_id VARCHAR(64) NOT NULL,
                    required_lifetime_paid DECIMAL(19, 2) NOT NULL,
                    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, reward_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_currency_exchange_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(80) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    route_id VARCHAR(48) NOT NULL,
                    units BIGINT NOT NULL,
                    gold_delta DECIMAL(19, 2) NOT NULL,
                    merit_delta BIGINT NOT NULL,
                    starlight_delta BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_currency_exchange_limits (
                    player_uuid VARCHAR(36) NOT NULL,
                    route_id VARCHAR(48) NOT NULL,
                    period_type VARCHAR(8) NOT NULL,
                    period_key VARCHAR(16) NOT NULL,
                    used_units BIGINT NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, route_id, period_type, period_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_community_identity (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    invite_code VARCHAR(16) UNIQUE NOT NULL,
                    mentor_uuid VARCHAR(36),
                    mentor_verified BOOLEAN NOT NULL DEFAULT FALSE,
                    verified_mentees INTEGER NOT NULL DEFAULT 0,
                    mentor_bound_at TIMESTAMP NULL,
                    mentor_verified_at TIMESTAMP NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                "ALTER TABLE rpg_community_identity ADD COLUMN IF NOT EXISTS community_honor BIGINT NOT NULL DEFAULT 0",
                "ALTER TABLE rpg_community_identity ADD COLUMN IF NOT EXISTS weekly_commemorations INTEGER NOT NULL DEFAULT 0",
                """
                CREATE TABLE IF NOT EXISTS rpg_communities (
                    id VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(40) NOT NULL,
                    normalized_name VARCHAR(40) UNIQUE NOT NULL,
                    tag VARCHAR(8) NOT NULL,
                    normalized_tag VARCHAR(8) UNIQUE NOT NULL,
                    owner_uuid VARCHAR(36) NOT NULL,
                    capacity INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_community_members (
                    community_id VARCHAR(36) NOT NULL,
                    player_uuid VARCHAR(36) UNIQUE NOT NULL,
                    role VARCHAR(12) NOT NULL,
                    lifetime_contribution DECIMAL(19, 2) NOT NULL DEFAULT 0,
                    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (community_id, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_community_invites (
                    community_id VARCHAR(36) NOT NULL,
                    inviter_uuid VARCHAR(36) NOT NULL,
                    target_uuid VARCHAR(36) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (community_id, target_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_community_projects (
                    community_id VARCHAR(36) NOT NULL,
                    period_key VARCHAR(24) NOT NULL,
                    gold_contributed DECIMAL(19, 2) NOT NULL DEFAULT 0,
                    verified_recruits INTEGER NOT NULL DEFAULT 0,
                    completed_at TIMESTAMP NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (community_id, period_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_community_contributions (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(80) UNIQUE NOT NULL,
                    community_id VARCHAR(36) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    period_key VARCHAR(24) NOT NULL,
                    amount DECIMAL(19, 2) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_community_project_rewards (
                    player_uuid VARCHAR(36) NOT NULL,
                    period_key VARCHAR(24) NOT NULL,
                    community_id VARCHAR(36) NOT NULL,
                    player_contribution DECIMAL(19, 2) NOT NULL,
                    minimum_contribution DECIMAL(19, 2) NOT NULL,
                    honor_awarded INTEGER NOT NULL,
                    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, period_key)
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_rpg_market_active ON rpg_market_listings (state, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_market_seller_state ON rpg_market_listings (seller_uuid, state, created_at)",
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_rpg_market_open_item ON rpg_market_listings (open_item_identity)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_delivery_mailbox ON rpg_market_deliveries (player_uuid, state, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_equipment_operation_player ON rpg_equipment_operations (player_uuid, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_activity_daily_date ON rpg_activity_daily (activity_date, activity)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_ledger_created ON rpg_ledger (created_at)",
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_rpg_ledger_reversal ON rpg_ledger (reversal_of)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_merit_ledger_created ON rpg_merit_ledger (created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_activity_ledger_created ON rpg_activity_ledger (created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_daily_item_reward_date ON rpg_daily_item_rewards (reward_date, reward_id)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_daily_item_reward_receipt_player ON rpg_daily_item_reward_receipts (player_uuid, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_commission_claim_player_date ON rpg_commission_claims (player_uuid, evidence_date, state)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_payment_ledger_created ON rpg_payment_ledger (created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_commerce_purchase_period ON rpg_commerce_purchases (player_uuid, product_id, period_key)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_credit_ledger_created ON rpg_credit_ledger (created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_skill_book_player_limit ON rpg_skill_book_uses (player_uuid, limit_group)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_energy_quota_period ON rpg_energy_recovery_quotas (source_id, period_key)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_skill_mutation_player ON rpg_skill_mutation_receipts (player_uuid, settled_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_hidden_path_audit_player ON rpg_hidden_path_audit (player_uuid, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_incident_compensations_player "
                        + "ON rpg_incident_compensations (player_uuid, applied_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_dungeon_energy_state ON rpg_dungeon_energy_reservations (state, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_dungeon_energy_owner ON rpg_dungeon_energy_reservations (state, owner_node, owner_process)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_dungeon_ticket_escrow_state ON rpg_dungeon_ticket_escrows (state, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_dungeon_reward_player ON rpg_dungeon_reward_receipts (player_uuid, settled_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_season_runs_board ON rpg_season_runs (season_id, rotation_id, party_size, duration_ms)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_season_runs_team ON rpg_season_runs (season_id, rotation_id, party_size, team_key, duration_ms)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_season_run_member_player ON rpg_season_run_members (player_uuid, run_id)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_season_claim_player ON rpg_season_rotation_claims (player_uuid, season_id, rotation_id)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_node_lease_expiry ON rpg_node_leases (lease_expires_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_merchant_ledger_created ON rpg_merchant_ledger (created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_pet_progress_owner ON rpg_pet_progress (owner_uuid, updated_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_pet_collection_player ON rpg_pet_collection (player_uuid, first_captured_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_pet_bond_pet ON rpg_pet_bond_ledger (pet_uuid, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_pet_revival_player ON rpg_pet_revival_intents (player_uuid, state, created_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_community_members_community ON rpg_community_members (community_id, role)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_community_invites_target ON rpg_community_invites (target_uuid, expires_at)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_community_projects_period ON rpg_community_projects (period_key, gold_contributed)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_community_contribution_player ON rpg_community_contributions (player_uuid, period_key)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_community_contribution_scope ON rpg_community_contributions (player_uuid, community_id, period_key)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_community_reward_community ON rpg_community_project_rewards (community_id, period_key)",
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_rpg_chapter_history_revision ON rpg_chapter_history (season_id, revision)",
                """
                CREATE TABLE IF NOT EXISTS rpg_progression_anomalies (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    ascension INTEGER NOT NULL,
                    level INTEGER NOT NULL,
                    experience BIGINT NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_mechanism_switches (
                    mechanism_id VARCHAR(64) PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_by VARCHAR(64) NOT NULL DEFAULT 'system'
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_season_settlements (
                    id VARCHAR(36) PRIMARY KEY,
                    season_id VARCHAR(32) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    preview_hash VARCHAR(64),
                    approved_by VARCHAR(64),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_support_credit_accounts (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    balance BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_support_credit_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    amount BIGINT NOT NULL,
                    direction VARCHAR(8) NOT NULL,
                    reason VARCHAR(128) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_market_buy_orders (
                    order_id VARCHAR(36) PRIMARY KEY,
                    buyer_uuid VARCHAR(36) NOT NULL,
                    item_matcher VARCHAR(64) NOT NULL,
                    price BIGINT NOT NULL,
                    quantity INTEGER NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_community_approvals (
                    approval_id VARCHAR(36) PRIMARY KEY,
                    community_id VARCHAR(36) NOT NULL,
                    actor_uuid VARCHAR(36) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rpg_session_leases (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    node_id VARCHAR(64) NOT NULL,
                    node_process_token VARCHAR(36) NULL,
                    lease_expires_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                "ALTER TABLE rpg_session_leases ADD COLUMN IF NOT EXISTS node_process_token VARCHAR(36)",
                "CREATE INDEX IF NOT EXISTS idx_rpg_session_leases_expiry"
                        + " ON rpg_session_leases (lease_expires_at)",
                """
                CREATE TABLE IF NOT EXISTS rpg_outbox (
                    event_id VARCHAR(36) PRIMARY KEY,
                    aggregate_type VARCHAR(32) NOT NULL,
                    aggregate_id VARCHAR(36) NOT NULL,
                    idempotency_key VARCHAR(96) NULL,
                    payload TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error VARCHAR(240) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    processed_at TIMESTAMP NULL
                )
                """,
                "ALTER TABLE rpg_outbox ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(96)",
                "ALTER TABLE rpg_outbox ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE rpg_outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(240)",
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_rpg_outbox_idempotency
                    ON rpg_outbox (idempotency_key)
                """,
                "CREATE INDEX IF NOT EXISTS idx_rpg_outbox_pending ON rpg_outbox (processed_at, created_at)",
                """
                CREATE TABLE IF NOT EXISTS rpg_equipment_states (
                    item_id VARCHAR(36) PRIMARY KEY,
                    state VARCHAR(24) NOT NULL,
                    owner_uuid VARCHAR(36),
                    last_operation VARCHAR(24),
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_rpg_equipment_states_owner ON rpg_equipment_states (owner_uuid, state)",
                """
                CREATE TABLE IF NOT EXISTS rpg_equipment_lifecycle_audit (
                    id VARCHAR(36) PRIMARY KEY,
                    item_id VARCHAR(36) NOT NULL,
                    action VARCHAR(16) NOT NULL,
                    from_state VARCHAR(24) NOT NULL,
                    to_state VARCHAR(24) NOT NULL,
                    actor VARCHAR(64) NOT NULL,
                    note VARCHAR(500) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_rpg_equipment_lifecycle_audit_item"
                        + " ON rpg_equipment_lifecycle_audit (item_id, created_at)",
        };

        try (Connection connection = dataSource.getConnection()) {
            executeMigrations(connection, List.of(migrations), postgres, migrationLockTimeoutMs);
        } catch (SQLException exception) {
            throw new IllegalStateException("Database migration failed", exception);
        }
    }

    /**
     * Runs one additive schema pass. PostgreSQL backends serialize every Paper
     * node on a transaction-scoped advisory lock, so a rolling deployment
     * cannot race CREATE/ALTER/INDEX statements. The local H2 development
     * backend intentionally has no cross-process coordination statement.
     */
    static void executeMigrations(
            Connection connection, Iterable<String> migrations, boolean postgres, long lockTimeoutMs
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : migrationCoordinationStatements(postgres, lockTimeoutMs)) {
                statement.execute(sql);
            }
            for (String sql : migrations) {
                statement.execute(sql);
            }
            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    static List<String> migrationCoordinationStatements(boolean postgres, long lockTimeoutMs) {
        if (!postgres) return List.of();
        long boundedTimeout = Math.clamp(lockTimeoutMs, 1_000L, 300_000L);
        return List.of(
                "SET LOCAL lock_timeout = '" + boundedTimeout + "ms'",
                "SELECT pg_advisory_xact_lock(" + MIGRATION_LOCK_NAMESPACE + ", "
                        + MIGRATION_LOCK_RESOURCE + ")"
        );
    }

    @Override
    public void close() {
        String jdbcUrl = dataSource.getJdbcUrl();
        String username = dataSource.getUsername();
        String password = dataSource.getPassword();
        nodeLeaseDataSource.close();
        dataSource.close();
        if (localH2) {
            // Close the pool first so its minimum-idle maintenance cannot
            // reopen H2 between SHUTDOWN and plugin class-loader disposal.
            Properties properties = new Properties();
            properties.setProperty("user", username == null ? "" : username);
            properties.setProperty("password", password == null ? "" : password);
            try (Connection connection = new org.h2.Driver().connect(jdbcUrl, properties);
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (SQLException exception) {
                // H2 can report the connection as closed after a successful
                // SHUTDOWN. The pool has already been closed safely above.
                System.err.println("[RpgCore] Explicit H2 shutdown fallback reported: " + exception.getMessage());
            }
        }
    }

    private static String environmentOrConfig(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
