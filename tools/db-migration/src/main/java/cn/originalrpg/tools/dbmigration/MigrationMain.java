package cn.originalrpg.tools.dbmigration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

public final class MigrationMain {
    private MigrationMain() {
    }

    public static void main(String[] arguments) {
        int code = run(arguments, System.getenv());
        if (code != 0) System.exit(code);
    }

    static int run(String[] arguments, Map<String, String> environment) {
        MigrationOptions options = null;
        try {
            options = MigrationOptions.parse(arguments, environment);
            if (options.help()) {
                printUsage();
                return 0;
            }
            PaperStopGate.requireStopped(options.paperRuntime());
            System.out.println("[OK] Paper stop gate passed for the selected runtime");

            Class.forName("org.h2.Driver");
            Class.forName("org.postgresql.Driver");
            try (
                    Connection source = connect(options.sourceUrl(), options.sourceUser(), options.sourcePassword());
                    Connection target = connect(options.targetUrl(), options.targetUser(), options.targetPassword())
            ) {
                requireProduct(source, "h2", "source");
                requireProduct(target, "postgresql", "target");
                requireConfirmedTarget(target, options);
                System.out.println("[OK] Source H2 and target PostgreSQL connections established (credentials redacted)");
                MigrationEngine engine = new MigrationEngine(System.out::println);
                MigrationEngine.Result result = engine.run(source, target, new MigrationEngine.Request(
                        options.sourceSchema(), options.targetSchema(), options.batchSize(),
                        options.dryRun(), options.verifyOnly(), options.replaceExisting()
                ));
                System.out.println(
                        "[DONE] outcome=" + result.outcome() + " tables=" + result.tables() + " rows=" + result.rows()
                );
            }
            return 0;
        } catch (MigrationException exception) {
            System.err.println("[FAIL] " + safeMessage(exception));
            return 2;
        } catch (ClassNotFoundException exception) {
            System.err.println("[FAIL] JDBC driver is missing from the migration distribution");
            return 3;
        } catch (SQLException exception) {
            System.err.println("[FAIL] Database connection or SQL operation failed: " + safeMessage(exception));
            return 4;
        } finally {
            if (options != null) options.clearPasswords();
        }
    }

    private static Connection connect(String url, String user, char[] password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", new String(password));
        if (url.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:postgresql:")) {
            properties.setProperty("ApplicationName", "sivita-h2-postgresql-migration");
        }
        return DriverManager.getConnection(url, properties);
    }

    private static void requireProduct(Connection connection, String expected, String label)
            throws SQLException, MigrationException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (!product.toLowerCase(java.util.Locale.ROOT).contains(expected)) {
            throw new MigrationException("Unexpected " + label + " database product: " + product);
        }
    }

    private static void requireConfirmedTarget(Connection target, MigrationOptions options)
            throws SQLException, MigrationException {
        if (!options.replaceExisting()) return;
        String actual = target.getCatalog();
        if (actual == null || !actual.equals(options.confirmedTargetCatalog())) {
            throw new MigrationException(
                    "--confirm-target does not match the connected PostgreSQL database; no rows were changed"
            );
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return throwable.getClass().getSimpleName();
        return message
                .replaceAll("(?i)(password|passwd|sslpassword)=([^;&\\s]+)", "$1=<redacted>")
                .replaceAll("(?i)(jdbc:postgresql://)[^/@:]+:[^/@]+@", "$1<redacted>@");
    }

    private static void printUsage() {
        System.out.println("""
                SivitaHub H2 -> PostgreSQL RPG data migration

                Required (option or matching SIVITA_MIGRATION_* environment variable):
                  --source-url JDBC_URL          Existing jdbc:h2:file: URL
                  --source-user USER             Defaults to sa
                  --target-url JDBC_URL          jdbc:postgresql: URL, without credentials
                  --target-user USER
                  --paper-runtime DIRECTORY      Paper runtime whose process must be stopped

                Passwords (never accepted directly on the command line):
                  --source-password-file FILE    0600 regular file; H2 defaults to empty
                  --target-password-file FILE    0600 regular file; required

                Policy:
                  --dry-run                      Inspect and verify without writing
                  --verify-only                  Require an already migrated exact match
                  --replace-existing             Replace all target rpg_* rows transactionally
                  --confirm-replace RPG_TABLES   Mandatory with --replace-existing
                  --confirm-target DATABASE      Exact connected database; mandatory for replacement
                  --batch-size N                 1..10000, default 500
                  --source-schema NAME           Default PUBLIC
                  --target-schema NAME           Default public

                The tool never creates schema. Initialize PostgreSQL with the exact RpgCore build,
                stop every Paper node, then run dry-run before the real copy.
                """);
    }
}
