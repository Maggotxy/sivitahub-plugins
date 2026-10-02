package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationOptionsTest {
    @TempDir
    Path temporary;

    @Test
    void readsPasswordFromOwnerOnlyFileWithoutPuttingItInArguments() throws Exception {
        Path password = secret("target.secret", "correct horse battery staple\n", "rw-------");
        MigrationOptions options = MigrationOptions.parse(arguments(password), Map.of());
        assertArrayEquals("correct horse battery staple".toCharArray(), options.targetPassword());
        assertTrue(options.sourceUrl().contains("IFEXISTS=TRUE"));
        assertTrue(options.sourceUrl().contains("ACCESS_MODE_DATA=r"));
        assertEquals(500, options.batchSize());
        options.clearPasswords();
    }

    @Test
    void rejectsGroupReadablePasswordFile() throws Exception {
        Path password = secret("unsafe.secret", "not-safe\n", "rw-r-----");
        MigrationException error = assertThrows(
                MigrationException.class,
                () -> MigrationOptions.parse(arguments(password), Map.of())
        );
        assertTrue(error.getMessage().contains("group/other"));
    }

    @Test
    void rejectsEmbeddedJdbcCredentials() throws Exception {
        Path password = secret("target.secret", "safe-test-only\n", "rw-------");
        List<String> args = new ArrayList<>(java.util.Arrays.asList(arguments(password)));
        int targetUrl = args.indexOf("--target-url") + 1;
        args.set(targetUrl, "jdbc:postgresql://user:secret@127.0.0.1/rpg");
        assertThrows(
                MigrationException.class,
                () -> MigrationOptions.parse(args.toArray(String[]::new), Map.of())
        );
    }

    @Test
    void replaceRequiresExplicitConfirmationToken() throws Exception {
        Path password = secret("target.secret", "safe-test-only\n", "rw-------");
        List<String> args = new ArrayList<>(java.util.Arrays.asList(arguments(password)));
        args.add("--replace-existing");
        MigrationException error = assertThrows(
                MigrationException.class,
                () -> MigrationOptions.parse(args.toArray(String[]::new), Map.of())
        );
        assertTrue(error.getMessage().contains("--confirm-replace"));
        args.add("--confirm-replace");
        args.add(MigrationOptions.REPLACE_CONFIRMATION);
        assertThrows(
                MigrationException.class,
                () -> MigrationOptions.parse(args.toArray(String[]::new), Map.of())
        );
        args.add("--confirm-target");
        args.add("rpg");
        assertTrue(MigrationOptions.parse(args.toArray(String[]::new), Map.of()).replaceExisting());
    }

    @Test
    void lockTimeoutDefaultsToThirtySecondsAndIsBounded() throws Exception {
        Path password = secret("target.secret", "safe-test-only\n", "rw-------");
        List<String> args = new ArrayList<>(java.util.Arrays.asList(arguments(password)));
        assertEquals(30_000L, MigrationOptions.parse(args.toArray(String[]::new), Map.of()).lockTimeoutMs());

        List<String> custom = new ArrayList<>(args);
        custom.add("--lock-timeout-ms");
        custom.add("45000");
        assertEquals(45_000L, MigrationOptions.parse(custom.toArray(String[]::new), Map.of()).lockTimeoutMs());

        for (String invalid : List.of("999", "300001", "abc", "1.5")) {
            List<String> bad = new ArrayList<>(args);
            bad.add("--lock-timeout-ms");
            bad.add(invalid);
            MigrationException error = assertThrows(
                    MigrationException.class,
                    () -> MigrationOptions.parse(bad.toArray(String[]::new), Map.of()),
                    "lock timeout must be rejected: " + invalid
            );
            assertTrue(error.getMessage().contains("--lock-timeout-ms"), error.getMessage());
        }
    }

    @Test
    void lockTimeoutCanComeFromTheEnvironment() throws Exception {
        Path password = secret("target.secret", "safe-test-only\n", "rw-------");
        MigrationOptions options = MigrationOptions.parse(
                arguments(password),
                Map.of("SIVITA_MIGRATION_LOCK_TIMEOUT_MS", "60000")
        );
        assertEquals(60_000L, options.lockTimeoutMs());
        options.clearPasswords();
    }

    private String[] arguments(Path password) {
        return new String[]{
                "--source-url", "jdbc:h2:file:" + temporary.resolve("source"),
                "--target-url", "jdbc:postgresql://127.0.0.1:5432/rpg",
                "--target-user", "migration",
                "--target-password-file", password.toString(),
                "--paper-runtime", temporary.toString()
        };
    }

    private Path secret(String name, String content, String permissions) throws Exception {
        Path file = temporary.resolve(name);
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }
}
