package cn.originalrpg.economy;

import java.util.Set;

final class VanillaIntegrationRules {
    private static final Set<String> RESONANT_ORES = Set.of(
            "COAL_ORE", "DEEPSLATE_COAL_ORE",
            "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE",
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE",
            "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE",
            "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE"
    );

    private VanillaIntegrationRules() {
    }

    static boolean isResonantOre(String material) {
        return RESONANT_ORES.contains(material);
    }

    static boolean isNaturalResonantOre(String material, int experienceToDrop, boolean playerPlaced) {
        return !playerPlaced && experienceToDrop > 0 && isResonantOre(material);
    }

    static int grantableEssence(String today, String storedDate, int storedCount, int cap, int requested) {
        if (requested <= 0 || cap <= 0) return 0;
        int current = today.equals(storedDate) ? Math.max(0, storedCount) : 0;
        return Math.max(0, Math.min(requested, cap - current));
    }
}
