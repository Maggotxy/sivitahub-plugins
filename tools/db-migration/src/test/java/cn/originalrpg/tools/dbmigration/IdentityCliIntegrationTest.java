package cn.originalrpg.tools.dbmigration;

import cn.originalrpg.core.persistence.JdbcProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class IdentityCliIntegrationTest {
    @TempDir Path directory;

    @Test void stoppedRealFileDatabaseCanPlanApplyAndReplayThroughThePublicCommand() throws Exception {
        IdentityDatabaseFixture fixture = new IdentityDatabaseFixture(directory, true);
        try {
            new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2).get(fixture.mapping.source()).toCompletableFuture().join();
        } finally { fixture.close(); }
        String url = "jdbc:h2:file:" + fixture.coreH2Base + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        var common = new ArrayList<>(List.of("identity", "--jdbc-url", url, "--schema", "public", "--user", "sa",
                "--paper-runtime", fixture.runtime.toString(), "--mapping", fixture.mappingFile.toString(), "--coverage", fixture.coverage.toString()));
        Path plan = directory.resolve("reviewed-plan.json");
        assertEquals(0, MigrationMain.run(append(common, "--dry-run", "--plan", plan.toString()), Map.of()));
        var report = IdentityJson.read(plan, 16 * 1024 * 1024);
        assertTrue(report.get("core_apply_ready").getAsBoolean());
        assertFalse(report.get("full_server_identity_cutover_verified").getAsBoolean());
        String catalog = report.get("database_catalog").getAsString();
        assertEquals(2, MigrationMain.run(append(common, "--apply", "--reviewed-plan", plan.toString(), "--confirm-database", "wrong-database"), Map.of()));
        assertEquals(0, MigrationMain.run(append(common, "--apply", "--reviewed-plan", plan.toString(), "--confirm-database", catalog), Map.of()));
        assertEquals(0, MigrationMain.run(append(common, "--apply", "--reviewed-plan", plan.toString(), "--confirm-database", catalog), Map.of()));
        try (var connection = DriverManager.getConnection(url, "sa", ""); var query = connection.createStatement()) {
            try (var rows = query.executeQuery("SELECT player_uuid FROM rpg_profiles")) {
                assertTrue(rows.next()); assertEquals(fixture.mapping.target().toString(), rows.getString(1)); assertFalse(rows.next());
            }
            try (var rows = query.executeQuery("SELECT COUNT(*) FROM rpg_identity_migration_receipts")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        }
    }

    private static String[] append(List<String> common, String... tail) {
        var values = new ArrayList<>(common); values.addAll(List.of(tail)); return values.toArray(String[]::new);
    }
}
