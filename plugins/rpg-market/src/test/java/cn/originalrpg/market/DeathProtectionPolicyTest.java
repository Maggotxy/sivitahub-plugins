package cn.originalrpg.market;

import cn.originalrpg.api.model.EquipmentSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathProtectionPolicyTest {
    private static EquipmentSnapshot equipment(Optional<UUID> owner) {
        return new EquipmentSnapshot(UUID.randomUUID(), UUID.randomUUID(), owner,
                "COMMON", "WEAPON", "WARRIOR", 4, 0, 0, 0, Optional.empty(), Map.of());
    }

    @Test
    void onlyTheDyingPlayersBoundEquipmentIsProtected() {
        UUID player = UUID.randomUUID();
        assertTrue(DeathProtectionPolicy.ownsEquipment(player, Optional.of(equipment(Optional.of(player)))));
        assertFalse(DeathProtectionPolicy.ownsEquipment(player, Optional.of(equipment(Optional.empty()))));
        assertFalse(DeathProtectionPolicy.ownsEquipment(player,
                Optional.of(equipment(Optional.of(UUID.randomUUID())))));
        assertFalse(DeathProtectionPolicy.ownsEquipment(player, Optional.empty()));
    }

    @Test
    void ownerMarkersMustMatchTheAccountExactly() {
        UUID player = UUID.randomUUID();
        assertTrue(DeathProtectionPolicy.ownsMarker(player, null, player.toString().toUpperCase()));
        assertFalse(DeathProtectionPolicy.ownsMarker(player, "", UUID.randomUUID().toString(), "corrupt"));
    }
}
