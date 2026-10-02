package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonEntryCommitAmbiguityContractTest {
    @Test
    void ambiguousCommitQueriesDurableStateBeforeRefundingOrConsumingTickets() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonCommand.java"),
                StandardCharsets.UTF_8);

        int commit = source.indexOf("entries.commitAll(reservations).whenComplete");
        int resolve = source.indexOf("resolveAmbiguousCommit(", commit);
        int status = source.indexOf("entries.partyCommitStatus(reservations)", resolve);
        int committed = source.indexOf("PartyCommitStatus.COMMITTED", status);
        int reserved = source.indexOf("PartyCommitStatus.RESERVED", committed);
        int refund = source.indexOf("rollbackEntry(reservations", reserved);

        assertTrue(commit >= 0 && resolve > commit && status > resolve);
        assertTrue(committed > status && reserved > committed && refund > reserved,
                "only an authoritative all-RESERVED result may refund an ambiguous commit");
    }

    @Test
    void instanceIsHeldAcrossTeleportAndCommitDisambiguation() throws IOException {
        Path root = repositoryRoot();
        String command = Files.readString(root.resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonCommand.java"));
        String manager = Files.readString(root.resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java"));

        int hold = command.indexOf("manager.holdEntrySettlement(world)");
        int teleport = command.indexOf("member.teleportAsync(destination)", hold);
        int commit = command.indexOf("entries.commitAll(reservations)", teleport);
        int release = command.indexOf("manager.releaseEntrySettlement(world, entryHold)", commit);

        assertTrue(hold >= 0 && teleport > hold && commit > teleport && release > commit);
        assertTrue(manager.contains("entrySettlementHolds.containsKey(worldName) || hasPendingRewardIntent(worldName)"));
        assertTrue(manager.contains("if (!hasInstanceHold(instance)) releaseInstance(previousWorld)"));
    }

    @Test
    void hiddenTicketIsDurablyEscrowedBeforeInventoryRemovalAndTeleport() throws IOException {
        String command = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonCommand.java"));
        int preparation = command.indexOf("prepareTicketEscrows(members, reservations, requiredTicket)");
        int consume = command.indexOf("consumeEscrowedTickets(", preparation);
        int teleport = command.indexOf("beginPartyTeleport(", consume);
        int commit = command.indexOf("entries.commitAll(reservations)", teleport);
        int escrow = command.indexOf("entries.escrowTicket(", commit);
        int refund = command.indexOf("entries.refund(reservationId, playerId)");

        assertTrue(preparation >= 0 && consume > preparation && teleport > consume && commit > teleport);
        assertTrue(escrow > commit, "the preparation helper must durably escrow its candidate");
        assertTrue(refund > 0 && command.contains("entries.ticketEscrow("));
        assertTrue(command.contains("ticket_entry_escrow"));
        assertTrue(command.contains("onEscrowedTicketDeath"));
        assertTrue(command.contains("onEscrowedTicketOffhandSwap"));
        assertTrue(command.contains("onEscrowedTicketInventoryMove"));
        assertTrue(command.contains("onEscrowedTicketInventoryPickup"));
        assertTrue(command.contains("payloadWithoutEscrowMarker"));
        assertTrue(command.contains("MAXIMUM_TICKET_ESCROW_ATTEMPTS"));
    }

    @Test
    void partialPartyEscrowFailureKeepsCandidatesAvailableForImmediateReconciliation() throws IOException {
        String command = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonCommand.java"));

        int prepare = command.indexOf("private CompletionStage<TicketEscrowPreparation> prepareTicketEscrows");
        int handle = command.indexOf("return stage.handle((allHeld, error)", prepare);
        int candidates = command.indexOf("new TicketEscrowPreparation(List.copyOf(candidates)", handle);
        int reconcile = command.indexOf("reconcileFailedTicketEscrows(result)");

        assertTrue(prepare >= 0 && handle > prepare && candidates > handle && reconcile >= 0);
        assertTrue(!command.substring(handle, candidates).contains("CompletionException"),
                "an exceptional future must retain the marked candidates for rollback reconciliation");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("plugins/rpg-dungeons"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Could not locate repository root");
        return current;
    }
}
