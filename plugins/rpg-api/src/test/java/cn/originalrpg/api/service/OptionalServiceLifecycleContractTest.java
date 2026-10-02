package cn.originalrpg.api.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OptionalServiceLifecycleContractTest {
    private static final List<String> PERFORMANCE_CONSUMERS = List.of(
            "plugins/rpg-combat/src/main/java/cn/originalrpg/combat/CombatVisuals.java",
            "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonVisuals.java",
            "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java",
            "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/EnhancementVisuals.java",
            "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeVisuals.java",
            "plugins/rpg-skills/src/main/java/cn/originalrpg/skills/SkillVisuals.java",
            "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/MobVisuals.java",
            "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/ability/MobAbilityVisuals.java",
            "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/BetterModelMobAppearanceService.java",
            "plugins/rpg-sulfur/src/main/java/cn/originalrpg/sulfur/SulfurVisuals.java",
            "plugins/rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java",
            "plugins/rpg-story/src/main/java/cn/originalrpg/story/EscortManager.java",
            "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/RpgIntegrationsPlugin.java",
            "plugins/rpg-cosmetics/src/main/java/cn/originalrpg/cosmetics/RpgCosmeticsPlugin.java"
    );
    private static final List<String> SKILL_HUD_CONSUMERS = List.of(
            "plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SivitaPlaceholderExpansion.java",
            "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideCommand.java",
            "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideTracker.java",
            "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/PlayerListHud.java",
            "plugins/rpg-ai-npc/src/main/java/cn/originalrpg/ainpc/AiNpcController.java"
    );
    private static final List<String> AI_NPC_OPTIONAL_CONSUMERS = List.of(
            "plugins/rpg-ai-npc/src/main/java/cn/originalrpg/ainpc/AiNpcController.java"
    );

    @Test
    void optionalPerformanceConsumersResolveOnlyEnabledProvidersAtUseTime() throws IOException {
        assertDynamic(PERFORMANCE_CONSUMERS, "PerformanceService");
    }

    @Test
    void optionalSkillHudConsumersResolveOnlyEnabledProvidersAtUseTime() throws IOException {
        assertDynamic(SKILL_HUD_CONSUMERS, "SkillHudService");
    }

    @Test
    void optionalAiNpcConsumersResolveReloadableProvidersAtUseTime() throws IOException {
        for (String service : List.of(
                "JourneyService", "SkillService", "EquipmentService", "CombatStateService")) {
            assertDynamic(AI_NPC_OPTIONAL_CONSUMERS, service);
        }
    }

    private static void assertDynamic(List<String> relativePaths, String service) throws IOException {
        Path root = repositoryRoot();
        for (String relativePath : relativePaths) {
            String source = Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
            assertTrue(source.contains("OptionalServices.enabled("), relativePath);
            assertFalse(source.contains("private final " + service), relativePath);
            assertFalse(source.contains("private " + service), relativePath);
            assertFalse(source.contains("getServicesManager().load(" + service + ".class)"), relativePath);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
