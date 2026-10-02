package cn.originalrpg.mobs.ability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MobAbilityRulesTest {
    @Test
    void vanillaFamiliesReceiveReadableCompatibleCandidates() {
        assertTrue(MobAbilityRules.candidates("SKELETON").contains(MobBehavior.SKIRMISHER));
        assertTrue(MobAbilityRules.candidates("SPIDER").contains(MobBehavior.SPITTER));
        assertTrue(MobAbilityRules.candidates("ZOMBIE").contains(MobBehavior.WARDEN));
        assertTrue(MobAbilityRules.candidates("ENDERMAN").contains(MobBehavior.BURROWER));
        assertTrue(MobAbilityRules.candidates("BLAZE").contains(MobBehavior.ORBITER));
        assertEquals(List.of(), MobAbilityRules.candidates("COW"));
    }

    @Test
    void selectionIsBoundedForEveryInteger() {
        List<MobBehavior> candidates = List.of(MobBehavior.CHARGER, MobBehavior.WARDEN);
        assertEquals(MobBehavior.CHARGER, MobAbilityRules.choose(candidates, Integer.MIN_VALUE));
        assertEquals(MobBehavior.WARDEN, MobAbilityRules.choose(candidates, -1));
        assertNull(MobAbilityRules.choose(List.of(), 9));
    }

    @Test
    void abilityDamageScalesButCannotGrowWithoutLimit() {
        assertEquals(3.2, MobAbilityRules.scaledDamage(1, 3.2, 0.05, 7.0), 0.0001);
        assertEquals(7.0, MobAbilityRules.scaledDamage(500, 3.2, 0.05, 7.0), 0.0001);
        assertEquals(3.2, MobAbilityRules.scaledDamage(-5, 3.2, 0.05, 7.0), 0.0001);
    }
}
