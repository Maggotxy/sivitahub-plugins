package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HomeWorldRulesTest {
    @Test
    void onlyTheConfiguredVanillaSurvivalWorldCanOwnABedHome() {
        assertTrue(HomeWorldRules.isSurvivalWorld("world", true, "world"));
        assertFalse(HomeWorldRules.isSurvivalWorld("sivita_hub", true, "world"));
        assertFalse(HomeWorldRules.isSurvivalWorld("adventure_normal", true, "world"));
        assertFalse(HomeWorldRules.isSurvivalWorld("world", false, "world"));
        assertFalse(HomeWorldRules.isSurvivalWorld("world", true, ""));
    }
}
