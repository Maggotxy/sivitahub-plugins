package cn.originalrpg.pets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PetCombatRulesTest {
    @Test
    void capsDamageByLevelPolicy() {
        assertEquals(6.0, PetCombatRules.boundedDamage(20.0, 6.0, 30.0, true));
    }

    @Test
    void reservesFinalHitForThePlayer() {
        assertEquals(2.5, PetCombatRules.boundedDamage(10.0, 10.0, 3.5, true));
        assertEquals(0.0, PetCombatRules.boundedDamage(10.0, 10.0, 1.0, true));
    }

    @Test
    void rejectsInvalidDamageInputs() {
        assertEquals(0.0, PetCombatRules.boundedDamage(Double.NaN, 10.0, 20.0, true));
        assertEquals(0.0, PetCombatRules.boundedDamage(3.0, -1.0, 20.0, true));
    }
}
