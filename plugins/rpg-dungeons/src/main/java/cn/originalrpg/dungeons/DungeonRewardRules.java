package cn.originalrpg.dungeons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The six shipped encounters and their gameplay-relevant reward identities. */
final class DungeonRewardRules {
    private static final List<RewardSpec> SPECS = List.of(
            new RewardSpec("ancient-ruins", "ancient", false,
                    "AMETHYST_SHARD", 8, "dungeon.items.ruins-inscription-core", "dungeon_core",
                    "ancient-ruins-hidden", "dungeon.items.ruins-seal-ticket",
                    null, null, null, 0),
            new RewardSpec("ancient-ruins-hidden", "ancient", true,
                    "ECHO_SHARD", 12, "dungeon.items.sealed-echo-core", "mutation_core",
                    null, null, "ancient-echo", "dungeon.items.sealed-echo-book",
                    "ancient_echo_skill_book", 2),
            new RewardSpec("sulfur-depths", "sulfur", false,
                    "SULFUR", 10, "dungeon.items.sulfur-crystal-core", "dungeon_core",
                    "sulfur-depths-hidden", "dungeon.items.cinnabar-heart-ticket",
                    null, null, null, 2),
            new RewardSpec("sulfur-depths-hidden", "sulfur", true,
                    "CINNABAR", 14, "dungeon.items.cinnabar-heart-core", "mutation_core",
                    null, null, "cinnabar-revelation", "dungeon.items.cinnabar-revelation-book",
                    "cinnabar_revelation_skill_book", 0),
            new RewardSpec("storm-vault", "storm", false,
                    "BREEZE_ROD", 12, "dungeon.items.storm-conductor-core", "dungeon_core",
                    "storm-vault-hidden", "dungeon.items.static-core-ticket",
                    null, null, null, 1),
            new RewardSpec("storm-vault-hidden", "storm", true,
                    "HEAVY_CORE", 16, "dungeon.items.silent-oath-core", "mutation_core",
                    null, null, "silent-thunder", "dungeon.items.silent-thunder-book",
                    "silent_thunder_skill_book", 2)
    );
    private static final Map<String, RewardSpec> BY_ID = indexAndValidate(SPECS);

    private DungeonRewardRules() {
    }

    static RewardSpec require(String id) {
        RewardSpec spec = BY_ID.get(id);
        if (spec == null) throw new IllegalArgumentException("Unsupported dungeon id: " + id);
        return spec;
    }

    static Set<String> ids() {
        return BY_ID.keySet();
    }

    static List<RewardSpec> all() {
        return SPECS;
    }

    static void requireConfiguredIds(Set<String> configuredIds) {
        if (!BY_ID.keySet().equals(Set.copyOf(configuredIds))) {
            throw new IllegalStateException("Dungeon configuration must define exactly " + BY_ID.keySet()
                    + "; found " + configuredIds);
        }
    }

    private static Map<String, RewardSpec> indexAndValidate(List<RewardSpec> specs) {
        LinkedHashMap<String, RewardSpec> result = new LinkedHashMap<>();
        for (RewardSpec spec : specs) {
            if (spec.id() == null || spec.id().isBlank() || spec.family() == null || spec.family().isBlank()
                    || spec.materialType() == null || spec.materialType().isBlank()
                    || spec.materialAmount() < 1 || spec.materialAmount() > 64
                    || spec.materialNameKey() == null || spec.materialNameKey().isBlank()
                    || spec.materialModel() == null || spec.materialModel().isBlank()
                    || spec.patternOffset() < 0 || spec.patternOffset() > 2
                    || result.putIfAbsent(spec.id(), spec) != null) {
                throw new IllegalStateException("Invalid dungeon reward specification: " + spec);
            }
            if (spec.hidden()) {
                if (!spec.id().endsWith("-hidden") || spec.hiddenDungeonId() != null
                        || spec.ticketNameKey() != null || spec.skillBookId() == null
                        || spec.skillBookId().isBlank() || spec.skillBookNameKey() == null
                        || spec.skillBookNameKey().isBlank() || spec.skillBookModel() == null
                        || spec.skillBookModel().isBlank()) {
                    throw new IllegalStateException("Invalid hidden dungeon reward specification: " + spec.id());
                }
            } else if (spec.id().endsWith("-hidden") || spec.hiddenDungeonId() == null
                    || spec.hiddenDungeonId().isBlank() || spec.ticketNameKey() == null
                    || spec.ticketNameKey().isBlank() || spec.skillBookId() != null
                    || spec.skillBookNameKey() != null || spec.skillBookModel() != null) {
                throw new IllegalStateException("Invalid normal dungeon reward specification: " + spec.id());
            }
        }
        if (result.size() != 6) throw new IllegalStateException("Exactly six shipped dungeon encounters are required");
        for (RewardSpec spec : specs) {
            long familyCount = specs.stream().filter(candidate -> candidate.family().equals(spec.family())).count();
            if (familyCount != 2) throw new IllegalStateException("Dungeon family must contain one normal/hidden pair");
            if (!spec.hidden()) {
                RewardSpec hidden = result.get(spec.hiddenDungeonId());
                if (hidden == null || !hidden.hidden() || !hidden.family().equals(spec.family())) {
                    throw new IllegalStateException("Dungeon hidden counterpart is invalid for " + spec.id());
                }
            }
        }
        if (specs.stream().map(RewardSpec::materialNameKey).distinct().count() != specs.size()) {
            throw new IllegalStateException("Every dungeon needs a distinct material identity");
        }
        long hiddenCount = specs.stream().filter(RewardSpec::hidden).count();
        if (specs.stream().filter(RewardSpec::hidden).map(RewardSpec::skillBookId).distinct().count()
                != hiddenCount
                || specs.stream().filter(RewardSpec::hidden).map(RewardSpec::skillBookModel).distinct().count()
                != hiddenCount) {
            throw new IllegalStateException("Each hidden dungeon needs its own skill book and item model");
        }
        return Map.copyOf(result);
    }

    record RewardSpec(
            String id,
            String family,
            boolean hidden,
            String materialType,
            int materialAmount,
            String materialNameKey,
            String materialModel,
            String hiddenDungeonId,
            String ticketNameKey,
            String skillBookId,
            String skillBookNameKey,
            String skillBookModel,
            int patternOffset
    ) {
    }
}
