package cn.originalrpg.items.forge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gameplay contract for dungeon cores. Values are deliberately bounded here
 * instead of being scattered across preview and result generation.
 */
final class DungeonCoreResonanceRules {
    private static final Map<String, Resonance> RESONANCES = create();

    private DungeonCoreResonanceRules() {
    }

    static Optional<Resonance> inspect(String sourceId) {
        return sourceId == null ? Optional.empty() : Optional.ofNullable(RESONANCES.get(sourceId));
    }

    static Set<String> ids() {
        return RESONANCES.keySet();
    }

    static double successChance(double ordinaryChance, double maximumChance, Resonance resonance) {
        return Math.min(maximumChance, ordinaryChance + resonance.successBonus());
    }

    static double rarityLuck(double ordinaryLuck, Resonance resonance) {
        return Math.min(0.30, ordinaryLuck + resonance.rarityLuckBonus());
    }

    static double rareTraitChance(double ordinaryChance, Resonance resonance) {
        return Math.clamp(ordinaryChance + resonance.rareTraitBonus(), 0.0, 0.10);
    }

    static double inheritanceChance(double ordinaryChance, Resonance resonance) {
        return Math.clamp(ordinaryChance + resonance.inheritanceBonus(), 0.01, 0.50);
    }

    static String enforceMinimumRarity(String rolled, Resonance resonance) {
        return rarityRank(rolled) < rarityRank(resonance.minimumRarity())
                ? resonance.minimumRarity() : rolled;
    }

    static int minimumSlots(String rarity, Resonance resonance) {
        return Math.min(8, ForgeRules.slotLowerBound(rarity) + resonance.minimumSlotBonus());
    }

    private static int rarityRank(String rarity) {
        return switch (rarity) {
            case "LEGENDARY" -> 3;
            case "EPIC" -> 2;
            case "RARE" -> 1;
            default -> 0;
        };
    }

    private static Map<String, Resonance> create() {
        LinkedHashMap<String, Resonance> result = new LinkedHashMap<>();
        // Ruins preserve knowledge: trait discovery and blueprint inheritance.
        add(result, new Resonance("ancient-ruins", "ancient", false,
                0.00, 0.00, "COMMON", 0, 0.010, 0.04));
        add(result, new Resonance("ancient-ruins-hidden", "ancient", true,
                0.02, 0.02, "RARE", 0, 0.035, 0.12));
        // Sulfur stabilises the blank lattice: a larger guaranteed slot floor.
        add(result, new Resonance("sulfur-depths", "sulfur", false,
                0.01, 0.01, "COMMON", 1, 0.000, 0.02));
        add(result, new Resonance("sulfur-depths-hidden", "sulfur", true,
                0.03, 0.03, "RARE", 2, 0.010, 0.04));
        // Storm cores amplify the attempt and push the rarity roll upward.
        add(result, new Resonance("storm-vault", "storm", false,
                0.04, 0.05, "COMMON", 0, 0.005, 0.00));
        add(result, new Resonance("storm-vault-hidden", "storm", true,
                0.08, 0.10, "EPIC", 0, 0.015, 0.02));
        return Map.copyOf(result);
    }

    private static void add(Map<String, Resonance> result, Resonance resonance) {
        if (resonance.id() == null || resonance.id().isBlank()
                || resonance.family() == null || resonance.family().isBlank()
                || resonance.successBonus() < 0.0 || resonance.rarityLuckBonus() < 0.0
                || resonance.minimumSlotBonus() < 0 || resonance.minimumSlotBonus() > 2
                || resonance.rareTraitBonus() < 0.0 || resonance.inheritanceBonus() < 0.0
                || result.putIfAbsent(resonance.id(), resonance) != null) {
            throw new IllegalStateException("Invalid dungeon core resonance: " + resonance);
        }
    }

    record Resonance(
            String id,
            String family,
            boolean hidden,
            double successBonus,
            double rarityLuckBonus,
            String minimumRarity,
            int minimumSlotBonus,
            double rareTraitBonus,
            double inheritanceBonus
    ) {
    }
}
