package cn.originalrpg.api.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Curated permanent collection goals backed by vanilla advancement facts.
 * Partial progress remains owned by Minecraft; these IDs identify display-only
 * journal stamps and never grant currency, equipment or combat modifiers.
 */
public enum FieldJournalEntry {
    OVERWORLD_EXPLORER("overworld-explorer", "minecraft:adventure/adventuring_time"),
    NETHER_EXPLORER("nether-explorer", "minecraft:nether/explore_nether"),
    NATURALIST("naturalist", "minecraft:husbandry/bred_all_animals"),
    FORAGER("forager", "minecraft:husbandry/balanced_diet"),
    VILLAGE_DEFENDER("village-defender", "minecraft:adventure/hero_of_the_village"),
    ARMOR_ARTISAN("armor-artisan", "minecraft:adventure/trim_with_all_exclusive_armor_patterns");

    private final String id;
    private final String advancementKey;

    FieldJournalEntry(String id, String advancementKey) {
        this.id = id;
        this.advancementKey = advancementKey;
    }

    public String id() {
        return id;
    }

    public String advancementKey() {
        return advancementKey;
    }

    public static Optional<FieldJournalEntry> fromId(String id) {
        return Arrays.stream(values()).filter(entry -> entry.id.equals(id)).findFirst();
    }

    public static Optional<FieldJournalEntry> fromAdvancementKey(String advancementKey) {
        return Arrays.stream(values()).filter(entry -> entry.advancementKey.equals(advancementKey)).findFirst();
    }
}
