package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxDeliveryIntegrationContractTest {
    @Test
    void trustedMailboxBoundaryClearsLegacyEquipmentEscrowLockBeforeDeliveryReceipt() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-market/src/main/java/cn/originalrpg/market/MarketCommand.java"),
                StandardCharsets.UTF_8);
        int method = source.indexOf("private void deliverNext");
        int deserialize = source.indexOf("ItemStack.deserializeBytes", method);
        int clearLegacyLock = source.indexOf("EquipmentOperationMarker.clear(item)", deserialize);
        int setReceipt = source.indexOf(
                "meta.getPersistentDataContainer().set(deliveryMarker", clearLegacyLock);
        int addToInventory = source.indexOf("player.getInventory().addItem(item)", setReceipt);

        assertTrue(method >= 0 && deserialize > method && clearLegacyLock > deserialize
                        && setReceipt > clearLegacyLock && addToInventory > setReceipt,
                "a legacy equipment escrow marker must be removed only after trusted mailbox "
                        + "deserialization and before the item enters player inventory");
    }

    @Test
    void staleListingCallbackCannotSaveOverANewerLoginAndOrphanMarkersAreRecovered() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-market/src/main/java/cn/originalrpg/market/MarketCommand.java"),
                StandardCharsets.UTF_8);
        int callback = source.indexOf("private void completePreparation");
        int liveHandle = source.indexOf("Player current = Bukkit.getPlayer(player.getUniqueId())", callback);
        int readyGuard = source.indexOf("if (!ready(current)) return", liveHandle);
        int clear = source.indexOf("clearListingMarker(current, listingId)", readyGuard);
        int save = source.indexOf("current.saveData()", clear);
        int recovery = source.indexOf("clearOrphanListingMarkers(player, preparedIds, inFlight)");
        int sessionIdentity = source.indexOf("Bukkit.getPlayer(player.getUniqueId()) == player");
        int exactAuthRemoval = source.indexOf("authenticatedPlayers.remove(player.getUniqueId(), player)");
        int recoveryMethod = source.indexOf("private void recoverPreparedListings");
        int inflightGuard = source.indexOf("if (preparingListings.containsKey(playerId))", recoveryMethod);
        int query = source.indexOf("market.preparedListings(playerId)", inflightGuard);

        assertTrue(callback >= 0 && liveHandle > callback && readyGuard > liveHandle
                        && clear > readyGuard && save > clear,
                "a failed preparation must mutate only the current authenticated Player handle");
        assertTrue(recovery > save && sessionIdentity > recovery,
                "offline failure markers need authoritative login reconciliation without stale saves");
        assertTrue(exactAuthRemoval >= 0,
                "an old disconnect must not revoke a newer authenticated Player handle");
        assertTrue(recoveryMethod >= 0 && inflightGuard > recoveryMethod && query > inflightGuard,
                "login reconciliation must not race an uncommitted listing preparation");
    }

    @Test
    void pluginDisableClosesEveryProcessLocalMarketGate() throws IOException {
        String command = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-market/src/main/java/cn/originalrpg/market/MarketCommand.java"),
                StandardCharsets.UTF_8);
        String plugin = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-market/src/main/java/cn/originalrpg/market/RpgMarketPlugin.java"),
                StandardCharsets.UTF_8);
        int close = command.indexOf("void close()");
        assertTrue(close >= 0
                && command.indexOf("authenticatedPlayers.clear()", close) > close
                && command.indexOf("preparingListings.clear()", close) > close
                && command.indexOf("mailboxClaimSessions.clear()", close) > close
                && command.indexOf("mailboxClaims.clear()", close) > close);
        assertTrue(plugin.contains("if (marketCommand != null) marketCommand.close()"));
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
