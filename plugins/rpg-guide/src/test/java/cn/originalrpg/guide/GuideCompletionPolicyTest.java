package cn.originalrpg.guide;

import cn.originalrpg.api.rules.CombatRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuideCompletionPolicyTest {
    @Test
    void explorationRequiresARealNonRecipeVanillaAdvancement() {
        assertTrue(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", "adventure/adventuring_time", true, "world", "world", true));
        assertTrue(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", "story/mine_stone", true, "world", "world", true));

        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", "recipes/building_blocks/oak_planks", true, "world", "world", true));
        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "customquests", "story/mine_stone", true, "world", "world", true));
        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                null, "story/mine_stone", true, "world", "world", true));
        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", null, true, "world", "world", true));
    }

    @Test
    void explorationRequiresWildArrivalAndTheConfiguredNormalOverworld() {
        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", "story/mine_stone", false, "world", "world", true));
        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", "story/mine_stone", true, "sivita_hub", "world", true));
        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", "story/mine_stone", true, "world_nether", "world", false));
        assertFalse(GuideCompletionPolicy.isVanillaExplorationAdvancement(
                "minecraft", "story/mine_stone", true, "world", null, true));
    }

    @Test
    void starterBuildRequiresAllFiveStartingPointsAndALearnedSkill() {
        assertTrue(GuideCompletionPolicy.starterBuildReady(5, 1));
        assertTrue(GuideCompletionPolicy.starterBuildReady(8, 2));
        assertFalse(GuideCompletionPolicy.starterBuildReady(4, 1));
        assertFalse(GuideCompletionPolicy.starterBuildReady(5, 0));
        assertFalse(GuideCompletionPolicy.starterBuildReady(0, 1));
    }

    @Test
    void everyDynamicLevelMarkerCompletesTheHuntRegardlessOfRewardGap() {
        assertTrue(GuideCompletionPolicy.adaptiveMonsterKillCompletes(1));
        assertTrue(GuideCompletionPolicy.adaptiveMonsterKillCompletes(25));
        assertTrue(GuideCompletionPolicy.adaptiveMonsterKillCompletes(500));
        assertFalse(GuideCompletionPolicy.adaptiveMonsterKillCompletes(null));

        assertTrue(CombatRules.allowsCustomDrops(24, 5), "a gap of 19 retains RPG rewards");
        assertFalse(CombatRules.allowsCustomDrops(25, 5), "a gap of 20 removes RPG rewards only");
        assertTrue(GuideCompletionPolicy.adaptiveMonsterKillCompletes(25),
                "the same gap-20 kill still completes the journey goal");
    }
}
