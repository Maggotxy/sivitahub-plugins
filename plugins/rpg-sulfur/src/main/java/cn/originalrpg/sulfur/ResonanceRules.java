package cn.originalrpg.sulfur;

import java.util.Locale;
import java.util.Optional;

final class ResonanceRules {
    private ResonanceRules() {
    }

    static Optional<Resonance> classify(String materialName) {
        if (materialName == null || materialName.isBlank()) return Optional.empty();
        String name = materialName.toUpperCase(Locale.ROOT);
        if (name.equals("POTENT_SULFUR")) return resonance("skill-haste", 3);
        if (name.contains("CINNABAR")) return resonance("critical", 3);
        if (name.contains("SULFUR")) return resonance("skill-haste", 2);
        if (name.equals("MAGMA_BLOCK") || name.equals("LAVA_BUCKET")) return resonance("strength", 3);
        if (name.equals("HONEY_BLOCK") || name.equals("HONEYCOMB_BLOCK")) return resonance("healing", 3);
        if (name.contains("ICE")) return resonance("skill-haste", 2);
        if (name.endsWith("_WOOL")) return resonance("intelligence", 2);
        if (isStaminaPrototype(name)) return resonance("stamina", 2);
        if (isWood(name)) return resonance("agility", 2);
        if (isMetal(name)) return resonance("strength", 2);
        if (isMineral(name)) return resonance("defense", 2);
        return Optional.empty();
    }

    static double biasedChance(int strength) {
        int safeStrength = Math.clamp(strength, 1, 3);
        int bonus = safeStrength * 75;
        return (100.0 + bonus) / (800.0 + bonus);
    }

    private static Optional<Resonance> resonance(String bias, int strength) {
        return Optional.of(new Resonance(bias, strength));
    }

    private static boolean isWood(String name) {
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE") || name.endsWith("_PLANKS") || name.equals("BAMBOO_BLOCK")
                || name.equals("STRIPPED_BAMBOO_BLOCK");
    }

    private static boolean isMetal(String name) {
        return name.equals("IRON_BLOCK") || name.equals("GOLD_BLOCK") || name.equals("NETHERITE_BLOCK")
                || name.equals("RAW_IRON_BLOCK") || name.equals("RAW_GOLD_BLOCK")
                || name.equals("RAW_COPPER_BLOCK") || name.equals("COPPER_BLOCK")
                || name.endsWith("_COPPER") || name.endsWith("_COPPER_BLOCK");
    }

    private static boolean isMineral(String name) {
        return name.endsWith("_ORE") || name.equals("STONE") || name.endsWith("_STONE")
                || name.contains("STONE_BRICK") || name.contains("SANDSTONE")
                || name.contains("DEEPSLATE")
                || name.contains("OBSIDIAN") || name.contains("TUFF") || name.contains("ANDESITE")
                || name.contains("DIORITE") || name.contains("GRANITE") || name.contains("BASALT")
                || name.contains("BLACKSTONE");
    }

    private static boolean isStaminaPrototype(String name) {
        return name.equals("MOSS_BLOCK") || name.equals("PALE_MOSS_BLOCK")
                || name.equals("HAY_BLOCK") || name.equals("DRIED_KELP_BLOCK")
                || name.equals("ROOTED_DIRT") || name.equals("MUD")
                || name.equals("PACKED_MUD") || name.equals("MUD_BRICKS");
    }

    record Resonance(String bias, int strength) {
    }
}
