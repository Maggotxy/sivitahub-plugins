package cn.originalrpg.items.forge;

final class ForgeRules {
    private ForgeRules() {
    }

    static int baseQuality(String name) {
        if (name.startsWith("NETHERITE_")) return 4;
        if (name.startsWith("DIAMOND_") || name.equals("TRIDENT") || name.equals("MACE")) return 3;
        if (name.startsWith("IRON_") || name.equals("TURTLE_HELMET")
                || name.equals("SHIELD") || name.equals("CROSSBOW")) return 2;
        if (name.startsWith("STONE_") || name.startsWith("CHAINMAIL_")
                || name.equals("BOW") || name.equals("BLAZE_ROD") || name.equals("BREEZE_ROD")) return 1;
        return 0;
    }

    static double successChance(
            String base, int catalystAmount, double baseChance, double maximumChance
    ) {
        double qualityBonus = baseQuality(base) * 0.025;
        return Math.min(maximumChance, baseChance + qualityBonus + catalystAmount * 0.02);
    }

    static double rarityLuck(String base, int catalystAmount) {
        return Math.min(0.22, catalystAmount * 0.015 + baseQuality(base) * 0.0125);
    }

    static int slotLowerBound(String rarity) {
        return switch (rarity) {
            case "LEGENDARY" -> 6;
            case "EPIC" -> 4;
            case "RARE" -> 2;
            default -> 0;
        };
    }

    static int slotUpperBound(String rarity, String base) {
        int quality = baseQuality(base);
        return switch (rarity) {
            case "LEGENDARY" -> 8;
            case "EPIC" -> Math.min(8, 7 + quality / 3);
            case "RARE" -> Math.min(8, 5 + quality / 2);
            default -> Math.min(8, 3 + quality);
        };
    }
}
