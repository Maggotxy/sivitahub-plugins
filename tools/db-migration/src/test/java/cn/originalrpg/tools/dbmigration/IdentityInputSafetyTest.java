package cn.originalrpg.tools.dbmigration;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

final class IdentityInputSafetyTest {
    @TempDir Path directory;

    @Test void duplicateJsonKeysAndNonCanonicalUuidsAreRejected() throws Exception {
        assertThrows(MigrationException.class, () -> IdentityJson.parse("{\"source_uuid\":\"old\",\"source_uuid\":\"replacement\"}"));
        assertThrows(MigrationException.class, () -> IdentityMapping.uuid("1-1-1-1-1"));
        assertThrows(MigrationException.class, () -> IdentityMapping.uuid("00000000-0000-0000-0000-000000000000"));
        assertThrows(MigrationException.class, () -> IdentityMapping.uuid("12345678-ABCD-1234-ABCD-123456789012"));
    }

    @Test void reviewedReportPublicationNeverReplacesAnotherWriter() throws Exception {
        Path report = directory.resolve("reviewed.json");
        JsonObject first = new JsonObject(); first.addProperty("owner", "first");
        JsonObject second = new JsonObject(); second.addProperty("owner", "second");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var outcomes = workers.invokeAll(List.of(
                    () -> publish(report, first), () -> publish(report, second)));
            int accepted = 0;
            for (var result : outcomes) if (Boolean.TRUE.equals(result.get())) accepted++;
            assertEquals(1, accepted);
        }
        String persisted = Files.readString(report);
        assertThrows(Exception.class, () -> IdentityJson.writeNew(report, new JsonObject()));
        assertEquals(persisted, Files.readString(report));
        assertTrue(IdentityJson.read(report, 4096).has("owner"));
    }

    @Test void planContentTamperingCannotReuseItsReviewedDigest() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            new cn.originalrpg.core.persistence.JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2)
                    .get(fixture.mapping.source()).toCompletableFuture().join();
            var plan = fixture.preview();
            assertTrue(plan.ready());
            Path report = directory.resolve("plan.json"); IdentityJson.writeNew(report, plan.document());
            assertEquals(plan.digest(), IdentityMigrationMain.reviewedDigest(report, fixture.mapping));
            JsonObject changed = plan.document().deepCopy(); changed.addProperty("changed_rows", 0);
            Files.writeString(report, changed.toString());
            assertThrows(MigrationException.class, () -> IdentityMigrationMain.reviewedDigest(report, fixture.mapping));
        }
    }

    @Test void everyExternalProviderMustBeReviewedAndSymlinkedStateIsRejected() throws Exception {
        try (var fixture = new IdentityDatabaseFixture(directory)) {
            JsonObject review = IdentityJson.read(fixture.coverage, 256 * 1024);
            review.getAsJsonArray("providers").remove(0);
            Files.writeString(fixture.coverage, review.toString());
            assertThrows(MigrationException.class, fixture::evidence);
        }
        Path second = Files.createDirectory(directory.resolve("second"));
        try (var fixture = new IdentityDatabaseFixture(second)) {
            Path outside = directory.resolve("hidden-player-state.dat"); Files.writeString(outside, "opaque");
            Files.createSymbolicLink(fixture.runtime.resolve("player-state.dat"), outside);
            assertThrows(MigrationException.class, fixture::evidence);
        }
    }

    @Test void dryRunH2IsActuallyReadOnlyAndUnsafeConnectionModesAreRefused() throws Exception {
        String url = createH2();
        IdentityMigrationOptions options = IdentityMigrationOptions.parse(args(url, "--dry-run", "--plan", directory.resolve("plan.json").toString()), Map.of());
        try (var connection = DriverManager.getConnection(options.url(), "sa", ""); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO sample VALUES (2)"));
        } finally { options.clearPassword(); }
        for (String unsafe : List.of(";AUTO_SERVER=TRUE", ";INIT=CREATE TABLE injected(id INT)", ";IFEXISTS=FALSE", ";USER=other")) {
            assertThrows(MigrationException.class, () -> IdentityMigrationOptions.parse(args(url + unsafe, "--dry-run", "--plan", directory.resolve("plan.json").toString()), Map.of()));
        }
    }

    @Test void explicitApplyConfirmationAndProductionOverrideRefusalCannotBeSkipped() throws Exception {
        String url = createH2();
        assertThrows(MigrationException.class, () -> IdentityMigrationOptions.parse(args(url, "--apply", "--reviewed-plan", directory.resolve("plan.json").toString()), Map.of()));
        assertThrows(MigrationException.class, () -> IdentityMigrationOptions.parse(args(url, "--dry-run", "--apply", "--plan", directory.resolve("plan.json").toString()), Map.of()));
        assertThrows(MigrationException.class, () -> IdentityMigrationOptions.parse(args(url, "--dry-run", "--plan", directory.resolve("plan.json").toString()), Map.of("RPG_DATABASE_URL", "production-override")));
    }

    @Test void passwordsAndUnreviewedConnectionOptionsNeverEnterUrlsOrArguments() throws Exception {
        for (String url : List.of("jdbc:postgresql://user:secret@localhost/game", "jdbc:postgresql://localhost/game?password=secret",
                "jdbc:postgresql://localhost/game?%70assword=secret", "jdbc:postgresql://localhost/game?socketFactory=custom")) {
            var arguments = new ArrayList<>(List.of(args(url, "--dry-run", "--plan", directory.resolve("plan.json").toString())));
            arguments.addAll(List.of("--user", "operator"));
            assertThrows(MigrationException.class, () -> IdentityMigrationOptions.parse(arguments.toArray(String[]::new), Map.of()));
        }
        String h2 = createH2();
        Path secret = directory.resolve("database.secret"); Files.writeString(secret, "private\n");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(MigrationException.class, () -> IdentityMigrationOptions.parse(args(h2, "--dry-run", "--plan", directory.resolve("plan.json").toString(), "--password-file", secret.toString()), Map.of()));
        assertThrows(MigrationException.class, () -> IdentityMigrationOptions.parse(args(h2, "--dry-run", "--plan", directory.resolve("plan.json").toString(), "--password", "private"), Map.of()));
    }

    private String createH2() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("core") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sample (id INTEGER PRIMARY KEY)");
        }
        return url;
    }

    private String[] args(String url, String... tail) {
        var arguments = new ArrayList<>(List.of("--jdbc-url", url, "--paper-runtime", directory.resolve("runtime").toString(),
                "--mapping", directory.resolve("mapping.json").toString(), "--coverage", directory.resolve("coverage.json").toString()));
        arguments.addAll(List.of(tail));
        return arguments.toArray(String[]::new);
    }

    private static boolean publish(Path path, JsonObject document) {
        try { IdentityJson.writeNew(path, document); return true; }
        catch (Exception rejected) { return false; }
    }
}
