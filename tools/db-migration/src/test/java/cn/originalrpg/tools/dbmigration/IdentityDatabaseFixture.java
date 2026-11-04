package cn.originalrpg.tools.dbmigration;

import cn.originalrpg.core.persistence.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Production Database.migrate(), never a miniature hand-written identity schema. */
final class IdentityDatabaseFixture implements AutoCloseable {
    final Database database;
    final DataSource dataSource;
    final Path runtime;
    final Path coverage;
    final Path mappingFile;
    final Path coreH2Base;
    final IdentityMapping mapping;
    final String schema;
    final boolean postgres;
    private final String url;
    private final String user;
    private final String password;

    IdentityDatabaseFixture(Path directory) throws Exception { this(directory, false); }

    IdentityDatabaseFixture(Path directory, boolean fileBackedH2) throws Exception {
        for (String variable : new String[]{"RPG_DATABASE_URL", "RPG_DATABASE_USER", "RPG_DATABASE_PASSWORD"}) {
            if (!System.getenv().getOrDefault(variable, "").isBlank()) {
                throw new IllegalStateException("Identity tests refuse runtime database overrides; use SIVITA_NETWORK_TEST_POSTGRES_*");
            }
        }
        url = System.getenv("SIVITA_NETWORK_TEST_POSTGRES_URL");
        user = System.getenv().getOrDefault("SIVITA_NETWORK_TEST_POSTGRES_USER", "postgres");
        password = System.getenv().getOrDefault("SIVITA_NETWORK_TEST_POSTGRES_PASSWORD", "");
        postgres = !fileBackedH2 && url != null && !url.isBlank();
        schema = postgres ? "sivita_identity_" + UUID.randomUUID().toString().replace("-", "") : "public";
        runtime = directory.resolve("runtime");
        Files.createDirectories(runtime.resolve("plugins"));
        Files.writeString(runtime.resolve("server.properties"), "online-mode=true\n");
        coreH2Base = fileBackedH2 ? runtime.resolve("plugins/RpgCore/data/rpg") : null;
        if (fileBackedH2) Files.createDirectories(coreH2Base.getParent());
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", (fileBackedH2 ? "jdbc:h2:file:" + coreH2Base : "jdbc:h2:mem:identity_" + UUID.randomUUID())
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        config.set("database.username", "sa");
        if (postgres) {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
            config.set("database.jdbc-url", url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            config.set("database.username", user);
            config.set("database.password", password);
        }
        config.set("database.maximum-pool-size", 4);
        Database opened = null;
        try {
            opened = Database.open(config);
            opened.migrate();
            database = opened;
            dataSource = database.dataSource();
        } catch (Exception error) {
            if (opened != null) opened.close();
            dropSchema();
            throw error;
        }
        Path proof = directory.resolve("ownership-review.txt");
        Files.writeString(proof, "Synthetic integration-test identity ownership proof; never deployment evidence.\n");
        mappingFile = directory.resolve("mapping.json");
        JsonObject json = new JsonObject();
        json.addProperty("schema_version", 1);
        json.addProperty("operation_id", UUID.randomUUID().toString());
        json.addProperty("source_uuid", UUID.randomUUID().toString());
        json.addProperty("target_uuid", UUID.randomUUID().toString());
        json.addProperty("source_label", "fixture-old");
        json.addProperty("target_label", "fixture-authenticated");
        json.addProperty("verified_by", "integration-test");
        json.addProperty("evidence_file", proof.getFileName().toString());
        json.addProperty("evidence_sha256", IdentityJson.fileHash(proof));
        Files.writeString(mappingFile, json.toString());
        mapping = IdentityMapping.read(mappingFile);
        coverage = directory.resolve("coverage.json");
        JsonObject external = new JsonObject();
        external.addProperty("schema_version", 1);
        external.addProperty("source_uuid", mapping.source().toString());
        external.addProperty("target_uuid", mapping.target().toString());
        external.addProperty("verified_by", mapping.verifiedBy());
        external.addProperty("evidence_sha256", mapping.evidenceSha256());
        JsonArray providers = new JsonArray();
        for (String id : new java.util.TreeSet<>(IdentityExternalCoverage.PROVIDERS)) {
            JsonObject provider = new JsonObject();
            provider.addProperty("id", id); provider.addProperty("state", "ABSENT");
            provider.addProperty("note", "Disposable database-only fixture has no external player identity state.");
            providers.add(provider);
        }
        external.add("providers", providers);
        Files.writeString(coverage, external.toString());
    }

    IdentityExternalCoverage.Evidence evidence() throws Exception {
        return IdentityExternalCoverage.scan(runtime, coverage, mapping, coreH2Base);
    }

    IdentityMigrationEngine.Plan preview() throws Exception {
        try (var connection = dataSource.getConnection()) {
            return new IdentityMigrationEngine().preview(connection, schema, mapping, evidence());
        }
    }

    IdentityMigrationEngine.Result apply(IdentityMigrationEngine.Plan plan) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return new IdentityMigrationEngine().apply(connection, schema, mapping, plan.digest(), this::evidence);
        }
    }

    void execute(String sql, Object... parameters) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            statement.executeUpdate();
            connection.commit();
        }
    }

    String value(String sql, Object... parameters) throws SQLException {
        try (var connection = dataSource.getConnection(); var query = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) query.setObject(index + 1, parameters[index]);
            try (var result = query.executeQuery()) { if (!result.next()) return null; return result.getString(1); }
        }
    }

    long count(String table) throws SQLException {
        if (!table.matches("rpg_[a-z_]+")) throw new IllegalArgumentException();
        return Long.parseLong(value("SELECT COUNT(*) FROM " + table));
    }

    private void dropSchema() throws SQLException {
        if (!postgres) return;
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Override public void close() throws SQLException { database.close(); dropSchema(); }
}
