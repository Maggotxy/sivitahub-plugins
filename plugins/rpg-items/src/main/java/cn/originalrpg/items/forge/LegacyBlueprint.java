package cn.originalrpg.items.forge;

import java.util.Objects;
import java.util.UUID;

record LegacyBlueprint(
        UUID blueprintId,
        UUID ownerId,
        UUID sourceItemId,
        String rareTrait,
        String equipmentType,
        String archetype
) {
    LegacyBlueprint {
        Objects.requireNonNull(blueprintId, "blueprintId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(sourceItemId, "sourceItemId");
        Objects.requireNonNull(rareTrait, "rareTrait");
        Objects.requireNonNull(equipmentType, "equipmentType");
        Objects.requireNonNull(archetype, "archetype");
    }
}
