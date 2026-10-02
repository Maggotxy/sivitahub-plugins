package cn.originalrpg.enhancement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentOperationIntegrationContractTest {
    @Test
    void refinementAndLimitBreakUseDurableOutboxInsteadOfSplitBurnRefund() throws IOException {
        Path root = repositoryRoot();
        for (String file : new String[]{"RefineCommand.java", "LimitBreakCommand.java"}) {
            String code = Files.readString(root.resolve(
                    "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/" + file),
                    StandardCharsets.UTF_8);
            assertTrue(code.contains("EquipmentOperationRequest"), file + " must create a replayable request");
            assertTrue(code.contains("operations.stage(player, request)"), file + " must persist before removal");
            assertTrue(code.contains("player.saveData()"), file + " must flush the staged escrow envelope");
            assertTrue(code.contains("operations.submit(player, request"), file + " must use the atomic outbox");
            assertFalse(code.contains("economy.burn("), file + " must not debit separately from item delivery");
            assertFalse(code.contains("economy.refund("), file + " must not need a split refund path");
        }
    }

    @Test
    void submittedGateSurvivesAuthenticationRevocation() throws IOException {
        Path root = repositoryRoot();
        String refine = Files.readString(root.resolve(
                "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/RefineCommand.java"));
        String limit = Files.readString(root.resolve(
                "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/LimitBreakCommand.java"));
        assertTrue(refine.contains("AuthenticatedPlayerRevokedEvent"));
        assertFalse(revocationMethod(refine).contains("active.remove"));
        assertTrue(limit.contains("AuthenticatedPlayerRevokedEvent"));
        assertFalse(revocationMethod(limit).contains("active.remove"));
    }

    @Test
    void refinedEquipmentIsSerializedAfterSigningAndLoreRefresh() throws IOException {
        String code = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/RefineCommand.java"));
        int mutateCopy = code.indexOf("applyRoll(player, resultItem");
        int sign = code.indexOf("equipment.sign(item)");
        int payload = code.indexOf("operations.itemPayload(resultItem)", mutateCopy);
        int removeOriginal = code.indexOf("current.item().setAmount(0)", payload);
        assertTrue(mutateCopy > 0 && sign > 0 && payload > mutateCopy && removeOriginal > payload,
                "signed PDC/lore result must exist before the live equipment is escrowed");
    }

    @Test
    void durableEnvelopePrecedesEveryEnhancementLocalGate() throws IOException {
        Path root = repositoryRoot();
        for (String file : new String[]{"RefineCommand.java", "LimitBreakCommand.java"}) {
            String code = Files.readString(root.resolve(
                    "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/" + file));
            int persistentGate = code.indexOf("operations.hasPending(player)");
            int localGate = code.indexOf("active.add(player.getUniqueId())", persistentGate);
            assertTrue(persistentGate > 0 && localGate > persistentGate,
                    file + " must recover the durable envelope before admitting another operation");
        }
    }

    @Test
    void completionFeedbackCannotTargetAReplacedLoginSession() throws IOException {
        Path root = repositoryRoot();
        for (String file : new String[]{"RefineCommand.java", "LimitBreakCommand.java"}) {
            String code = Files.readString(root.resolve(
                    "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/" + file));
            int callback = code.indexOf("operations.submit(player, request");
            int guard = code.indexOf("if (!currentSession(player)", callback);
            int identity = code.indexOf("Bukkit.getPlayer(player.getUniqueId()) == player", guard);
            assertTrue(callback > 0 && guard > callback && identity > guard,
                    file + " must reject completion feedback from an obsolete Player handle");
        }
    }

    private static String revocationMethod(String source) {
        int start = source.indexOf("onAuthenticationRevoked");
        int end = source.indexOf("\n    }", start);
        return source.substring(start, end);
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
