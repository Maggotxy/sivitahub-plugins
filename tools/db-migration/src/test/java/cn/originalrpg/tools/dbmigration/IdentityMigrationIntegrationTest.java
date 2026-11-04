package cn.originalrpg.tools.dbmigration;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.core.persistence.JdbcEconomyService;
import cn.originalrpg.core.persistence.JdbcProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class IdentityMigrationIntegrationTest {
    @TempDir Path directory;

    @Test void realMigrationHasAnExplicitPolicyForEveryColumnAndRetainsWealthAndGrantIdempotency() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            UUID grant = UUID.randomUUID();
            PlayerProfile before = seed(fixture, grant);
            String historicalKey = "fixture-gold:" + fixture.mapping.source();
            var plan = fixture.preview();
            assertTrue(plan.ready(), plan.blockers().toString());
            assertEquals(SchemaManifest.tables().size(), plan.document().getAsJsonArray("tables").size());
            assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
            assertEquals(fixture.mapping.source().toString(), fixture.value("SELECT player_uuid FROM rpg_profiles"));
            assertEquals("CORE_DATABASE_APPLIED", fixture.apply(plan).outcome());
            assertEquals("ALREADY_APPLIED", fixture.apply(plan).outcome());
            assertEquals(1, fixture.count(IdentityMigrationEngine.RECEIPTS));
            assertEquals(1, fixture.count("rpg_profiles"));
            assertEquals(fixture.mapping.target().toString(), fixture.value("SELECT player_uuid FROM rpg_profiles"));
            assertEquals("125.75", fixture.value("SELECT balance FROM rpg_accounts"));
            assertEquals(historicalKey, fixture.value("SELECT idempotency_key FROM rpg_ledger"));
            assertEquals(fixture.mapping.target().toString(), fixture.value("SELECT receiver_uuid FROM rpg_ledger"));
            assertEquals("local-milestone:" + fixture.mapping.target() + ":0:1", fixture.value("SELECT milestone_key FROM rpg_skill_point_grants"));
            JdbcProfileService reopened = profiles(fixture);
            PlayerProfile after = reopened.grantExperienceOnce(fixture.mapping.target(), grant, firstMilestoneXp()).toCompletableFuture().join();
            assertEquals(before.level(), after.level());
            assertEquals(before.experience(), after.experience());
            assertEquals(before.skillPoints(), after.skillPoints());
            assertEquals(before.attributes(), after.attributes());
            assertEquals(1, fixture.count("rpg_skill_point_grants"));
            assertEquals(1, fixture.count("rpg_experience_grants"));
            // A new legitimate XP grant cannot accidentally reissue an already migrated milestone.
            assertEquals(before.skillPoints(), reopened.grantExperienceOnce(fixture.mapping.target(), UUID.randomUUID(), 1).toCompletableFuture().join().skillPoints());
            assertEquals(1, fixture.count("rpg_skill_point_grants"));
        }
    }

    @Test void commitSucceededButReplyWasLostReplaysOneAtomicReceipt() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            var plan = fixture.preview();
            try (Connection real = fixture.dataSource.getConnection()) {
                Connection uncertain = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        Object answer = method.invoke(real, arguments);
                        if (method.getName().equals("commit")) throw new SQLException("Injected lost commit response");
                        return answer;
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
                MigrationException failure = assertThrows(MigrationException.class, () -> new IdentityMigrationEngine()
                        .apply(uncertain, fixture.schema, fixture.mapping, plan.digest(), fixture::evidence));
                assertTrue(failure.getMessage().contains("uncertain"));
            }
            assertEquals("ALREADY_APPLIED", fixture.apply(plan).outcome());
            assertEquals(1, fixture.count(IdentityMigrationEngine.RECEIPTS));
            assertEquals("125.75", fixture.value("SELECT balance FROM rpg_accounts"));
        }
    }

    @Test void failureAfterUpdatesRollsBackEveryIdentityAndLeavesNoReceipt() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            var plan = fixture.preview();
            try (var connection = fixture.dataSource.getConnection()) {
                assertThrows(MigrationException.class, () -> new IdentityMigrationEngine(() -> { throw new IllegalStateException("fault after identity updates"); })
                        .apply(connection, fixture.schema, fixture.mapping, plan.digest(), fixture::evidence));
            }
            assertEquals(fixture.mapping.source().toString(), fixture.value("SELECT player_uuid FROM rpg_profiles"));
            assertEquals(fixture.mapping.source().toString(), fixture.value("SELECT receiver_uuid FROM rpg_ledger"));
            assertEquals("125.75", fixture.value("SELECT balance FROM rpg_accounts"));
            assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
            assertEquals(plan.digest(), fixture.preview().digest());
        }
    }

    @Test void targetCollisionIsRejectedEvenWhenTargetHasNoProfile() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            fixture.execute("INSERT INTO rpg_accounts (player_uuid,balance) VALUES (?,42.00)", fixture.mapping.target().toString());
            var plan = fixture.preview();
            assertTrue(plan.blockers().contains("TARGET_IDENTITY_COLLISION:rpg_accounts.player_uuid"));
            assertThrows(MigrationException.class, () -> fixture.apply(plan));
            assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
            assertEquals(2, fixture.count("rpg_accounts"));
        }
    }

    @Test void aChangedDatabaseOrOwnershipEvidenceInvalidatesTheReviewedPlan() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            var plan = fixture.preview();
            fixture.execute("UPDATE rpg_accounts SET balance = 125.76");
            assertThrows(MigrationException.class, () -> fixture.apply(plan));
            assertEquals(fixture.mapping.source().toString(), fixture.value("SELECT player_uuid FROM rpg_profiles"));
            var changed = fixture.preview();
            Files.writeString(fixture.mapping.evidenceFile(), "Ownership review was replaced");
            assertThrows(MigrationException.class, () -> fixture.apply(changed));
            assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
        }
    }

    @Test void unknownDatabaseColumnsNeverDefaultToNonIdentity() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            fixture.execute("ALTER TABLE rpg_profiles ADD COLUMN future_owner VARCHAR(36)");
            MigrationException error = assertThrows(MigrationException.class, fixture::preview);
            assertTrue(error.getMessage().contains("future_owner"));
            assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
        }
    }

    @Test void activeNodesSessionsAndOutboxClaimsBlockTheSameReviewedOperation() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            var reviewed = fixture.preview();
            Timestamp future = Timestamp.from(Instant.now().plusSeconds(3600));
            fixture.execute("INSERT INTO rpg_node_leases (node_id,process_token,lease_expires_at) VALUES ('live',?,?)", UUID.randomUUID().toString(), future);
            fixture.execute("INSERT INTO rpg_session_leases (player_uuid,node_id,lease_expires_at) VALUES (?,'live',?)", fixture.mapping.source().toString(), future);
            fixture.execute("INSERT INTO rpg_outbox (event_id,aggregate_type,aggregate_id,payload,claim_token,claim_expires_at) VALUES (?,'other',?,'{}',?,?)",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), future);
            var blocked = fixture.preview();
            assertTrue(blocked.blockers().containsAll(List.of("ACTIVE_AUTHORITY:rpg_node_leases", "ACTIVE_AUTHORITY:rpg_session_leases", "ACTIVE_OUTBOX_CLAIM")));
            assertThrows(MigrationException.class, () -> fixture.apply(reviewed));
            assertEquals(fixture.mapping.source().toString(), fixture.value("SELECT player_uuid FROM rpg_profiles"));
        }
    }

    @Test void derivedTeamRoleQuotaAndDungeonEventIdentitiesFollowTheirBusinessFormats() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            UUID run = UUID.randomUUID(), other = UUID.randomUUID();
            String team = List.of(fixture.mapping.source(), other).stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
            fixture.execute("INSERT INTO rpg_season_runs (run_id,season_id,rotation_id,dungeon_id,party_size,duration_ms,team_key,member_ids,server_first,completed_at) VALUES (?,'s1','r1','trial',2,5000,?,?,FALSE,CURRENT_TIMESTAMP)", run.toString(), team, team);
            fixture.execute("INSERT INTO rpg_community_approvals (approval_id,community_id,actor_uuid,action,status,subject) VALUES (?,?,?,'ROLE_GRANT','PENDING',?)",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), other.toString(), fixture.mapping.source() + ":ARCHITECT");
            fixture.execute("INSERT INTO rpg_support_credit_limits (scope_id,week_key,issued_units) VALUES (?,'2026-W45',7)", "player:" + fixture.mapping.source());
            String event = "{\"runId\":\"" + run + "\",\"player\":\"" + fixture.mapping.source() + "\",\"dungeon\":\"trial\"}";
            String originalKey = "dungeon:" + fixture.mapping.source();
            fixture.execute("INSERT INTO rpg_outbox (event_id,aggregate_type,aggregate_id,idempotency_key,payload) VALUES (?,'dungeon.completion',?,?,?)", UUID.randomUUID().toString(), run.toString(), originalKey, event);
            var plan = fixture.preview();
            assertTrue(plan.ready(), plan.blockers().toString());
            fixture.apply(plan);
            String expected = List.of(fixture.mapping.target(), other).stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
            assertEquals(expected, fixture.value("SELECT team_key FROM rpg_season_runs"));
            assertEquals(expected, fixture.value("SELECT member_ids FROM rpg_season_runs"));
            assertEquals(fixture.mapping.target() + ":ARCHITECT", fixture.value("SELECT subject FROM rpg_community_approvals"));
            assertEquals("player:" + fixture.mapping.target(), fixture.value("SELECT scope_id FROM rpg_support_credit_limits"));
            assertEquals("7", fixture.value("SELECT issued_units FROM rpg_support_credit_limits"));
            assertEquals(fixture.mapping.target().toString(), IdentityJson.parse(fixture.value("SELECT payload FROM rpg_outbox")).get("player").getAsString());
            assertEquals(originalKey, fixture.value("SELECT idempotency_key FROM rpg_outbox"));
        }
    }

    @Test void opaquePendingMailIsBlockedEvenWhenItsRecipientIsAnotherPlayer() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            fixture.execute("INSERT INTO rpg_market_deliveries (id,player_uuid,item_payload,source,state) VALUES (?,?,'opaque-signed-item','fixture','PENDING')", UUID.randomUUID().toString(), UUID.randomUUID().toString());
            var plan = fixture.preview();
            assertTrue(plan.blockers().contains("OPAQUE_ASSET_ADAPTER_REQUIRED:rpg_market_deliveries.item_payload"));
            assertThrows(MigrationException.class, () -> fixture.apply(plan));
            fixture.execute("UPDATE rpg_market_deliveries SET state='DELIVERED'");
            var archived = fixture.preview();
            assertTrue(archived.ready(), archived.blockers().toString());
            fixture.apply(archived);
            assertEquals("opaque-signed-item", fixture.value("SELECT item_payload FROM rpg_market_deliveries"));
        }
    }

    @Test void signedPaymentProofRemainsImmutableAndBlocksTheAffectedMapping() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            fixture.execute("INSERT INTO rpg_support_payment_orders (order_id,player_uuid,key_id,audience,credit_units,issued_at,expires_at,payload_hash,signed_payload,signature,ledger_key,week_key) VALUES ('receipt',?,'key','fixture',7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'hash','signed original payer proof','signature','receipt-ledger','2026-W45')", fixture.mapping.source().toString());
            var plan = fixture.preview();
            assertTrue(plan.blockers().contains("SIGNED_PAYMENT_RECEIPT_ADAPTER_REQUIRED:rpg_support_payment_orders.signed_payload"));
            assertThrows(MigrationException.class, () -> fixture.apply(plan));
            assertEquals("signed original payer proof", fixture.value("SELECT signed_payload FROM rpg_support_payment_orders"));
            assertEquals(fixture.mapping.source().toString(), fixture.value("SELECT player_uuid FROM rpg_support_payment_orders"));
        }
    }

    @Test void expiredMailboxClaimsBlockIdentityChangesEvenWhenPayloadEvidenceIsEmpty() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            var reviewed = fixture.preview();
            for (String state : List.of("CLAIMED", "STAGING")) {
                fixture.execute("DELETE FROM rpg_market_deliveries");
                fixture.execute("INSERT INTO rpg_market_deliveries (id,player_uuid,item_payload,source,state,claim_token,claim_expires_at) VALUES (?,?,'','fixture',?,?,?)",
                        UUID.randomUUID().toString(), fixture.mapping.source().toString(), state, UUID.randomUUID().toString(), Timestamp.from(Instant.EPOCH));
                assertTrue(fixture.preview().blockers().contains("UNRESOLVED_MAILBOX_CLAIMS"));
                assertThrows(MigrationException.class, () -> fixture.apply(reviewed));
                assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
            }
        }
    }

    @Test void playerFileAndDeclaredExternalAdaptersAreHardGates() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            Path players = fixture.runtime.resolve("world/playerdata"); Files.createDirectories(players);
            Files.write(players.resolve(fixture.mapping.source() + ".dat"), new byte[]{0, 1, 2});
            var plan = fixture.preview();
            assertTrue(plan.blockers().stream().anyMatch(value -> value.startsWith("EXTERNAL_FILE_ADAPTER_REQUIRED:")));
            assertThrows(MigrationException.class, () -> fixture.apply(plan));
            Files.delete(players.resolve(fixture.mapping.source() + ".dat"));
            Files.createDirectories(fixture.runtime.resolve("plugins/AuthMe"));
            assertTrue(fixture.preview().blockers().stream().anyMatch(value -> value.startsWith("EXTERNAL_STORE_PRESENT:authme")));
        }
    }

    @Test void unrecognizedAndDuplicateKeyOutboxPayloadsCannotBeSilentlyRewritten() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            UUID run = UUID.randomUUID();
            String malformed = "{\"runId\":\"" + run + "\",\"player\":\"" + UUID.randomUUID() + "\",\"player\":\"" + fixture.mapping.source() + "\",\"dungeon\":\"trial\"}";
            fixture.execute("INSERT INTO rpg_outbox (event_id,aggregate_type,aggregate_id,payload) VALUES (?,'dungeon.completion',?,?)", UUID.randomUUID().toString(), run.toString(), malformed);
            assertTrue(fixture.preview().blockers().contains("UNRECOGNIZED_DUNGEON_EVENT:rpg_outbox.payload"));
        }
    }

    @Test void postgresRechecksAuthorityCommittedDuringTheMaintenanceLockWait() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(fixture.postgres, "Requires actual PostgreSQL lock/snapshot semantics");
            seed(fixture, UUID.randomUUID());
            var plan = fixture.preview();
            var approachingNodeLock = new java.util.concurrent.CountDownLatch(1);
            try (var writer = fixture.dataSource.getConnection(); var insert = writer.prepareStatement(
                    "INSERT INTO rpg_node_leases (node_id,process_token,lease_expires_at) VALUES ('starting',?,?)");
                 var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setTimestamp(2, Timestamp.from(Instant.now().plusSeconds(3600)));
                insert.executeUpdate();
                var apply = workers.submit(() -> {
                    try (var real = fixture.dataSource.getConnection()) {
                        Connection observed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            try {
                                Object answer = method.invoke(real, args);
                                if (!method.getName().equals("createStatement")) return answer;
                                return Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(), new Class<?>[]{java.sql.Statement.class}, (statementProxy, call, parameters) -> {
                                    if (call.getName().equals("execute") && parameters[0] instanceof String sql
                                            && sql.startsWith("LOCK TABLE") && sql.contains("rpg_node_leases")) approachingNodeLock.countDown();
                                    try { return call.invoke(answer, parameters); }
                                    catch (InvocationTargetException error) { throw error.getCause(); }
                                });
                            } catch (InvocationTargetException error) { throw error.getCause(); }
                        });
                        return assertThrows(MigrationException.class, () -> new IdentityMigrationEngine()
                                .apply(observed, fixture.schema, fixture.mapping, plan.digest(), fixture::evidence));
                    }
                });
                try {
                    assertTrue(approachingNodeLock.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    writer.commit();
                    assertTrue(apply.get(20, java.util.concurrent.TimeUnit.SECONDS).getMessage().contains("stopped authorities"));
                } finally { writer.rollback(); }
            }
            assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
            assertEquals(fixture.mapping.source().toString(), fixture.value("SELECT player_uuid FROM rpg_profiles"));
        }
    }

    @Test void equipmentAdapterGateMatchesEveryAuthoritativeLifecycleStateAndRejectsUnknownNames() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            seed(fixture, UUID.randomUUID());
            fixture.execute("INSERT INTO rpg_equipment_states (item_id,state,owner_uuid) VALUES (?,'CREATED',?)",
                    UUID.randomUUID().toString(), fixture.mapping.source().toString());
            for (var state : cn.originalrpg.api.rules.EquipmentLifecycle.State.values()) {
                fixture.execute("UPDATE rpg_equipment_states SET state = ?", state.name());
                boolean blocked = fixture.preview().blockers().contains("SIGNED_EQUIPMENT_ADAPTER_REQUIRED:rpg_equipment_states");
                assertEquals(!cn.originalrpg.api.rules.EquipmentLifecycle.isTerminal(state), blocked, state.name());
            }
            for (String unknown : List.of("RECYCLED", "RETIRED", "FUTURE_TERMINAL")) {
                fixture.execute("UPDATE rpg_equipment_states SET state = ?", unknown);
                assertTrue(fixture.preview().blockers().contains("SIGNED_EQUIPMENT_ADAPTER_REQUIRED:rpg_equipment_states"), unknown);
            }
            assertEquals(0, fixture.count(IdentityMigrationEngine.RECEIPTS));
        }
    }

    private static JdbcProfileService profiles(IdentityDatabaseFixture fixture) { return new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2); }

    private static PlayerProfile seed(IdentityDatabaseFixture fixture, UUID grant) {
        PlayerProfile profile = profiles(fixture).grantExperienceOnce(fixture.mapping.source(), grant, firstMilestoneXp()).toCompletableFuture().join();
        new JdbcEconomyService(fixture.dataSource, Runnable::run, BigDecimal.ZERO).credit(fixture.mapping.source(), new BigDecimal("125.75"),
                "fixture-gold:" + fixture.mapping.source(), "fixture identity migration").toCompletableFuture().join();
        return profile;
    }

    private static long firstMilestoneXp() {
        return ProgressionRules.experienceToNextLocalLevel(0, 1) + ProgressionRules.experienceToNextLocalLevel(0, 2) + 17;
    }
}
