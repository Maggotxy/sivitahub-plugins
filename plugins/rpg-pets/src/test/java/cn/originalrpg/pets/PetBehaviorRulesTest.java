package cn.originalrpg.pets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PetBehaviorRulesTest {
    @Test
    void friendlyPublicModeMeansGuardNotPacifist() {
        assertEquals("friendly", PetBehaviorRules.publicMode("friendly"));
        assertEquals("raid", PetBehaviorRules.engineMode("friendly"));
    }

    @Test
    void aggressiveRemainsExplicit() {
        assertEquals("aggressive", PetBehaviorRules.publicMode("AGGRESSIVE"));
        assertEquals("aggressive", PetBehaviorRules.engineMode("aggressive"));
    }

    @Test
    void unknownInputFallsBackToGuard() {
        assertEquals("friendly", PetBehaviorRules.publicMode("farm"));
        assertEquals("raid", PetBehaviorRules.engineMode(PetBehaviorRules.publicMode("farm")));
    }
}
