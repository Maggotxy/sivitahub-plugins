package cn.originalrpg.tools.dbmigration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record MigrationOptions(
        String sourceUrl,
        String sourceUser,
        char[] sourcePassword,
        String sourceSchema,
        String targetUrl,
        String targetUser,
        char[] targetPassword,
        String targetSchema,
        Path paperRuntime,
        int batchSize,
        boolean dryRun,
        boolean verifyOnly,
        boolean replaceExisting,
        String confirmedTargetCatalog,
        long lockTimeoutMs,
        boolean help
) {
    static final String REPLACE_CONFIRMATION = "RPG_TABLES";
    private static final Pattern SECRET_URL_PARAMETER = Pattern.compile(
            "(?i)(?:^|[?;&])(password|passwd|sslpassword|token|secret)="
    );
    private static final Pattern POSTGRES_USERINFO = Pattern.compile(
            "(?i)^jdbc:postgresql://[^/@:]+:[^/@]+@"
    );

    static MigrationOptions parse(String[] arguments, Map<String, String> environment)
            throws MigrationException {
        Map<String, String> values = new HashMap<>();
        Set<String> flags = new java.util.HashSet<>();
        List<String> args = List.of(arguments);
        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index);
            if (argument.equals("--help") || argument.equals("-h")) {
                flags.add("help");
                continue;
            }
            if (Set.of("--dry-run", "--verify-only", "--replace-existing").contains(argument)) {
                flags.add(argument.substring(2));
                continue;
            }
            if (!argument.startsWith("--")) {
                throw new MigrationException("Unexpected positional argument: " + argument);
            }
            if (index + 1 >= args.size()) {
                throw new MigrationException("Missing value for " + argument);
            }
            String key = argument.substring(2);
            if (!Set.of(
                    "source-url", "source-user", "source-password-file", "source-schema",
                    "target-url", "target-user", "target-password-file", "target-schema",
                    "paper-runtime", "batch-size", "lock-timeout-ms",
                    "confirm-replace", "confirm-target"
            ).contains(key)) {
                throw new MigrationException("Unknown option: " + argument);
            }
            if (values.putIfAbsent(key, args.get(++index)) != null) {
                throw new MigrationException("Option specified more than once: " + argument);
            }
        }

        boolean help = flags.contains("help");
        if (help) {
            return new MigrationOptions("", "", new char[0], "", "", "", new char[0],
                    "", Path.of("."), 500, false, false, false, "",
                    MigrationAdvisoryLock.DEFAULT_TIMEOUT_MS, true);
        }

        String sourceUrl = required(values, environment, "source-url", "SIVITA_MIGRATION_SOURCE_URL");
        String sourceUser = optional(values, environment, "source-user", "SIVITA_MIGRATION_SOURCE_USER", "sa");
        String targetUrl = required(values, environment, "target-url", "SIVITA_MIGRATION_TARGET_URL");
        String targetUser = required(values, environment, "target-user", "SIVITA_MIGRATION_TARGET_USER");
        String sourceSchema = optional(values, environment, "source-schema", "SIVITA_MIGRATION_SOURCE_SCHEMA", "PUBLIC");
        String targetSchema = optional(values, environment, "target-schema", "SIVITA_MIGRATION_TARGET_SCHEMA", "public");
        String runtimeText = required(values, environment, "paper-runtime", "SIVITA_MIGRATION_PAPER_RUNTIME");

        validateIdentifier(sourceSchema, "source schema");
        validateIdentifier(targetSchema, "target schema");
        validateUrl(sourceUrl);
        validateUrl(targetUrl);
        if (!sourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:file:")) {
            throw new MigrationException("Source must be an existing file-backed H2 JDBC URL");
        }
        if (!targetUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")) {
            throw new MigrationException("Target must be a PostgreSQL JDBC URL");
        }
        Path runtime = Path.of(runtimeText).toAbsolutePath().normalize();
        sourceUrl = readOnlyH2Url(resolveH2Path(sourceUrl, runtime));

        char[] sourcePassword = password(
                values.get("source-password-file"), environment.get("SIVITA_MIGRATION_SOURCE_PASSWORD"),
                false, "source"
        );
        char[] targetPassword = password(
                values.get("target-password-file"), environment.get("SIVITA_MIGRATION_TARGET_PASSWORD"),
                true, "target"
        );

        int batchSize = parseBatchSize(values.getOrDefault("batch-size", "500"));
        long lockTimeoutMs = parseLockTimeoutMs(optional(
                values, environment, "lock-timeout-ms", "SIVITA_MIGRATION_LOCK_TIMEOUT_MS",
                Long.toString(MigrationAdvisoryLock.DEFAULT_TIMEOUT_MS)
        ));
        boolean dryRun = flags.contains("dry-run");
        boolean verifyOnly = flags.contains("verify-only");
        boolean replaceExisting = flags.contains("replace-existing");
        if (dryRun && verifyOnly) {
            throw new MigrationException("--dry-run and --verify-only are mutually exclusive");
        }
        if (replaceExisting && verifyOnly) {
            throw new MigrationException("--replace-existing cannot be combined with --verify-only");
        }
        if (replaceExisting && !REPLACE_CONFIRMATION.equals(values.get("confirm-replace"))) {
            throw new MigrationException("--replace-existing requires --confirm-replace " + REPLACE_CONFIRMATION);
        }
        String confirmedTargetCatalog = values.getOrDefault("confirm-target", "");
        if (replaceExisting && confirmedTargetCatalog.isBlank()) {
            throw new MigrationException("--replace-existing requires --confirm-target with the exact PostgreSQL database name");
        }
        if (!replaceExisting && (values.containsKey("confirm-replace") || values.containsKey("confirm-target"))) {
            throw new MigrationException("Replace confirmations are valid only with --replace-existing");
        }

        return new MigrationOptions(
                sourceUrl, sourceUser, sourcePassword, sourceSchema,
                targetUrl, targetUser, targetPassword, targetSchema,
                runtime, batchSize, dryRun, verifyOnly, replaceExisting, confirmedTargetCatalog,
                lockTimeoutMs, false
        );
    }

    void clearPasswords() {
        java.util.Arrays.fill(sourcePassword, '\0');
        java.util.Arrays.fill(targetPassword, '\0');
    }

    private static String required(
            Map<String, String> values, Map<String, String> environment, String option, String variable
    ) throws MigrationException {
        String value = optional(values, environment, option, variable, "");
        if (value.isBlank()) {
            throw new MigrationException("Missing --" + option + " or " + variable);
        }
        return value;
    }

    private static String optional(
            Map<String, String> values, Map<String, String> environment,
            String option, String variable, String fallback
    ) {
        String optionValue = values.get(option);
        if (optionValue != null) return optionValue;
        String environmentValue = environment.get(variable);
        return environmentValue == null ? fallback : environmentValue;
    }

    private static char[] password(String fileText, String environmentValue, boolean required, String label)
            throws MigrationException {
        if (fileText != null && environmentValue != null) {
            throw new MigrationException("Provide the " + label + " password by file or environment, not both");
        }
        String password;
        if (fileText != null) {
            password = readSecretFile(Path.of(fileText), label);
        } else {
            password = environmentValue == null ? "" : environmentValue;
        }
        if (required && password.isEmpty()) {
            throw new MigrationException("Target password is required via a 0600 file or SIVITA_MIGRATION_TARGET_PASSWORD");
        }
        return password.toCharArray();
    }

    private static String readSecretFile(Path file, String label) throws MigrationException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) {
            throw new MigrationException("The " + label + " password path must be a regular non-symlink file");
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(absolute, LinkOption.NOFOLLOW_LINKS);
            EnumSet<PosixFilePermission> forbidden = EnumSet.of(
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE
            );
            if (!java.util.Collections.disjoint(permissions, forbidden)) {
                throw new MigrationException("The " + label + " password file must not grant group/other permissions");
            }
            String text = Files.readString(absolute, StandardCharsets.UTF_8);
            if (text.endsWith("\r\n")) text = text.substring(0, text.length() - 2);
            else if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                throw new MigrationException("The " + label + " password file must contain exactly one line");
            }
            return text;
        } catch (UnsupportedOperationException exception) {
            throw new MigrationException("Cannot verify POSIX permissions for the " + label + " password file", exception);
        } catch (IOException exception) {
            throw new MigrationException("Cannot read the " + label + " password file", exception);
        }
    }

    private static void validateUrl(String url) throws MigrationException {
        if (SECRET_URL_PARAMETER.matcher(url).find() || POSTGRES_USERINFO.matcher(url).find()) {
            throw new MigrationException("JDBC URLs must not embed passwords, tokens, or user-info");
        }
    }

    private static void validateIdentifier(String value, String label) throws MigrationException {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new MigrationException("Invalid " + label + " identifier");
        }
    }

    private static long parseLockTimeoutMs(String value) throws MigrationException {
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new MigrationException("--lock-timeout-ms must be a whole number of milliseconds");
        }
        if (parsed < MigrationAdvisoryLock.MIN_TIMEOUT_MS || parsed > MigrationAdvisoryLock.MAX_TIMEOUT_MS) {
            throw new MigrationException("--lock-timeout-ms must be between "
                    + MigrationAdvisoryLock.MIN_TIMEOUT_MS + " and " + MigrationAdvisoryLock.MAX_TIMEOUT_MS);
        }
        return parsed;
    }

    private static int parseBatchSize(String value) throws MigrationException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 10_000) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new MigrationException("--batch-size must be between 1 and 10000");
        }
    }

    private static String readOnlyH2Url(String url) {
        List<String> additions = new ArrayList<>();
        String upper = url.toUpperCase(Locale.ROOT);
        if (!upper.contains(";IFEXISTS=")) additions.add("IFEXISTS=TRUE");
        if (!upper.contains(";ACCESS_MODE_DATA=")) additions.add("ACCESS_MODE_DATA=r");
        return additions.isEmpty() ? url : url + ";" + String.join(";", additions);
    }

    private static String resolveH2Path(String url, Path runtime) throws MigrationException {
        String prefix = "jdbc:h2:file:";
        int separator = url.indexOf(';', prefix.length());
        String databasePath = separator < 0 ? url.substring(prefix.length()) : url.substring(prefix.length(), separator);
        String settings = separator < 0 ? "" : url.substring(separator);
        if (databasePath.isBlank() || databasePath.startsWith("~")) {
            throw new MigrationException("H2 source path must be an explicit filesystem path");
        }
        Path path = Path.of(databasePath);
        if (!path.isAbsolute()) path = runtime.resolve(path);
        return prefix + path.toAbsolutePath().normalize() + settings;
    }
}
