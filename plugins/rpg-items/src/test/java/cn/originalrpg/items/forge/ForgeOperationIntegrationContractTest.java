package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeOperationIntegrationContractTest {
    @Test
    void forgePersistsEscrowBeforeConsumingInputsAndUsesDeterministicOutbox() throws IOException {
        String code = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java"),
                StandardCharsets.UTF_8);
        int stage = code.indexOf("operations.stage(player, request)");
        int consume = code.indexOf("consume(core, coreCost)", stage);
        int submit = code.indexOf("operations.submit(player, request", consume);
        assertTrue(stage > 0 && consume > stage && submit > consume);
        assertTrue(code.contains("EquipmentOperationEnvelope.deterministicSeed"));
        assertTrue(code.contains("player.saveData()"));
        assertFalse(code.contains("economy.burn("));
    }

    @Test
    void operationMarkerMakesEscrowCopiesUnauthenticAndUnusable() throws IOException {
        Path root = repositoryRoot();
        String security = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/security/EquipmentSecurity.java"));
        String boundary = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/security/AuthoritativeItemProcessingListener.java"));
        assertTrue(security.contains("EquipmentOperationMarker.isMarked"));
        assertTrue(boundary.contains("onMarkedOperationItemClick"));
    }

    @Test
    void projectileKillsUseTheLaunchWeaponInsteadOfTheCurrentMainHand() throws IOException {
        String code = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java"),
                StandardCharsets.UTF_8);
        int handler = code.indexOf("public void onKill(EntityDeathEvent event)");
        int helper = code.indexOf("private void grantProjectileKillExperience", handler);
        String killHandler = code.substring(handler, helper);
        String projectileHelper = code.substring(helper, code.indexOf("\n    @EventHandler", helper));
        assertTrue(killHandler.contains("getDamageSource().getDirectEntity() instanceof AbstractArrow"));
        assertTrue(killHandler.contains("grantProjectileKillExperience(player, arrow, gained)"));
        assertTrue(projectileHelper.contains("arrow.getWeapon()"));
        assertTrue(projectileHelper.contains("player.getInventory().getContents()"));
        assertTrue(projectileHelper.contains("security.itemId(candidate).equals(launchedId)"));
        assertTrue(projectileHelper.contains("trident.setItem(thrown.clone())"));
        assertFalse(projectileHelper.contains("getItemInMainHand"));
    }

    @Test
    void unresolvedDatabaseOutcomeKeepsTheEnvelopeButReleasesOnlyTheLocalGate() throws IOException {
        Path root = repositoryRoot();
        String coordinator = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/EquipmentOperationCoordinator.java"));
        int failure = coordinator.indexOf("if (error != null)");
        int failureEnd = coordinator.indexOf("\n            if (!TERMINAL.contains(result.status()))", failure);
        String failureBranch = coordinator.substring(failure, failureEnd);
        assertTrue(failureBranch.contains("RECOVERY_PENDING"));
        assertTrue(failureBranch.contains("completion.accept"));
        assertFalse(failureBranch.contains("clearIfMatching"));

        String forge = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java"));
        int method = forge.indexOf("private void attemptForge");
        int persistentGate = forge.indexOf("operations.hasPending(player)", method);
        int localGate = forge.indexOf("pending.add(player.getUniqueId())", method);
        assertTrue(persistentGate > method && localGate > persistentGate,
                "the durable envelope must reject/recover before a new local operation is admitted");
    }

    @Test
    void onlyTheCurrentLoginSessionMayClearAndPersistTheEquipmentEnvelope() throws IOException {
        String coordinator = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/EquipmentOperationCoordinator.java"));
        int callback = coordinator.indexOf("stage.whenComplete");
        int sessionGuard = coordinator.indexOf("if (currentSession(player))", callback);
        int clear = coordinator.indexOf("clearIfMatching(player, request.operationId())", sessionGuard);
        int save = coordinator.indexOf("player.saveData()", clear);
        int helper = coordinator.indexOf("private static boolean currentSession", save);
        int handleMatch = coordinator.indexOf("Bukkit.getPlayer(player.getUniqueId()) == player", helper);

        assertTrue(callback >= 0 && sessionGuard > callback && clear > sessionGuard && save > clear,
                "an old Player handle must retain the idempotent envelope for the new login to replay");
        assertTrue(helper > save && handleMatch > helper,
                "online status alone is insufficient after a fast reconnect");
    }

    @Test
    void unbindAndLegacyUseTheSameDurableEscrowProtocolAsForge() throws IOException {
        Path root = repositoryRoot();
        for (String command : java.util.List.of("UnbindCommand.java", "LegacyCommand.java")) {
            String code = Files.readString(root.resolve(
                    "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/" + command));
            int stage = code.indexOf("operations.stage(player, request)");
            int consume = code.indexOf("item.setAmount(0)", stage);
            int save = code.indexOf("player.saveData()", consume);
            int submit = code.indexOf("operations.submit(player, request", save);
            assertTrue(stage > 0 && consume > stage && save > consume && submit > save,
                    command + " must persist an envelope before escrow and submit only after player-data save");
            assertTrue(code.contains("operations.hasPending(player)"));
            assertTrue(code.contains("EquipmentOperationEnvelope.fingerprint"));
            assertFalse(code.contains("economy.burn("));
            assertFalse(code.contains("economy.refund("));
        }
    }

    @Test
    void legacyBlueprintIdentityIsDeterministicInsideThePersistedResult() throws IOException {
        Path root = repositoryRoot();
        String command = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyCommand.java"));
        String blueprints = Files.readString(root.resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/LegacyBlueprintService.java"));
        assertTrue(command.contains("blueprints.create(player, current, messages, operationId)"));
        assertTrue(blueprints.contains("blueprintIdentity.toString()"));
        assertFalse(blueprints.contains("data.set(blueprintId, PersistentDataType.STRING, UUID.randomUUID()"));
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
