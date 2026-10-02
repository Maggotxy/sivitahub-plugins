package cn.originalrpg.hub;

import cn.originalrpg.api.model.JourneyStep;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HubWayfindingPolicyTest {
    @Test
    void journeyDefaultsResolveOnlyNpcOwnedObjectives() {
        assertEquals(HubRole.GUIDE, HubWayfinder.roleFor(JourneyStep.HUB).orElseThrow());
        assertEquals(HubRole.CLASS, HubWayfinder.roleFor(JourneyStep.CLASS).orElseThrow());
        assertEquals(HubRole.CLASS, HubWayfinder.roleFor(JourneyStep.ATTRIBUTE).orElseThrow());
        assertEquals(HubRole.WILDERNESS, HubWayfinder.roleFor(JourneyStep.EXPLORE).orElseThrow());
        assertTrue(HubWayfinder.roleFor(JourneyStep.HUNT).isEmpty());
        assertEquals(HubRole.FORGE, HubWayfinder.roleFor(JourneyStep.FORGE).orElseThrow());
        assertTrue(HubWayfinder.roleFor(JourneyStep.REVEAL).isEmpty());
        assertEquals(HubRole.DUNGEON, HubWayfinder.roleFor(JourneyStep.DUNGEON).orElseThrow());
        assertEquals(HubRole.QUESTS, HubWayfinder.roleFor(null).orElseThrow());
    }

    @Test
    void commandAliasesResolveWithoutCreatingASecondDestinationVocabulary() {
        assertEquals(HubRole.CLASS, HubRole.parse("attributes").orElseThrow());
        assertEquals(HubRole.ENHANCEMENT, HubRole.parse("refine").orElseThrow());
        assertEquals(HubRole.WILDERNESS, HubRole.parse("frontier").orElseThrow());
        assertEquals(HubRole.QUESTS, HubRole.parse("tasks").orElseThrow());
        assertEquals(HubRole.AI, HubRole.parse("listener").orElseThrow());
    }

    @Test
    void permanentRoutesRepeatTheNpcColourLanguage() {
        assertEquals(Material.PURPLE_CONCRETE, HubBlueprint.routeMaterial(HubRole.CLASS));
        assertEquals(Material.ORANGE_CONCRETE, HubBlueprint.routeMaterial(HubRole.FORGE));
        assertEquals(Material.RED_CONCRETE, HubBlueprint.routeMaterial(HubRole.DUNGEON));
        assertEquals(Material.GREEN_CONCRETE, HubBlueprint.routeMaterial(HubRole.WILDERNESS));
    }

    @Test
    void finalBlueprintKeepsEveryRouteAndDistantPoiCrestVisible() {
        HubSite site = new HubSite(null, 0, 100, 0, 52, 60, 3);
        Map<String, Material> finalBlocks = new HashMap<>();
        for (HubBlueprint.BlockChange change : HubBlueprint.create(site)) {
            finalBlocks.put(change.x() + ":" + change.y() + ":" + change.z(), change.material());
        }

        assertEquals(Material.PURPLE_CONCRETE, finalBlocks.get("-15:100:-15"));
        assertEquals(Material.BLUE_CONCRETE, finalBlocks.get("0:100:-20"));
        assertEquals(Material.ORANGE_CONCRETE, finalBlocks.get("15:100:-15"));
        assertEquals(Material.CYAN_CONCRETE, finalBlocks.get("20:100:0"));
        assertEquals(Material.RED_CONCRETE, finalBlocks.get("15:100:15"));
        assertEquals(Material.LIME_CONCRETE, finalBlocks.get("-20:100:0"));
        assertEquals(Material.YELLOW_CONCRETE, finalBlocks.get("-15:100:15"));
        assertEquals(Material.GREEN_CONCRETE, finalBlocks.get("0:100:31"));
        for (HubRole role : HubRole.values()) {
            if (role == HubRole.GUIDE || role == HubRole.QUESTS || role == HubRole.AI) continue;
            assertEquals(Material.SEA_LANTERN,
                    finalBlocks.get(role.offsetX() + ":109:" + role.offsetZ()));
        }
    }

    @Test
    void particleCohortsStayBoundedForLargeHubCrowds() {
        assertEquals(1, HubWayfinder.particleStride(0, 256));
        assertEquals(1, HubWayfinder.particleStride(128, 256));
        assertEquals(4, HubWayfinder.particleStride(1_000, 256));
        assertEquals(40, HubWayfinder.particleStride(10_000, 256));
        assertEquals(1, HubWayfinder.particleStride(10_000, 0));
    }

    @Test
    void arrivalThresholdAlwaysFitsInsideTheRealInteractionRange() {
        assertEquals(2.5, HubWayfindingPolicy.arrivalDistance(5.0, 3.0));
        assertEquals(1.65, HubWayfindingPolicy.arrivalDistance(5.0, 2.0));
        assertEquals(0.25, HubWayfindingPolicy.arrivalDistance(5.0, 0.25));
        assertEquals(2.5, HubWayfindingPolicy.arrivalDistance(Double.NaN, Double.NaN));
    }

    @Test
    void compassRestorationRequiresTheNavigationTargetToStillBeOwned() {
        UUID world = UUID.randomUUID();
        UUID otherWorld = UUID.randomUUID();
        assertTrue(HubWayfindingPolicy.ownsCompassTarget(world, 24.5, -12.5,
                world, 24.5, -12.5));
        assertFalse(HubWayfindingPolicy.ownsCompassTarget(world, 25.5, -12.5,
                world, 24.5, -12.5));
        assertFalse(HubWayfindingPolicy.ownsCompassTarget(otherWorld, 24.5, -12.5,
                world, 24.5, -12.5));
    }
}
