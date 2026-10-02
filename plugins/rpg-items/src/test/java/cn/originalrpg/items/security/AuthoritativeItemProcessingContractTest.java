package cn.originalrpg.items.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthoritativeItemProcessingContractTest {
    @Test
    void listenerCoversEveryVanillaConversionSurface() throws IOException {
        Path source = findRepositoryRoot().resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/security/AuthoritativeItemProcessingListener.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        for (String event : new String[]{
                "PrepareItemCraftEvent",
                "CrafterCraftEvent",
                "BlockCookEvent",
                "FurnaceBurnEvent",
                "PrepareGrindstoneEvent",
                "PrepareAnvilEvent",
                "PrepareSmithingEvent"}) {
            assertTrue(code.contains(event), "Missing conversion boundary: " + event);
        }
        assertTrue(code.contains("StonecutterInventory"),
                "Stonecutter extraction must be checked at the result slot");
        assertTrue(code.contains("InventoryType.SlotType.RESULT"),
                "A final result-slot gate must defend against late plugin mutations");
    }

    @Test
    void obsoleteDungeonOnlyCraftBlacklistWasRemoved() throws IOException {
        String dungeonManager = Files.readString(findRepositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java"),
                StandardCharsets.UTF_8);
        assertFalse(dungeonManager.contains("PrepareItemCraftEvent"),
                "The central boundary replaces the boss-core-only crafting blacklist");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
