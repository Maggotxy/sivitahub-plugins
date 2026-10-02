package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileBackedH2SourceTest {
    @TempDir
    Path temporary;

    @Test
    void resolvedSourceUrlOpensExistingH2ReadOnlyAndCannotCreateOrWrite() throws Exception {
        Path runtime = Files.createDirectory(temporary.resolve("runtime"));
        Path databaseDirectory = Files.createDirectories(runtime.resolve("plugins/RpgCore/data"));
        String writable = "jdbc:h2:file:" + databaseDirectory.resolve("rpg")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        try (var connection = DriverManager.getConnection(writable, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY, payload VARCHAR(16) NOT NULL)");
            statement.execute("INSERT INTO sample VALUES (1, 'preserved')");
        }

        Path targetSecret = temporary.resolve("target.secret");
        Files.writeString(targetSecret, "test-only\n");
        Files.setPosixFilePermissions(targetSecret, PosixFilePermissions.fromString("rw-------"));
        MigrationOptions options = MigrationOptions.parse(new String[]{
                "--source-url", "jdbc:h2:file:./plugins/RpgCore/data/rpg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "--target-url", "jdbc:postgresql://127.0.0.1/test",
                "--target-user", "test",
                "--target-password-file", targetSecret.toString(),
                "--paper-runtime", runtime.toString()
        }, Map.of());

        try (var connection = DriverManager.getConnection(options.sourceUrl(), "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT payload FROM sample WHERE id = 1")) {
            result.next();
            assertEquals("preserved", result.getString(1));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO sample VALUES (2, 'blocked')"));
        } finally {
            options.clearPasswords();
        }
    }
}
