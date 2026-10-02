package cn.originalrpg.api.rules;

import java.util.Locale;

/** Immediate, deterministic RPG value retained from the vanilla forge base. */
public final class EquipmentBaseRules {
    private EquipmentBaseRules() {
    }

    public static int power(String material, String rarity, String equipmentType) {
        if (!"WEAPON".equals(normalize(equipmentType))) return 0;
        return 1 + quality(material) + rarityBonus(rarity);
    }

    public static int guard(String material, String rarity, String equipmentType) {
        if (!"ARMOR".equals(normalize(equipmentType))) return 0;
        return 1 + quality(material) + rarityBonus(rarity);
    }

    public static int quality(String material) {
        String name = normalize(material);
        if (name.startsWith("NETHERITE_")) return 4;
        if (name.startsWith("DIAMOND_") || name.equals("TRIDENT") || name.equals("MACE")) return 3;
        if (name.startsWith("IRON_") || name.equals("TURTLE_HELMET")
                || name.equals("SHIELD") || name.equals("CROSSBOW")) return 2;
        if (name.startsWith("STONE_") || name.startsWith("CHAINMAIL_")
                || name.equals("BOW") || name.equals("BLAZE_ROD") || name.equals("BREEZE_ROD")) return 1;
        return 0;
    }

    private static int rarityBonus(String rarity) {
        return switch (normalize(rarity)) {
            case "LEGENDARY" -> 3;
            case "EPIC" -> 2;
            case "RARE" -> 1;
            default -> 0;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
