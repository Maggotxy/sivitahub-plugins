package cn.originalrpg.classes;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.PlayerProfile;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassMenuPolicyTest {
    @Test
    void attributeAllocationStaysOpenWhileNavigationAndPermanentChoicesClose() {
        assertTrue(ClassMenuPolicy.keepsMenuOpen("attribute strength"));
        assertFalse(ClassMenuPolicy.keepsMenuOpen("choose mage"));
        assertFalse(ClassMenuPolicy.keepsMenuOpen("branch arcanist"));
        assertFalse(ClassMenuPolicy.keepsMenuOpen("ascend"));
        assertFalse(ClassMenuPolicy.keepsMenuOpen("command:skills"));
        assertFalse(ClassMenuPolicy.keepsMenuOpen("command:rpghelp current"));
        assertFalse(ClassMenuPolicy.keepsMenuOpen(null));
    }

    @Test
    void everyChosenClassHasOneConcreteStarterSkill() {
        assertEquals("heal", ClassMenuPolicy.starterSkill(Archetype.HEALER));
        assertEquals("war-cry", ClassMenuPolicy.starterSkill(Archetype.WARRIOR));
        assertEquals("shadow-step", ClassMenuPolicy.starterSkill(Archetype.ASSASSIN));
        assertEquals("fireball", ClassMenuPolicy.starterSkill(Archetype.MAGE));
        assertEquals("power-shot", ClassMenuPolicy.starterSkill(Archetype.RANGER));
        assertEquals("improvisation", ClassMenuPolicy.starterSkill(Archetype.FREEFORM));
        assertNull(ClassMenuPolicy.starterSkill(Archetype.UNCHOSEN));
        assertNull(ClassMenuPolicy.starterSkill(null));
        for (Archetype archetype : Archetype.values()) {
            assertEquals(archetype != Archetype.UNCHOSEN, ClassMenuPolicy.showsHiddenPath(archetype));
        }
        assertFalse(ClassMenuPolicy.showsHiddenPath(null));
    }

    @Test
    void wrongStarterAttributesAreNotClickableUntilTheFirstGateIsReady() {
        PlayerProfile mage = new PlayerProfile(UUID.randomUUID(), Archetype.MAGE, 0, 1, 0,
                100, 100, 5, 1, new java.util.EnumMap<>(CoreAttribute.class));
        assertTrue(ClassMenuPolicy.mayAllocateStarter(mage, CoreAttribute.INTELLIGENCE));
        assertTrue(ClassMenuPolicy.mayAllocateStarter(mage, CoreAttribute.STAMINA));
        assertFalse(ClassMenuPolicy.mayAllocateStarter(mage, CoreAttribute.STRENGTH));

        PlayerProfile ready = new PlayerProfile(mage.playerId(), Archetype.MAGE, 0, 1, 0,
                100, 102, 1, 1, Map.of(CoreAttribute.INTELLIGENCE, 4, CoreAttribute.STAMINA, 1));
        assertTrue(ClassMenuPolicy.starterReady(ready));
        assertTrue(ClassMenuPolicy.mayAllocateStarter(ready, CoreAttribute.STRENGTH));
    }

    @Test
    void branchTreeConvergesSymmetricallyWithoutNavigationCollisions() {
        assertEquals(34, ClassMenuPolicy.ASCEND_SLOT);
        assertEquals(25, ClassMenuPolicy.HIDDEN_PATH_SLOT);
        assertEquals(37, ClassMenuPolicy.branchSlot(1, 0));
        assertEquals(43, ClassMenuPolicy.branchSlot(1, 1));
        assertEquals(38, ClassMenuPolicy.branchSlot(2, 0));
        assertEquals(42, ClassMenuPolicy.branchSlot(2, 1));
        assertEquals(40, ClassMenuPolicy.branchSlot(3, 0));
        assertEquals(-1, ClassMenuPolicy.branchSlot(3, 1));
    }

    @Test
    void anUnselectedPreviousBranchIsAStableLockedState() {
        assertTrue(ClassMenuPolicy.prerequisiteSatisfied(1, Set.of("vanguard"), null));
        assertTrue(ClassMenuPolicy.prerequisiteSatisfied(2, Set.of(), null));
        assertFalse(ClassMenuPolicy.prerequisiteSatisfied(2, Set.of("vanguard"), null));
        assertTrue(ClassMenuPolicy.prerequisiteSatisfied(2, Set.of("vanguard"), "vanguard"));
        assertFalse(ClassMenuPolicy.prerequisiteSatisfied(2, Set.of("vanguard"), "berserker"));
    }
}
