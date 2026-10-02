package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RareTraitRulesTest {
    @Test
    void weightedPoolIsDeterministicBoundedAndOrderIndependent() {
        Map<String, Integer> weights = Map.of("z", 1, "a", 2);
        assertEquals("a", RareTraitRules.select(weights, 0));
        assertEquals("a", RareTraitRules.select(weights, 1));
        assertEquals("z", RareTraitRules.select(weights, 2));
        assertEquals("z", RareTraitRules.select(weights, -1));
        assertEquals("a", RareTraitRules.select(weights, 3));
    }

    @Test
    void emptyOrNonPositivePoolsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> RareTraitRules.select(Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> RareTraitRules.select(Map.of("bad", 0), 0));
    }
}
