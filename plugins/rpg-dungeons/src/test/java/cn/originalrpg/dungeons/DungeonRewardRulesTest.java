package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonRewardRulesTest {
    @Test
    void catalogContainsThreeRealNormalHiddenFamiliesAndSixDistinctMaterials() {
        assertEquals(6, DungeonRewardRules.all().size());
        assertEquals(Set.of("ancient", "sulfur", "storm"), DungeonRewardRules.all().stream()
                .map(DungeonRewardRules.RewardSpec::family).collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, DungeonRewardRules.all().stream()
                .map(DungeonRewardRules.RewardSpec::materialNameKey).distinct().count());

        for (DungeonRewardRules.RewardSpec normal : DungeonRewardRules.all().stream()
                .filter(spec -> !spec.hidden()).toList()) {
            DungeonRewardRules.RewardSpec hidden = DungeonRewardRules.require(normal.hiddenDungeonId());
            assertTrue(hidden.hidden());
            assertEquals(normal.family(), hidden.family());
            assertTrue(hidden.materialAmount() > normal.materialAmount());
            assertFalse(normal.ticketNameKey().isBlank());
            assertFalse(hidden.skillBookNameKey().isBlank());
        }
    }

    @Test
    void allBossesDoNotOpenWithTheSameGroundPattern() {
        Set<Integer> normalOffsets = new HashSet<>();
        DungeonRewardRules.all().stream().filter(spec -> !spec.hidden())
                .forEach(spec -> normalOffsets.add(spec.patternOffset()));
        assertEquals(Set.of(0, 1, 2), normalOffsets);
    }

    @Test
    void startupConfigurationCannotSilentlyDropOrInventAnEncounter() {
        DungeonRewardRules.requireConfiguredIds(DungeonRewardRules.ids());
        Set<String> missing = new HashSet<>(DungeonRewardRules.ids());
        missing.remove("storm-vault-hidden");
        assertThrows(IllegalStateException.class, () -> DungeonRewardRules.requireConfiguredIds(missing));
        assertThrows(IllegalStateException.class, () -> DungeonRewardRules.requireConfiguredIds(
                Set.of("ancient-ruins", "custom-placeholder")));
    }
}
