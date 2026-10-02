package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.IncidentActivityCompensation;
import cn.originalrpg.api.model.IncidentConditionalActivityAudit;
import cn.originalrpg.api.model.IncidentConditionalActivityCompensation;
import cn.originalrpg.api.model.IncidentCompensationRequest;
import cn.originalrpg.api.model.IncidentCompensationResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcAdminIncidentCompensationServiceTest {
    private static final UUID PLAYER = UUID.fromString("b8229850-d84a-39ff-bd79-7529c4887403");
    private final Executor direct = Runnable::run;
    private JdbcDataSource dataSource;
    private JdbcProfileService profiles;
    private JdbcAdminIncidentCompensationService incidents;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:incident-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE rpg_profiles (
                        player_uuid VARCHAR(36) PRIMARY KEY, archetype VARCHAR(24) NOT NULL,
                        ascension INTEGER NOT NULL, level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        energy INTEGER NOT NULL, unspent_attribute_points INTEGER NOT NULL,
                        skill_points INTEGER NOT NULL, defense INTEGER NOT NULL, strength INTEGER NOT NULL,
                        agility INTEGER NOT NULL, intelligence INTEGER NOT NULL, stamina INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_skill_point_grants (
                        source VARCHAR(96) NOT NULL, idempotency_key VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL, from_local_level INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (source, idempotency_key)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE rpg_accounts (player_uuid VARCHAR(36) PRIMARY KEY,
                        balance DECIMAL(19, 2) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_progression_wallets (player_uuid VARCHAR(36) PRIMARY KEY,
                        merit BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_activity_daily (player_uuid VARCHAR(36) NOT NULL,
                        activity VARCHAR(24) NOT NULL, activity_date DATE NOT NULL,
                        gold_earned DECIMAL(19,2) NOT NULL, merit_earned BIGINT NOT NULL,
                        PRIMARY KEY(player_uuid, activity, activity_date))
                    """);
            sql.execute("""
                    CREATE TABLE rpg_ledger (id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36), amount DECIMAL(19,2) NOT NULL,
                        fee DECIMAL(19,2) NOT NULL, reason VARCHAR(128) NOT NULL,
                        reversal_of VARCHAR(96), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_merit_ledger (id VARCHAR(36) PRIMARY KEY,
                        idempotency_key VARCHAR(96) UNIQUE NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        amount BIGINT NOT NULL, direction VARCHAR(8) NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_activity_ledger (id VARCHAR(36) PRIMARY KEY,
                        idempotency_key VARCHAR(96) UNIQUE NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        activity VARCHAR(24) NOT NULL, requested_gold DECIMAL(19,2) NOT NULL,
                        granted_gold DECIMAL(19,2) NOT NULL, requested_merit BIGINT NOT NULL,
                        granted_merit BIGINT NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_incident_compensations (incident_key VARCHAR(96) PRIMARY KEY,
                        request_hash VARCHAR(64) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        activity_date DATE NOT NULL, experience BIGINT NOT NULL,
                        total_gold DECIMAL(19,2) NOT NULL, total_merit BIGINT NOT NULL,
                        before_profile TEXT NOT NULL, after_profile TEXT NOT NULL,
                        actor VARCHAR(64) NOT NULL, reason VARCHAR(256) NOT NULL,
                        applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_incident_compensation_lines (incident_key VARCHAR(96) NOT NULL,
                        line_kind VARCHAR(16) NOT NULL, line_id VARCHAR(128) NOT NULL,
                        activity VARCHAR(24), gold DECIMAL(19,2) NOT NULL DEFAULT 0,
                        merit BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(incident_key, line_kind, line_id),
                        FOREIGN KEY(incident_key) REFERENCES rpg_incident_compensations(incident_key))
                    """);
            connection.commit();
        }
        profiles = new JdbcProfileService(dataSource, direct, 100, 2);
        incidents = new JdbcAdminIncidentCompensationService(
                dataSource, direct, profiles, BigDecimal.ZERO.setScale(2));
        profiles.get(PLAYER).toCompletableFuture().join();
    }

    @AfterEach
    void close() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("SHUTDOWN");
        }
    }

    @Test
    void appliesCompleteIncidentOnceAndPersistsEveryAuditDetail() throws Exception {
        var applied = incidents.apply(request("console", "documented outage"))
                .toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.APPLIED, applied.status());
        var audit = applied.audit().orElseThrow();
        assertEquals(34_100L, audit.experience());
        assertEquals(new BigDecimal("605.36"), audit.totalGold());
        assertEquals(10L, audit.totalMerit());
        assertEquals(3, audit.evidenceIds().size());
        assertEquals(1, audit.activities().size());
        assertEquals(3, audit.conditionalActivities().size());
        assertTrue(audit.conditionalActivities().stream().allMatch(
                line -> line.status() == IncidentConditionalActivityAudit.Status.APPLIED));
        assertEquals(audit.afterProfile(), profiles.get(PLAYER).toCompletableFuture().join());
        assertNotEquals(audit.beforeProfile(), audit.afterProfile());
        assertEquals(new BigDecimal("605.36"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(10L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_ledger"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(7L, number("SELECT COUNT(*) FROM rpg_incident_compensation_lines"));
        assertEquals(new BigDecimal("245.36"), decimal(
                "SELECT gold_earned FROM rpg_activity_daily WHERE activity='HUNTING'"));
        assertEquals(new BigDecimal("360.00"), decimal(
                "SELECT gold_earned FROM rpg_activity_daily WHERE activity='EXPLORATION'"));
    }

    @Test
    void matchingOriginalRewardIsEvidenceOnlyWhileMissingRewardsAreRestored() throws Exception {
        IncidentCompensationRequest request = request("console", "documented outage");
        insertOriginalReward(request.conditionalActivities().getFirst());

        var applied = incidents.apply(request).toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.APPLIED, applied.status());
        var audit = applied.audit().orElseThrow();
        assertEquals(new BigDecimal("485.36"), audit.totalGold());
        assertEquals(9L, audit.totalMerit());
        assertEquals(1L, audit.conditionalActivities().stream().filter(
                line -> line.status() == IncidentConditionalActivityAudit.Status.ALREADY_PRESENT).count());
        assertEquals(2L, audit.conditionalActivities().stream().filter(
                line -> line.status() == IncidentConditionalActivityAudit.Status.APPLIED).count());
        assertEquals(new BigDecimal("605.36"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(10L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void fullGoldBalanceRejectsWithoutClaimingOrMutatingAnythingAndCanRetryAfterSpending() throws Exception {
        setGold(new BigDecimal("999999999400.00"));
        var cachedBefore = profiles.cached(PLAYER).orElseThrow();

        var rejected = incidents.apply(request("console", "documented outage"))
                .toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.BALANCE_LIMIT, rejected.status());
        assertTrue(rejected.audit().isEmpty());
        assertEquals(new BigDecimal("999999999400.00"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(0L, number("SELECT experience FROM rpg_profiles"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_progression_wallets"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_activity_daily"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_incident_compensation_lines"));
        assertEquals(cachedBefore, profiles.cached(PLAYER).orElseThrow());

        // Spending just enough Gold must leave the original incident key
        // available for an otherwise identical retry.
        setGold(new BigDecimal("999999999390.00"));
        var retried = incidents.apply(request("console", "documented outage"))
                .toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.APPLIED, retried.status());
        assertEquals(new BigDecimal("999999999995.36"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(retried.audit().orElseThrow().afterProfile().experience(),
                number("SELECT experience FROM rpg_profiles"));
    }

    @Test
    void completedIncidentReplayStaysDuplicateWhenGoldBalanceIsFull() throws Exception {
        var first = incidents.apply(request("console", "documented outage"))
                .toCompletableFuture().join();
        setGold(new BigDecimal("1000000000000.00"));

        var replay = incidents.apply(request("another-operator", "documented outage"))
                .toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.DUPLICATE, replay.status());
        assertEquals(first.audit().orElseThrow().requestHash(), replay.audit().orElseThrow().requestHash());
        assertEquals(new BigDecimal("1000000000000.00"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(first.audit().orElseThrow().afterProfile().experience(),
                number("SELECT experience FROM rpg_profiles"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void fullMeritBalanceRejectsWithoutClaimingOrMutatingAnythingAndCanRetryAfterSpending() throws Exception {
        setMerit(999_999_995L);
        var cachedBefore = profiles.cached(PLAYER).orElseThrow();

        var rejected = incidents.apply(request("console", "documented outage"))
                .toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.MERIT_LIMIT, rejected.status());
        assertTrue(rejected.audit().isEmpty());
        assertEquals(999_999_995L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(0L, number("SELECT experience FROM rpg_profiles"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_accounts"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_activity_daily"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_incident_compensation_lines"));
        assertEquals(cachedBefore, profiles.cached(PLAYER).orElseThrow());

        setMerit(999_999_990L);
        var retried = incidents.apply(request("console", "documented outage"))
                .toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.APPLIED, retried.status());
        assertEquals(1_000_000_000L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(retried.audit().orElseThrow().afterProfile().experience(),
                number("SELECT experience FROM rpg_profiles"));
    }

    @Test
    void completedIncidentReplayStaysDuplicateWhenMeritBalanceIsFull() throws Exception {
        var first = incidents.apply(request("console", "documented outage"))
                .toCompletableFuture().join();
        setMerit(1_000_000_000L);

        var replay = incidents.apply(request("another-operator", "documented outage"))
                .toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.DUPLICATE, replay.status());
        assertEquals(first.audit().orElseThrow().requestHash(), replay.audit().orElseThrow().requestHash());
        assertEquals(1_000_000_000L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(first.audit().orElseThrow().afterProfile().experience(),
                number("SELECT experience FROM rpg_profiles"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void conflictingOriginalRewardFailsClosedAndRollsBackEverything() throws Exception {
        IncidentCompensationRequest request = request("console", "documented outage");
        IncidentConditionalActivityCompensation original = request.conditionalActivities().getFirst();
        insertOriginalReward(original);
        try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_activity_ledger SET player_uuid = ? WHERE idempotency_key = ?")) {
            update.setString(1, UUID.randomUUID().toString());
            update.setString(2, original.idempotencyKey());
            update.executeUpdate();
            connection.commit();
        }

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> incidents.apply(request).toCompletableFuture().join());

        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(0L, number("SELECT experience FROM rpg_profiles"));
        assertEquals(new BigDecimal("120.00"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(1L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void exactReplayReturnsOriginalReceiptWithoutAnySecondMutation() throws Exception {
        var first = incidents.apply(request("console", "documented outage")).toCompletableFuture().join();
        profiles.grantExperience(PLAYER, 1L).toCompletableFuture().join();
        var current = profiles.cached(PLAYER).orElseThrow();
        var replay = incidents.apply(request("different-operator", "documented outage"))
                .toCompletableFuture().join();

        // Actor is execution metadata, not part of the approved compensation facts.
        assertEquals(IncidentCompensationResult.Status.DUPLICATE, replay.status());
        assertEquals(first.audit().orElseThrow().requestHash(), replay.audit().orElseThrow().requestHash());
        assertEquals(new BigDecimal("605.36"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(10L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
        assertEquals(current, profiles.cached(PLAYER).orElseThrow());
    }

    @Test
    void changedFactsUnderSameIncidentKeyConflictWithoutMutation() throws Exception {
        incidents.apply(request("console", "documented outage")).toCompletableFuture().join();
        IncidentCompensationRequest changed = new IncidentCompensationRequest(
                request("console", "documented outage").incidentKey(), PLAYER, LocalDate.of(2026, 8, 12),
                34_101L, request("console", "documented outage").activities(),
                request("console", "documented outage").conditionalActivities(),
                request("console", "documented outage").evidenceIds(), "console", "documented outage");

        var conflict = incidents.apply(changed).toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.CONFLICT, conflict.status());
        assertEquals(new BigDecimal("605.36"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void anyLedgerFailureRollsBackProfileWalletAccountAndClaim() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE rpg_activity_ledger");
            connection.commit();
        }

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> incidents.apply(request("console", "documented outage")).toCompletableFuture().join());

        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_accounts"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_progression_wallets"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_profiles"));
        assertEquals(1L, number("SELECT level FROM rpg_profiles"));
        assertEquals(0L, number("SELECT experience FROM rpg_profiles"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
    }

    @Test
    void concurrentReplayHasExactlyOneWinner() throws Exception {
        try (var concurrent = Executors.newVirtualThreadPerTaskExecutor()) {
            JdbcAdminIncidentCompensationService service = new JdbcAdminIncidentCompensationService(
                    dataSource, concurrent, profiles, BigDecimal.ZERO.setScale(2));
            var first = service.apply(request("console-a", "documented outage")).toCompletableFuture();
            var second = service.apply(request("console-b", "documented outage")).toCompletableFuture();

            List<IncidentCompensationResult.Status> statuses = List.of(first.join().status(), second.join().status());
            assertEquals(1L, statuses.stream().filter(
                    IncidentCompensationResult.Status.APPLIED::equals).count());
            assertEquals(1L, statuses.stream().filter(
                    IncidentCompensationResult.Status.DUPLICATE::equals).count());
        }
        assertEquals(new BigDecimal("605.36"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(10L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void concurrentDifferentIncidentsCannotCrossTheGoldBalanceLimit() throws Exception {
        setGold(new BigDecimal("999999999394.64"));
        try (var concurrent = Executors.newVirtualThreadPerTaskExecutor()) {
            JdbcAdminIncidentCompensationService service = new JdbcAdminIncidentCompensationService(
                    dataSource, concurrent, profiles, BigDecimal.ZERO.setScale(2));
            var first = service.apply(requestWithKey(
                    "ledger-outage-2026-08-12-cure-white-a", "console-a", "documented outage"))
                    .toCompletableFuture();
            var second = service.apply(requestWithKey(
                    "ledger-outage-2026-08-12-cure-white-b", "console-b", "documented outage"))
                    .toCompletableFuture();

            IncidentCompensationResult firstResult = first.join();
            IncidentCompensationResult secondResult = second.join();
            List<IncidentCompensationResult.Status> statuses = List.of(
                    firstResult.status(), secondResult.status());
            assertEquals(1L, statuses.stream().filter(IncidentCompensationResult.Status.APPLIED::equals).count());
            assertEquals(1L, statuses.stream().filter(
                    IncidentCompensationResult.Status.BALANCE_LIMIT::equals).count());
            long committedExperience = firstResult.audit().or(() -> secondResult.audit())
                    .orElseThrow().afterProfile().experience();
            assertEquals(committedExperience, number("SELECT experience FROM rpg_profiles"));
        }
        assertEquals(new BigDecimal("1000000000000.00"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(10L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void concurrentDifferentIncidentsCannotCrossTheMeritBalanceLimit() throws Exception {
        setMerit(999_999_990L);
        try (var concurrent = Executors.newVirtualThreadPerTaskExecutor()) {
            JdbcAdminIncidentCompensationService service = new JdbcAdminIncidentCompensationService(
                    dataSource, concurrent, profiles, BigDecimal.ZERO.setScale(2));
            var first = service.apply(requestWithKey(
                    "ledger-outage-2026-08-12-cure-merit-a", "console-a", "documented outage"))
                    .toCompletableFuture();
            var second = service.apply(requestWithKey(
                    "ledger-outage-2026-08-12-cure-merit-b", "console-b", "documented outage"))
                    .toCompletableFuture();

            IncidentCompensationResult firstResult = first.join();
            IncidentCompensationResult secondResult = second.join();
            List<IncidentCompensationResult.Status> statuses = List.of(
                    firstResult.status(), secondResult.status());
            assertEquals(1L, statuses.stream().filter(IncidentCompensationResult.Status.APPLIED::equals).count());
            assertEquals(1L, statuses.stream().filter(
                    IncidentCompensationResult.Status.MERIT_LIMIT::equals).count());
            long committedExperience = firstResult.audit().or(() -> secondResult.audit())
                    .orElseThrow().afterProfile().experience();
            assertEquals(committedExperience, number("SELECT experience FROM rpg_profiles"));
        }
        assertEquals(1_000_000_000L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
        assertEquals(new BigDecimal("605.36"), decimal("SELECT balance FROM rpg_accounts"));
        assertEquals(4L, number("SELECT COUNT(*) FROM rpg_activity_ledger"));
    }

    @Test
    void unknownTargetDoesNotCreateAProfileOrIncidentClaim() throws Exception {
        IncidentCompensationRequest original = request("console", "documented outage");
        IncidentCompensationRequest unknown = new IncidentCompensationRequest(
                "ledger-outage-2026-08-12-unknown", UUID.randomUUID(), original.activityDate(),
                original.experience(), original.activities(), original.conditionalActivities(), original.evidenceIds(),
                original.actor(), original.reason());

        var result = incidents.apply(unknown).toCompletableFuture().join();

        assertEquals(IncidentCompensationResult.Status.NOT_FOUND, result.status());
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_profiles"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_incident_compensations"));
    }

    private IncidentCompensationRequest request(String actor, String reason) {
        return requestWithKey("ledger-outage-2026-08-12-cure-white", actor, reason);
    }

    private IncidentCompensationRequest requestWithKey(String incidentKey, String actor, String reason) {
        return new IncidentCompensationRequest(
                incidentKey, PLAYER, LocalDate.of(2026, 8, 12), 34_100L,
                List.of(new IncidentActivityCompensation(EconomyActivity.HUNTING,
                        new BigDecimal("245.36"), 7L)),
                List.of(
                        conditional("minecraft:nether/find_bastion"),
                        conditional("minecraft:nether/loot_bastion"),
                        conditional("minecraft:nether/obtain_ancient_debris")),
                List.of("minecraft:nether/find_bastion", "minecraft:nether/loot_bastion",
                        "minecraft:nether/obtain_ancient_debris"), actor, reason);
    }

    private IncidentConditionalActivityCompensation conditional(String evidence) {
        UUID identity = UUID.nameUUIDFromBytes(evidence.getBytes(StandardCharsets.UTF_8));
        return new IncidentConditionalActivityCompensation(evidence, EconomyActivity.EXPLORATION,
                new BigDecimal("120.00"), 1L, "adv:" + PLAYER + ":" + identity,
                "vanilla-advancement:" + evidence);
    }

    private void insertOriginalReward(IncidentConditionalActivityCompensation reward) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO rpg_accounts(player_uuid, balance) VALUES (?, ?)")) {
                insert.setString(1, PLAYER.toString());
                insert.setBigDecimal(2, reward.gold());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO rpg_progression_wallets(player_uuid, merit) VALUES (?, ?)")) {
                insert.setString(1, PLAYER.toString());
                insert.setLong(2, reward.merit());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_ledger(id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                    VALUES (?, ?, NULL, ?, ?, 0, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, reward.idempotencyKey());
                insert.setString(3, PLAYER.toString());
                insert.setBigDecimal(4, reward.gold());
                insert.setString(5, reward.reason());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_merit_ledger(id, idempotency_key, player_uuid, amount, direction, reason)
                    VALUES (?, ?, ?, ?, 'CREDIT', ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, reward.idempotencyKey());
                insert.setString(3, PLAYER.toString());
                insert.setLong(4, reward.merit());
                insert.setString(5, reward.reason());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_activity_ledger(id, idempotency_key, player_uuid, activity,
                        requested_gold, granted_gold, requested_merit, granted_merit, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, reward.idempotencyKey());
                insert.setString(3, PLAYER.toString());
                insert.setString(4, reward.activity().name());
                insert.setBigDecimal(5, reward.gold());
                insert.setBigDecimal(6, reward.gold());
                insert.setLong(7, reward.merit());
                insert.setLong(8, reward.merit());
                insert.setString(9, reward.reason());
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private void setGold(BigDecimal balance) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE rpg_accounts SET balance = ? WHERE player_uuid = ?")) {
                update.setBigDecimal(1, balance);
                update.setString(2, PLAYER.toString());
                changed = update.executeUpdate();
            }
            if (changed == 0) try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO rpg_accounts(player_uuid, balance) VALUES (?, ?)")) {
                insert.setString(1, PLAYER.toString());
                insert.setBigDecimal(2, balance);
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private void setMerit(long merit) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE rpg_progression_wallets SET merit = ? WHERE player_uuid = ?")) {
                update.setLong(1, merit);
                update.setString(2, PLAYER.toString());
                changed = update.executeUpdate();
            }
            if (changed == 0) try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO rpg_progression_wallets(player_uuid, merit) VALUES (?, ?)")) {
                insert.setString(1, PLAYER.toString());
                insert.setLong(2, merit);
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private long number(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql);
             ResultSet result = query.executeQuery()) {
            result.next();
            long value = result.getLong(1);
            connection.rollback();
            return value;
        }
    }

    private BigDecimal decimal(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql);
             ResultSet result = query.executeQuery()) {
            result.next();
            BigDecimal value = result.getBigDecimal(1).setScale(2);
            connection.rollback();
            return value;
        }
    }
}
