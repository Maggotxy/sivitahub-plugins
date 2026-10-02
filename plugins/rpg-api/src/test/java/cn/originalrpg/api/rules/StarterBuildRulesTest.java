package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterBuildRulesTest {
    @Test
    void everyClassUsesExactlyFivePointsAndCanReachItsFirstSkill() {
        for (Archetype archetype : Archetype.values()) {
            Map<CoreAttribute, Integer> requirements = StarterBuildRules.requirements(archetype);
            if (archetype == Archetype.UNCHOSEN) {
                assertTrue(requirements.isEmpty());
                continue;
            }
            assertEquals(StarterBuildRules.STARTING_ATTRIBUTE_POINTS,
                    requirements.values().stream().mapToInt(Integer::intValue).sum(), archetype.name());
            assertTrue(StarterBuildRules.ready(archetype, requirements), archetype.name());
        }
    }

    @Test
    void allocationIsGuidedOnlyUntilTheStarterContractIsSatisfied() {
        EnumMap<CoreAttribute, Integer> mage = new EnumMap<>(CoreAttribute.class);
        assertTrue(StarterBuildRules.mayAllocate(Archetype.MAGE, mage, CoreAttribute.INTELLIGENCE));
        assertTrue(StarterBuildRules.mayAllocate(Archetype.MAGE, mage, CoreAttribute.STAMINA));
        assertFalse(StarterBuildRules.mayAllocate(Archetype.MAGE, mage, CoreAttribute.STRENGTH));
        assertFalse(StarterBuildRules.mayAllocate(Archetype.UNCHOSEN, mage, CoreAttribute.INTELLIGENCE));

        mage.put(CoreAttribute.INTELLIGENCE, 4);
        mage.put(CoreAttribute.STAMINA, 1);
        assertTrue(StarterBuildRules.ready(Archetype.MAGE, mage));
        assertTrue(StarterBuildRules.mayAllocate(Archetype.MAGE, mage, CoreAttribute.STRENGTH),
                "after the first-skill gate, builds become free-form again");
    }
}
