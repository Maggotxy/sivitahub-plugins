package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaManifestContractTest {
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (rpg_[a-z_]+)");
    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS (rpg_[a-z_]+) \\((.*?)\\)\\s*\\\"\\\"\\\"",
            Pattern.DOTALL
    );
    private static final Pattern INLINE_UNIQUE = Pattern.compile("(?m)^\\s*([a-z_]+)\\s+[^,\\n]*\\bUNIQUE\\b");
    private static final Pattern TABLE_UNIQUE = Pattern.compile(
            "(?i)(?:CONSTRAINT\\s+[a-z_]+\\s+)?UNIQUE\\s*\\(([^)]+)\\)"
    );
    private static final Pattern INDEX_UNIQUE = Pattern.compile(
            "CREATE UNIQUE INDEX IF NOT EXISTS [a-z_]+ ON (rpg_[a-z_]+) \\(([^)]+)\\)"
    );
    private static final Pattern INDEX_NON_UNIQUE = Pattern.compile(
            "CREATE INDEX IF NOT EXISTS [a-z_]+ ON (rpg_[a-z_]+) \\(([^)]+)\\)"
    );

    @Test
    void manifestMatchesEveryRpgCoreTableAndUniqueKey() throws Exception {
        Path root = Path.of(System.getProperty("sivita.projectRoot"));
        String source = Files.readString(root.resolve(
                "plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java"
        ));
        String normalizedSource = source.replaceAll("\"\\s*\\+\\s*\"", "");

        List<String> tables = new ArrayList<>();
        Matcher tableMatcher = TABLE.matcher(source);
        while (tableMatcher.find()) tables.add(tableMatcher.group(1));
        assertEquals(tables, SchemaManifest.tables(), "Update migration order whenever RpgCore adds a table");

        Set<String> schemaKeys = new LinkedHashSet<>();
        Matcher blocks = TABLE_BLOCK.matcher(source);
        while (blocks.find()) {
            Matcher inline = INLINE_UNIQUE.matcher(blocks.group(2));
            while (inline.find()) schemaKeys.add(blocks.group(1) + ":" + inline.group(1));
            Matcher tableUnique = TABLE_UNIQUE.matcher(blocks.group(2));
            while (tableUnique.find()) {
                schemaKeys.add(blocks.group(1) + ":" + tableUnique.group(1).replaceAll("\\s+", ""));
            }
        }
        Matcher indexes = INDEX_UNIQUE.matcher(normalizedSource);
        while (indexes.find()) {
            String columns = indexes.group(2).replaceAll("\\s+", "");
            schemaKeys.add(indexes.group(1) + ":" + columns);
        }

        Set<String> manifestKeys = new LinkedHashSet<>();
        SchemaManifest.uniqueKeys().forEach((table, keys) -> keys.forEach(
                key -> manifestKeys.add(table + ":" + String.join(",", key))
        ));
        assertEquals(schemaKeys, manifestKeys, "Every RpgCore unique key must be explicitly verified");

        Set<String> schemaIndexes = new LinkedHashSet<>();
        Matcher nonUniqueIndexes = INDEX_NON_UNIQUE.matcher(normalizedSource);
        while (nonUniqueIndexes.find()) {
            schemaIndexes.add(nonUniqueIndexes.group(1) + ":"
                    + nonUniqueIndexes.group(2).replaceAll("\\s+", ""));
        }

        Set<String> manifestIndexes = new LinkedHashSet<>();
        SchemaManifest.requiredIndexes().forEach((table, tableIndexes) -> tableIndexes.forEach(
                index -> manifestIndexes.add(table + ":" + String.join(",", index))
        ));
        assertEquals(
                schemaIndexes,
                manifestIndexes,
                "Every RpgCore non-unique runtime index must be explicitly verified"
        );
    }
}
