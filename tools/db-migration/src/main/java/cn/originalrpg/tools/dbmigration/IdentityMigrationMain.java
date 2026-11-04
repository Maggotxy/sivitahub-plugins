package cn.originalrpg.tools.dbmigration;

import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;

/** Offline command, never a Bukkit command or runtime service. */
final class IdentityMigrationMain {
    private IdentityMigrationMain() { }

    static int run(String[] arguments, Map<String, String> environment) {
        IdentityMigrationOptions options = null;
        try {
            options = IdentityMigrationOptions.parse(arguments, environment);
            if (options.help()) { usage(); return 0; }
            IdentityExternalCoverage.requireNoLocalJavaRuntime(options.runtime());
            IdentityMapping mapping = IdentityMapping.read(options.mapping());
            mapping.verifyEvidenceUnchanged();
            IdentityMigrationOptions selected = options;
            IdentityMigrationEngine.EvidenceProbe probe = () -> {
                IdentityExternalCoverage.requireNoLocalJavaRuntime(selected.runtime());
                return IdentityExternalCoverage.scan(selected.runtime(), selected.coverage(), mapping, selected.coreH2Base());
            };
            Properties properties = new Properties();
            properties.setProperty("user", options.user());
            properties.setProperty("password", new String(options.password()));
            if (options.url().startsWith("jdbc:postgresql:")) {
                properties.setProperty("ApplicationName", "sivita-offline-identity-migration");
            }
            try (Connection connection = DriverManager.getConnection(options.url(), properties)) {
                IdentityMigrationEngine engine = new IdentityMigrationEngine();
                if (!options.apply()) {
                    var plan = engine.preview(connection, options.schema(), mapping, probe.inspect());
                    IdentityJson.writeNew(options.plan(), plan.document());
                    System.out.println("[PLAN] scope=CORE_DATABASE_ONLY ready=" + plan.ready() + " blockers=" + plan.blockers().size()
                            + " sha256=" + plan.digest());
                    System.out.println("[PLAN] Full server cutover is not verified; inspect every column and external adapter gate in " + options.plan());
                    return plan.ready() ? 0 : 5;
                }
                if (!options.confirmedDatabase().equals(connection.getCatalog())) {
                    throw new MigrationException("--confirm-database must equal the exact connected catalog; no rows were changed");
                }
                String reviewed = reviewedDigest(options.reviewedPlan(), mapping);
                var result = engine.apply(connection, options.schema(), mapping, reviewed, probe);
                System.out.println("[DONE] scope=CORE_DATABASE_ONLY outcome=" + result.outcome() + " changed_rows=" + result.changedRows()
                        + " plan_sha256=" + result.planDigest() + " operation_id=" + mapping.operationId());
            } finally { properties.clear(); }
            return 0;
        } catch (Exception error) {
            String message = error.getMessage();
            // SQL exceptions may contain row payloads or credentialed URLs. Expose
            // controlled domain messages only; never print a JDBC cause/stack trace.
            System.err.println("[FAIL] " + (error instanceof MigrationException && message != null ? message
                    : "Identity migration could not complete; no success is asserted. Check permissions and the reviewed offline inputs."));
            return 2;
        } finally { if (options != null) options.clearPassword(); }
    }

    static String reviewedDigest(java.nio.file.Path file, IdentityMapping mapping) throws Exception {
        JsonObject plan = IdentityJson.read(file, 16 * 1024 * 1024);
        String digest = IdentityJson.text(plan, "plan_sha256", 64);
        JsonObject content = plan.deepCopy(); content.remove("plan_sha256");
        if (!digest.matches("[0-9a-f]{64}") || !digest.equals(IdentityJson.hash(IdentityJson.canonical(content)))
                || !IdentityJson.text(plan, "mapping_sha256", 64).equals(mapping.digest())
                || !IdentityJson.text(plan, "operation_id", 36).equals(mapping.operationId().toString())
                || !plan.has("core_apply_ready") || !plan.get("core_apply_ready").isJsonPrimitive()
                || !plan.get("core_apply_ready").getAsJsonPrimitive().isBoolean() || !plan.get("core_apply_ready").getAsBoolean()) {
            throw new MigrationException("Reviewed identity plan is modified, blocked, or belongs to a different verified mapping");
        }
        return digest;
    }

    private static void usage() {
        System.out.println("""
                SivitaHub offline identity migration — explicit one-to-one Core database subset
                sivita-db-migration identity --dry-run|--apply [options]

                  --jdbc-url URL             Existing embedded H2 file or PostgreSQL database; no credentials
                  --user USER                Database user (H2 default: sa)
                  --password-file FILE       0600 file, or SIVITA_IDENTITY_DATABASE_PASSWORD
                  --schema NAME              H2 default PUBLIC, PostgreSQL default public
                  --paper-runtime DIRECTORY  Actual stopped runtime; every external file is inventoried
                  --mapping FILE             Reviewed UUID mapping and ownership-evidence SHA-256
                  --coverage FILE            Explicit review of every external identity provider
                  --plan FILE                New immutable JSON report; required for --dry-run
                  --reviewed-plan FILE       Original ready report; required for --apply
                  --confirm-database NAME    Exact connected catalog from the report; required for --apply

                All nodes and external writers must be stopped and backed up. This tool creates
                no schema, infers no UUIDs, merges no accounts, rewrites no signed items or opaque
                player files, and provides no force/ignore-adapter option. Source and target
                cannot participate in an earlier identity receipt. Repeating an uncertain apply
                with the same operation and original report checks the atomic receipt.
                See docs/operations/UUID_IDENTITY_MIGRATION.md before scheduling a cutover.
                """);
    }
}
