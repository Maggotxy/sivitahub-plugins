package cn.originalrpg.api.model;

import java.util.Set;
import java.util.UUID;

/** Main-thread evidence derived only from authenticated, player-owned equipment and journey state. */
public record AscensionEvidence(
        int targetAscension, boolean journeyComplete, int equipmentScore, Set<UUID> equipmentIds
) {
    public AscensionEvidence {
        if (targetAscension < 1 || targetAscension > 3
                || equipmentScore < 0 || equipmentScore > 1_000_000 || equipmentIds == null
                || equipmentIds.size() > 16 || equipmentIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid ascension evidence");
        }
        equipmentIds = Set.copyOf(equipmentIds);
    }
}
