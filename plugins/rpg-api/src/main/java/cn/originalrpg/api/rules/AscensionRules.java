package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.AscensionRequirement;
import cn.originalrpg.api.model.EquipmentSnapshot;

import java.util.Optional;
import java.util.Set;

/** Bounded ascension and authenticated-equipment scoring rules shared by UI and persistence. */
public final class AscensionRules {
    private AscensionRules() {
    }

    public static Optional<AscensionRequirement> requirement(int targetAscension) {
        return switch (targetAscension) {
            case 1 -> Optional.of(new AscensionRequirement(1, 40, 2, 1, 1, 20, true, false));
            case 2 -> Optional.of(new AscensionRequirement(2, 160, 5, 10, 3, 100, false, true));
            case 3 -> Optional.of(new AscensionRequirement(3, 300, 7, 30, 2, 300, false, true));
            default -> Optional.empty();
        };
    }

    /** Authored encounters which can prove preparation for this transition. */
    public static Set<String> qualifyingDungeons(int targetAscension) {
        return switch (targetAscension) {
            case 1 -> Set.of("ancient-ruins", "ancient-ruins-hidden");
            case 2 -> Set.of(
                    "sulfur-depths", "sulfur-depths-hidden",
                    "storm-vault", "storm-vault-hidden");
            case 3 -> Set.of("storm-vault", "storm-vault-hidden");
            default -> Set.of();
        };
    }

    public static int equipmentScore(EquipmentSnapshot item) {
        int rarity = switch (item.rarity()) {
            case "COMMON" -> 8;
            case "RARE" -> 14;
            case "EPIC" -> 22;
            case "LEGENDARY" -> 32;
            default -> 0;
        };
        int refinedPower = item.stats().entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("base-") && entry.getValue() > 0)
                .mapToInt(java.util.Map.Entry::getValue)
                .sum();
        int base = Math.max(0, item.stat("base-power")) + Math.max(0, item.stat("base-guard"));
        return Math.clamp(rarity + base + item.revealedSlots() * 4 + item.limitBreakLevel() * 12
                + (item.rareTrait().isPresent() ? 10 : 0) + Math.min(30, refinedPower / 4), 0, 200);
    }
}
