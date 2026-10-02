package cn.originalrpg.market;

import cn.originalrpg.api.model.EquipmentSnapshot;

import java.util.Optional;
import java.util.UUID;

/** Pure ownership checks for the deliberately narrow death-protection boundary. */
final class DeathProtectionPolicy {
    private DeathProtectionPolicy() {
    }

    static boolean ownsEquipment(UUID playerId, Optional<EquipmentSnapshot> snapshot) {
        return snapshot.flatMap(EquipmentSnapshot::boundOwner).filter(playerId::equals).isPresent();
    }

    static boolean ownsMarker(UUID playerId, String... encodedOwners) {
        String expected = playerId.toString();
        for (String encoded : encodedOwners) {
            if (expected.equalsIgnoreCase(encoded == null ? "" : encoded.strip())) return true;
        }
        return false;
    }
}
