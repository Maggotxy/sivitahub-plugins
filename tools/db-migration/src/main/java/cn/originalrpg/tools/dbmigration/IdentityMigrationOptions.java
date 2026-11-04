package cn.originalrpg.tools.dbmigration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit offline inputs; this command never inherits the running server's database override. */
record IdentityMigrationOptions(String url, String user, char[] password, String schema, Path runtime,
                                Path mapping, Path coverage, Path plan, Path reviewedPlan, Path coreH2Base,
                                String confirmedDatabase, boolean apply, boolean help) {
    private static final Set<String> VALUES = Set.of("jdbc-url", "user", "password-file", "schema", "paper-runtime",
            "mapping", "coverage", "plan", "reviewed-plan", "confirm-database");

    static IdentityMigrationOptions parse(String[] arguments, Map<String, String> environment) throws MigrationException {
        Map<String, String> values = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (Set.of("--help", "-h", "--dry-run", "--apply").contains(argument)) {
                if (!flags.add(argument)) throw new MigrationException("Repeated identity option: " + argument);
                continue;
            }
            if (!argument.startsWith("--") || !VALUES.contains(argument.substring(2))) {
                throw new MigrationException("Unknown identity option (passwords are never accepted on the command line)");
            }
            if (++index >= arguments.length || arguments[index].startsWith("--")
                    || values.putIfAbsent(argument.substring(2), arguments[index]) != null) {
                throw new MigrationException("Missing value or repeated identity option: " + argument);
            }
        }
        if (flags.contains("--help") || flags.contains("-h")) {
            return new IdentityMigrationOptions("", "", new char[0], "", null, null, null, null, null, null, "", false, true);
        }
        if (!environment.getOrDefault("RPG_DATABASE_URL", "").isBlank()) {
            throw new MigrationException("Unset RPG_DATABASE_URL; identity migration requires its own explicit --jdbc-url");
        }
        if (flags.contains("--dry-run") == flags.contains("--apply")) throw new MigrationException("Select exactly one of --dry-run or --apply");
        boolean apply = flags.contains("--apply");
        Path runtime = path(required(values, "paper-runtime"));
        Path mapping = path(required(values, "mapping"));
        Path coverage = path(required(values, "coverage"));
        Path plan = apply ? null : path(required(values, "plan"));
        Path reviewed = apply ? path(required(values, "reviewed-plan")) : null;
        if (apply && values.containsKey("plan") || !apply && (values.containsKey("reviewed-plan") || values.containsKey("confirm-database"))) {
            throw new MigrationException("Plan output belongs to dry-run; reviewed-plan and confirm-database belong to apply");
        }
        for (Path control : new Path[]{mapping, coverage, plan == null ? reviewed : plan}) {
            if (control.startsWith(runtime)) throw new MigrationException("Identity control documents must be outside the Paper runtime");
        }
        String url = required(values, "jdbc-url");
        String schema;
        Path coreH2Base = null;
        String user;
        if (url.startsWith("jdbc:h2:file:")) {
            String[] parts = url.substring("jdbc:h2:file:".length()).split(";", -1);
            if (parts[0].isBlank() || parts[0].startsWith("~") || parts[0].chars().anyMatch(Character::isISOControl)) {
                throw new MigrationException("H2 requires an explicit filesystem database path");
            }
            coreH2Base = Path.of(parts[0]);
            if (!coreH2Base.isAbsolute()) coreH2Base = runtime.resolve(coreH2Base);
            coreH2Base = coreH2Base.toAbsolutePath().normalize();
            Path databaseFile = Path.of(coreH2Base + ".mv.db");
            if (!Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(databaseFile)) {
                throw new MigrationException("Identity migration requires an existing regular H2 .mv.db file");
            }
            Map<String, String> settings = new java.util.TreeMap<>();
            for (int index = 1; index < parts.length; index++) {
                String[] pair = parts[index].split("=", 2);
                if (pair.length != 2) throw new MigrationException("Invalid H2 identity URL setting");
                String key = pair[0].toUpperCase(Locale.ROOT), value = pair[1].toUpperCase(Locale.ROOT);
                boolean allowed = key.equals("MODE") && value.equals("POSTGRESQL")
                        || key.equals("DATABASE_TO_LOWER") && Set.of("TRUE", "FALSE").contains(value)
                        || key.equals("IFEXISTS") && value.equals("TRUE")
                        || key.equals("DB_CLOSE_ON_EXIT") && value.equals("FALSE")
                        || key.equals("ACCESS_MODE_DATA") && value.equals("R") && !apply;
                if (!allowed || settings.putIfAbsent(key, value) != null) {
                    throw new MigrationException("Unsafe, duplicate or unsupported H2 setting; remote/AUTO_SERVER/INIT modes are prohibited");
                }
            }
            settings.put("IFEXISTS", "TRUE");
            if (!apply) settings.put("ACCESS_MODE_DATA", "R");
            url = "jdbc:h2:file:" + coreH2Base;
            for (var setting : settings.entrySet()) url += ";" + setting.getKey() + "=" + setting.getValue();
            schema = values.getOrDefault("schema", "PUBLIC");
            user = values.getOrDefault("user", "sa");
        } else if (url.startsWith("jdbc:postgresql://")) {
            validatePostgresUrl(url);
            schema = values.getOrDefault("schema", "public");
            user = required(values, "user");
        } else throw new MigrationException("Use existing embedded jdbc:h2:file: or jdbc:postgresql://; remote H2 and memory databases are prohibited");
        if (!schema.matches("[A-Za-z_][A-Za-z_0-9]{0,62}")) throw new MigrationException("Invalid database schema identifier");
        String confirmation = apply ? required(values, "confirm-database") : "";
        char[] password = readPassword(values.get("password-file"), environment.get("SIVITA_IDENTITY_DATABASE_PASSWORD"));
        return new IdentityMigrationOptions(url, user, password, schema, runtime, mapping, coverage, plan, reviewed,
                coreH2Base, confirmation, apply, false);
    }

    void clearPassword() { Arrays.fill(password, '\0'); }

    private static String required(Map<String, String> values, String key) throws MigrationException {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new MigrationException("Required identity option: --" + key);
        return value;
    }

    private static Path path(String text) throws MigrationException {
        try { return Path.of(text).toAbsolutePath().normalize(); }
        catch (java.nio.file.InvalidPathException error) { throw new MigrationException("Invalid identity path", error); }
    }

    private static void validatePostgresUrl(String url) throws MigrationException {
        try {
            java.net.URI uri = java.net.URI.create(url.substring(5));
            if (uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null
                    || uri.getPath() == null || uri.getPath().length() <= 1) throw new IllegalArgumentException();
            Set<String> seen = new HashSet<>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                String key = java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (pair.length != 2 || !seen.add(key) || !Set.of("sslmode", "connecttimeout", "sockettimeout", "currentschema").contains(key)) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (IllegalArgumentException error) {
            throw new MigrationException("PostgreSQL URL must name one database without credentials; only sslmode/connectTimeout/socketTimeout/currentSchema are accepted", error);
        }
    }

    private static char[] readPassword(String fileText, String environmentPassword) throws MigrationException {
        if (fileText != null && environmentPassword != null) throw new MigrationException("Use a password file or SIVITA_IDENTITY_DATABASE_PASSWORD, never both");
        if (fileText == null) return (environmentPassword == null ? "" : environmentPassword).toCharArray();
        Path file = path(fileText);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) > 16_384) {
                throw new MigrationException("Password file must be a bounded regular non-symlink file");
            }
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))) {
                throw new MigrationException("Password file must have no group/other permissions (use 0600)");
            }
            String password = Files.readString(file, StandardCharsets.UTF_8);
            if (password.endsWith("\r\n")) password = password.substring(0, password.length() - 2);
            else if (password.endsWith("\n")) password = password.substring(0, password.length() - 1);
            if (password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) throw new MigrationException("Password file must contain one line");
            return password.toCharArray();
        } catch (IOException | UnsupportedOperationException error) { throw new MigrationException("Cannot securely read password file", error); }
    }
}
