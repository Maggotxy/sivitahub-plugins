package cn.originalrpg.enhancement;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefinementWeightsTest {
    private static final String[] STATS = {"strength", "defense", "agility", "intelligence",
            "stamina", "critical", "healing", "skill-haste"};

    @Test
    void strengthTwoAddsExactlyOneHundredFiftyTicketsToTheBias() {
        int total = RefinementWeights.totalWeight(STATS, "agility", 2);
        Map<String, Integer> counts = new HashMap<>();
        for (int ticket = 0; ticket < total; ticket++) {
            counts.merge(RefinementWeights.select(STATS, "agility", 2, ticket), 1, Integer::sum);
        }
        assertEquals(950, total);
        assertEquals(250, counts.get("agility"));
        assertEquals(100, counts.get("strength"));
    }

    @Test
    void invalidBiasFallsBackToUniformWeights() {
        assertEquals(800, RefinementWeights.totalWeight(STATS, "invalid", 3));
        assertThrows(IllegalArgumentException.class,
                () -> RefinementWeights.select(STATS, "invalid", 3, 800));
    }
}
