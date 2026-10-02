package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterForgePolicyTest {
    private static final UUID OWNER = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test
    void onlyTheSameOwnersCompleteAStarterPair() {
        String owner = OWNER.toString();
        var valid = StarterForgePolicy.assess(OWNER, owner, owner, "WARRIOR");
        assertTrue(valid.containsStarterComponent());
        assertTrue(valid.valid());
        assertEquals("WARRIOR", valid.archetype().orElseThrow());

        assertFalse(StarterForgePolicy.assess(OWNER, owner, UUID.randomUUID().toString(), "WARRIOR").valid());
        assertFalse(StarterForgePolicy.assess(OWNER, owner, null, "WARRIOR").valid());
        assertFalse(StarterForgePolicy.assess(OWNER, null, owner, null).valid());
    }

    @Test
    void ordinaryMaterialsNeverReceiveTheFreeGuarantee() {
        var ordinary = StarterForgePolicy.assess(OWNER, null, null, null);
        assertFalse(ordinary.containsStarterComponent());
        assertFalse(ordinary.valid());
        assertTrue(ordinary.archetype().isEmpty());
    }
}
