package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EquipmentAsyncLifecycleContractTest {
    @Test
    void compensationAndOperationCallbacksRejectStaleSessionsAndShutdown() throws Exception {
        Path root = repositoryRoot();
        String recovery = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/EquipmentRecoveryListener.java"));
        String coordinator = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/EquipmentOperationCoordinator.java"));
        String salvage = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/SalvageCommand.java"));
        assertTrue(recovery.contains("profiles.get(player.getUniqueId()).whenComplete((profile, failure) -> sync("));
        assertTrue(recovery.contains("if (!plugin.isEnabled()) return"));
        assertTrue(recovery.contains("if (plugin.isEnabled()) action.run()"));
        assertTrue(recovery.contains("if (!currentSession(player)) return"));
        assertTrue(recovery.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertTrue(coordinator.contains("stage.whenComplete((result, error) -> sync("));
        // The stage-side shape gate is the anti-brick seam: a request the ledger
        // would answer INVALID/CONFLICT must never reach the PDC envelope, or the
        // account stays blocked until an operator intervenes.
        assertTrue(coordinator.contains("if (!request.settleable()) throw new IllegalArgumentException"));
        assertTrue(coordinator.contains("if (!plugin.isEnabled()) return"));
        assertTrue(coordinator.contains("if (plugin.isEnabled()) action.run()"));
        assertTrue(coordinator.contains("if (!currentSession(player)) return"));
        assertTrue(salvage.contains("EquipmentOperationKind.SALVAGE"));
        assertTrue(salvage.contains("operations.stage(player, request)"));
        assertTrue(salvage.contains("player.saveData()"));
        assertTrue(salvage.contains("operations.submit(player, request"));
        assertTrue(salvage.contains("List<String> rejected = List.of(operations.itemPayload(original))"));
        assertTrue(salvage.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
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
