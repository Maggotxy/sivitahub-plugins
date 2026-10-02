package cn.originalrpg.pets;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PetRevivalLifecycleContractTest {
    @Test
    void revivalUsesDurableSagaInsteadOfDebitThenRefund() throws Exception {
        String source = source();

        assertTrue(source.contains("petRevivals.begin(new PetRevivalRequest"));
        assertTrue(source.contains("getRepository().updatePet(snapshot)"));
        assertTrue(source.contains("petRevivals.complete(intent.operationId()"));
        assertTrue(source.contains("recoverRevivals(playerId, true)"));
        assertTrue(source.contains("recoverRevivals(pet.getOwner().getUniqueId(), false)"));
        assertFalse(source.contains("economy.burn(playerId, cost"));
        assertFalse(source.contains("refundRevive("));
    }

    @Test
    void everyFutureToBukkitBridgePassesThroughDisableAwareDispatcher() throws Exception {
        String source = source();

        assertFalse(source.contains("whenComplete((result, error) -> getServer().getScheduler().runTask"));
        assertFalse(source.contains("whenComplete((progress, error) -> getServer().getScheduler().runTask"));
        assertTrue(source.contains("private void runMain(Runnable action)"));
        assertTrue(source.contains("if (!isEnabled()) return;"));
        assertTrue(source.contains("if (isEnabled()) action.run();"));
        assertTrue(source.contains("sessionTokens.put(playerId, token)"));
        assertTrue(source.contains("session.token().equals(sessionTokens.get(session.playerId()))"));
        assertTrue(source.contains("private final Set<UUID> revivalPlayerQueries"));
        assertTrue(source.contains("private final Set<UUID> revivalWrites"));
        assertFalse(source.contains("private final Set<UUID> pendingRevives"));
        assertTrue(source.contains("maximumPersistedHealth(stored)"));
        assertTrue(source.contains("if (!isEnabled()) {\n                        revivalWrites.remove(intent.operationId());"));
    }

    @Test
    void oldQuitAndCallbacksCannotRevokeOrActOnANewerAuthenticatedConnection() throws Exception {
        String source = source();

        assertTrue(source.contains("private final Map<UUID, AuthenticatedSession> authenticatedPlayers"));
        assertTrue(source.contains("new AuthenticatedSession(event.player(), token)"));
        assertTrue(source.contains("session.player() != player"));
        assertTrue(source.contains("authenticatedPlayers.remove(playerId, session)"));
        assertTrue(source.contains("sessionTokens.remove(playerId, session.token())"));
        assertTrue(source.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertFalse(source.contains("authenticatedPlayers.remove(playerId);"));
        assertFalse(source.contains("sessionTokens.remove(playerId);"));
        assertTrue(source.contains("catch (IllegalStateException ignored)"));
    }

    @Test
    void buildTargetsTheExactRuntimeMyPetArtifact() throws Exception {
        Path root = repositoryRoot();
        String build = Files.readString(root.resolve("plugins/rpg-pets/build.gradle.kts"), StandardCharsets.UTF_8);

        assertTrue(build.contains("MyPet-4.0.0-Sivita.jar"));
        assertFalse(build.contains("MyPet-4.0.0.jar\""));
    }

    private static String source() throws Exception {
        return Files.readString(repositoryRoot().resolve(
                "plugins/rpg-pets/src/main/java/cn/originalrpg/pets/RpgPetsPlugin.java"),
                StandardCharsets.UTF_8);
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
