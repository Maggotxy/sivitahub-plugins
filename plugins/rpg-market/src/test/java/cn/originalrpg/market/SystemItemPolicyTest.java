package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemItemPolicyTest {
    @Test
    void personalInteractionItemsCannotEnterThePlayerMarket() {
        assertTrue(SystemItemPolicy.isNonTransferableMarker("rpgguide:handbook"));
        assertTrue(SystemItemPolicy.isNonTransferableMarker("rpgpets:companion_signal"));
        assertTrue(SystemItemPolicy.isNonTransferableMarker("rpgdungeons:ticket_entry_escrow"));
    }

    @Test
    void LegitimateMaterialsAndEquipmentAreNotBlockedByNamespace() {
        assertFalse(SystemItemPolicy.isNonTransferableMarker("rpgmobs:adaptive_material"));
        assertFalse(SystemItemPolicy.isNonTransferableMarker("rpgdungeons:boss_material"));
        assertFalse(SystemItemPolicy.isNonTransferableMarker("rpgitems:item_id"));
        assertFalse(SystemItemPolicy.isNonTransferableMarker("minecraft:custom_data"));
        assertFalse(SystemItemPolicy.isNonTransferableMarker(null));
    }
}
