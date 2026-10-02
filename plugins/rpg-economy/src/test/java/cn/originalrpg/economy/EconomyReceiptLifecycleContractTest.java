package cn.originalrpg.economy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EconomyReceiptLifecycleContractTest {
    @Test
    void onlyTheCurrentAuthenticatedPlayerSessionMayDeleteAndPersistAReceipt() throws IOException {
        String source = commandSource();
        int removeMethod = source.indexOf("private boolean removeReceipt");
        int sessionGuard = source.indexOf("if (!currentPlayerSession(player)) return false", removeMethod);
        int persist = source.indexOf("persistReceipts(player, current)", sessionGuard);
        int sessionMethod = source.indexOf("private boolean currentPlayerSession", persist);
        int online = source.indexOf("player.isOnline()", sessionMethod);
        int currentHandle = source.indexOf("Bukkit.getPlayer(player.getUniqueId()) == player", online);

        assertTrue(removeMethod >= 0 && sessionGuard > removeMethod && persist > sessionGuard,
                "a stale login session must retain the idempotent receipt instead of saving player data");
        assertTrue(sessionMethod > persist && online > sessionMethod && currentHandle > online,
                "online status alone is insufficient after a quick reconnect; the Player handle must match");
    }

    @Test
    void evidenceReleaseIsDurableAndRecoverableBeforeACommissionReceiptIsDeleted() throws IOException {
        String source = commandSource();
        int missingMaterial = source.indexOf("if (!remove(player, entry.material(), entry.amount()))");
        int missingStage = source.indexOf("withStage(EconomyReceiptCodec.Stage.RELEASE_PENDING)", missingMaterial);
        int missingRelease = source.indexOf("releaseCommissionReceipt(player, releasing, false)", missingStage);
        int refundQueued = source.indexOf("private void queueCommissionRefund");
        int refundStage = source.indexOf("withStage(EconomyReceiptCodec.Stage.RELEASE_PENDING)", refundQueued);
        int recovery = source.indexOf("receipt.stage() == EconomyReceiptCodec.Stage.RELEASE_PENDING", refundStage);
        int releaseMethod = source.indexOf("private void releaseCommissionReceipt", recovery);
        int releaseSuccessGuard = source.indexOf("if (!ready(player))", releaseMethod);
        int delete = source.indexOf("removeReceipt(player, receipt.id())", releaseSuccessGuard);

        assertTrue(missingMaterial >= 0 && missingStage > missingMaterial && missingRelease > missingStage,
                "material-removal failure must record release intent before the asynchronous evidence release");
        assertTrue(refundQueued >= 0 && refundStage > refundQueued && recovery > refundStage,
                "a durable mailbox refund must retain a recoverable evidence-release receipt");
        assertTrue(releaseMethod > recovery && releaseSuccessGuard > releaseMethod && delete > releaseSuccessGuard,
                "only the current session may delete the receipt after evidence release commits");
    }

    @Test
    void aQueuedRefundCannotFallBackIntoTheRewardSettlementPathAfterReconnect() throws IOException {
        String source = commandSource();
        int commissionRefund = source.indexOf("private void queueCommissionRefund");
        int intent = source.indexOf("withStage(EconomyReceiptCodec.Stage.REFUND_PENDING)", commissionRefund);
        int enqueue = source.indexOf("market.enqueueDelivery", intent);
        int recovery = source.indexOf("receipt.stage() == EconomyReceiptCodec.Stage.REFUND_PENDING", enqueue);
        int commissionResume = source.indexOf("queueCommissionRefund(player, receipt, true", recovery);
        int merchantResume = source.indexOf("queueMerchantRefund(player, receipt, true", recovery);

        assertTrue(commissionRefund >= 0 && intent > commissionRefund && enqueue > intent,
                "refund intent must be persisted before the asynchronous mailbox write");
        assertTrue(recovery > enqueue && commissionResume > recovery && merchantResume > recovery,
                "refund recovery must resume the refund path for both receipt kinds, never settle rewards again");
    }

    @Test
    void operationAndAuthenticationOwnershipSurviveQuickReconnectAndShutdown() throws IOException {
        String source = commandSource();

        assertTrue(source.contains("private final Map<UUID, OperationLease> pending"));
        assertTrue(source.contains("private final Map<UUID, PlayerSession> authenticatedPlayers"));
        assertTrue(source.contains("pending.putIfAbsent(player.getUniqueId(),"));
        assertTrue(source.contains("if (lease != null && lease.player() == player) pending.remove(playerId, lease)"));
        assertTrue(source.contains("authenticatedPlayers.remove(player.getUniqueId(), session)"));
        assertFalse(source.contains("pending.remove(player.getUniqueId())"));
        assertFalse(source.contains("authenticatedPlayers.remove(player.getUniqueId());"));
        assertTrue(source.contains("if (!plugin.isEnabled()) return;"));
        assertTrue(source.contains("catch (IllegalStateException ignored)"));
    }

    private static String commandSource() throws IOException {
        return Files.readString(repositoryRoot().resolve(
                "plugins/rpg-economy/src/main/java/cn/originalrpg/economy/EconomyCommand.java"),
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
