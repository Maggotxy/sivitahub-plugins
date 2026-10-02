package cn.originalrpg.pets;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetPolicyTest {
    private final PetPolicy policy = new PetPolicy(
            Set.of("dungeon_instance_", "dungeon_"), Set.of("sivita_hub"), Set.of("world"));

    @Test
    void blocksCombatAndEveryDynamicDungeon() {
        assertEquals(PetPolicy.Denial.COMBAT, policy.summonDenial(true, "world"));
        assertEquals(PetPolicy.Denial.DUNGEON,
                policy.summonDenial(false, "dungeon_instance_ancient_ruins_123"));
        assertEquals(PetPolicy.Denial.WORLD, policy.summonDenial(false, "sivita_hub"));
    }

    @Test
    void worldMatchingIsPrefixBound() {
        assertTrue(policy.inDungeon("dungeon_test"));
        assertFalse(policy.inDungeon("world_dungeon_test"));
    }

    @Test
    void captureIsOnlyAvailableInConfiguredSurvivalWorld() {
        assertTrue(policy.canCapture("world"));
        assertFalse(policy.canCapture("world_nether"));
        assertFalse(policy.canCapture("sivita_hub"));
        assertFalse(policy.canCapture("dungeon_instance_test"));
    }
}
