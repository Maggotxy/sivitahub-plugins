package cn.originalrpg.world;

import cn.originalrpg.api.model.FieldJournalEntry;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Validates the reviewed content against the live server registry before exposing goals. */
final class FieldJournalCatalog {
    private final Map<FieldJournalEntry, Set<String>> expectedCriteria;

    private FieldJournalCatalog(Map<FieldJournalEntry, Set<String>> expectedCriteria) {
        this.expectedCriteria = Map.copyOf(expectedCriteria);
    }

    static FieldJournalCatalog load(InputStream input) {
        Objects.requireNonNull(input, "field-journal.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        if (config.getInt("schema-version") != 1 || !"26.2".equals(config.getString("minecraft-version"))) {
            throw new IllegalStateException("Unsupported field journal catalog version");
        }
        ConfigurationSection entries = Objects.requireNonNull(config.getConfigurationSection("entries"), "entries");
        Set<String> expectedIds = new HashSet<>();
        Map<FieldJournalEntry, Set<String>> criteria = new EnumMap<>(FieldJournalEntry.class);
        for (FieldJournalEntry entry : FieldJournalEntry.values()) {
            expectedIds.add(entry.id());
            String key = entries.getString(entry.id() + ".advancement");
            java.util.List<String> names = entries.getStringList(entry.id() + ".criteria");
            if (!entry.advancementKey().equals(key) || names.isEmpty()
                    || names.stream().anyMatch(String::isBlank) || new HashSet<>(names).size() != names.size()) {
                throw new IllegalStateException("Invalid field journal content for " + entry.id());
            }
            criteria.put(entry, Set.copyOf(names));
        }
        if (!entries.getKeys(false).equals(expectedIds)) {
            throw new IllegalStateException("Field journal catalog and public entry IDs differ");
        }
        return new FieldJournalCatalog(criteria);
    }

    Map<FieldJournalEntry, Advancement> resolve(
            Function<NamespacedKey, Advancement> lookup, Consumer<String> reportDisabled
    ) {
        Map<FieldJournalEntry, Advancement> available = new EnumMap<>(FieldJournalEntry.class);
        for (FieldJournalEntry entry : FieldJournalEntry.values()) {
            Advancement advancement = lookup.apply(Objects.requireNonNull(NamespacedKey.fromString(entry.advancementKey())));
            if (advancement == null || !entry.advancementKey().equals(advancement.getKey().toString())) {
                reportDisabled.accept("Field journal goal " + entry.id() + " disabled: missing advancement " + entry.advancementKey());
                continue;
            }
            if (!expectedCriteria.get(entry).equals(Set.copyOf(advancement.getCriteria()))) {
                reportDisabled.accept("Field journal goal " + entry.id() + " disabled: advancement criteria differ from reviewed Minecraft 26.2 data");
                continue;
            }
            available.put(entry, advancement);
        }
        return Map.copyOf(available);
    }

    Set<String> criteria(FieldJournalEntry entry) {
        return expectedCriteria.get(entry);
    }
}
