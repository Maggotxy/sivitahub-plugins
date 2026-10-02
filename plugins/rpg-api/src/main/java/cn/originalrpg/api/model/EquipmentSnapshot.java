package cn.originalrpg.api.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record EquipmentSnapshot(
        UUID itemId,
        UUID crafterId,
        Optional<UUID> boundOwner,
        String rarity,
        String equipmentType,
        String archetype,
        int totalSlots,
        int revealedSlots,
        int experience,
        int limitBreakLevel,
        Optional<String> rareTrait,
        Map<String, Integer> stats
) {
    public EquipmentSnapshot {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(crafterId, "crafterId");
        Objects.requireNonNull(boundOwner, "boundOwner");
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(equipmentType, "equipmentType");
        Objects.requireNonNull(archetype, "archetype");
        Objects.requireNonNull(rareTrait, "rareTrait");
        Objects.requireNonNull(stats, "stats");
        stats = Map.copyOf(stats);
    }

    public int stat(String name) {
        return stats.getOrDefault(name, 0);
    }
}
