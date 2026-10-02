package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.EnergyRecovery;
import cn.originalrpg.api.model.DungeonTicketEscrow;
import cn.originalrpg.api.service.DungeonEntryService;
import cn.originalrpg.api.service.ProfileService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class JdbcDungeonEntryService implements DungeonEntryService {
    private static final int MAX_TICKET_PAYLOAD = 2_000_000;
    private final DataSource dataSource;
    private final Executor executor;
    private final ProfileService profiles;
    private final int startingEnergy;
    private final int energyPerStamina;
    private final JdbcNodeLease nodeLease;

    public JdbcDungeonEntryService(
            DataSource dataSource, Executor executor, ProfileService profiles, int startingEnergy,
            int energyPerStamina, JdbcNodeLease nodeLease
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.profiles = profiles;
        this.startingEnergy = Math.max(1, startingEnergy);
        this.energyPerStamina = Math.max(0, energyPerStamina);
        this.nodeLease = nodeLease;
    }

    @Override
    public CompletionStage<ReservationStatus> reserve(UUID reservationId, UUID playerId, int energyCost) {
        if (reservationId == null || playerId == null || energyCost < 1 || energyCost > 10_000) {
            return CompletableFuture.completedFuture(ReservationStatus.INVALID);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Instant now = nodeLease.now();
            nodeLease.requireOwned(connection, now);
            ProfileEnergy profile = lockProfile(connection, playerId);
            Reservation existing = reservation(connection, reservationId);
            if (existing != null) {
                return existing.playerId().equals(playerId) && existing.energyCost() == energyCost
                        && nodeLease.nodeId().equals(existing.ownerNode())
                        && nodeLease.processToken().equals(existing.ownerProcess())
                        ? ReservationStatus.DUPLICATE : ReservationStatus.INVALID;
            }
            if (profile.energy() < energyCost) return ReservationStatus.INSUFFICIENT_ENERGY;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_dungeon_energy_reservations
                        (id, player_uuid, energy_cost, state, owner_node, owner_process)
                    VALUES (?, ?, ?, 'RESERVED', ?, ?)
                    """)) {
                insert.setString(1, reservationId.toString());
                insert.setString(2, playerId.toString());
                insert.setInt(3, energyCost);
                insert.setString(4, nodeLease.nodeId());
                insert.setString(5, nodeLease.processToken());
                insert.executeUpdate();
            }
            updateEnergy(connection, playerId, profile.energy() - energyCost);
            return ReservationStatus.SUCCESS;
        })).thenApply(status -> {
            if (status == ReservationStatus.SUCCESS) profiles.evict(playerId);
            return status;
        });
    }

    @Override
    public CompletionStage<Boolean> escrowTicket(
            UUID reservationId, UUID playerId, String ticketId, String itemPayload
    ) {
        if (reservationId == null || playerId == null || ticketId == null || ticketId.isBlank()
                || ticketId.length() > 64 || itemPayload == null || itemPayload.isBlank()
                || itemPayload.length() > MAX_TICKET_PAYLOAD) {
            return CompletableFuture.completedFuture(false);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            nodeLease.requireOwned(connection, nodeLease.now());
            Reservation reservation = reservation(connection, reservationId);
            if (!ownedByThisProcess(reservation) || !reservation.playerId().equals(playerId)
                    || !reservation.state().equals("RESERVED")) return false;
            DungeonTicketEscrow existing = ticketEscrow(connection, reservationId);
            if (existing != null) {
                return existing.playerId().equals(playerId) && existing.ticketId().equals(ticketId)
                        && existing.itemPayload().equals(itemPayload);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_dungeon_ticket_escrows
                        (reservation_id, player_uuid, ticket_id, item_payload, state)
                    VALUES (?, ?, ?, ?, 'HELD')
                    """)) {
                insert.setString(1, reservationId.toString());
                insert.setString(2, playerId.toString());
                insert.setString(3, ticketId);
                insert.setString(4, itemPayload);
                return insert.executeUpdate() == 1;
            }
        }));
    }

    @Override
    public CompletionStage<Optional<DungeonTicketEscrow>> ticketEscrow(
            UUID reservationId, UUID playerId
    ) {
        if (reservationId == null || playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            nodeLease.requireOwned(connection, nodeLease.now());
            Reservation reservation = reservation(connection, reservationId);
            // This is a reconciliation read, not an ownership-bearing mutation.
            // After a restart recoverAll() returns an old process's HELD ticket
            // through the mailbox while the matching marked inventory stack can
            // still be waiting in player data.  The replacement process must be
            // able to see that durable escrow row; treating another process's
            // already-settled reservation as absent would keep the physical
            // ticket as well and duplicate it.
            if (reservation == null || !reservation.playerId().equals(playerId)) return Optional.empty();
            DungeonTicketEscrow escrow = ticketEscrow(connection, reservationId);
            return escrow != null && escrow.playerId().equals(playerId) ? Optional.of(escrow) : Optional.empty();
        }));
    }

    @Override
    public CompletionStage<Boolean> commit(UUID reservationId, UUID playerId) {
        if (reservationId == null || playerId == null) return CompletableFuture.completedFuture(false);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Instant now = nodeLease.now();
            nodeLease.requireOwned(connection, now);
            Reservation current = reservation(connection, reservationId);
            if (!ownedByThisProcess(current) || !current.playerId().equals(playerId)) return false;
            if (current.state().equals("COMMITTED")) {
                // Idempotent replay also repairs a historical/ambiguous commit
                // whose reservation committed but ticket settlement did not.
                settleCommittedTickets(connection, Map.of(playerId, reservationId));
                return true;
            }
            if (!current.state().equals("RESERVED")) return false;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_dungeon_energy_reservations
                    SET state = 'COMMITTED', settled_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND state = 'RESERVED' AND owner_node = ? AND owner_process = ?
                    """)) {
                update.setString(1, reservationId.toString());
                update.setString(2, nodeLease.nodeId());
                update.setString(3, nodeLease.processToken());
                boolean committed = update.executeUpdate() == 1;
                if (committed) settleCommittedTickets(connection, Map.of(playerId, reservationId));
                return committed;
            }
        }));
    }

    @Override
    public CompletionStage<Boolean> commitAll(Map<UUID, UUID> reservations) {
        if (reservations == null || reservations.isEmpty() || reservations.size() > 8
                || reservations.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null)) {
            return CompletableFuture.completedFuture(false);
        }
        Map<UUID, UUID> immutable = Map.copyOf(reservations);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Instant now = nodeLease.now();
            nodeLease.requireOwned(connection, now);
            String partyState = null;
            for (Map.Entry<UUID, UUID> entry : immutable.entrySet()) {
                Reservation current = reservation(connection, entry.getValue());
                if (!ownedByThisProcess(current) || !current.playerId().equals(entry.getKey())
                        || (!current.state().equals("RESERVED") && !current.state().equals("COMMITTED"))) {
                    return false;
                }
                if (partyState == null) partyState = current.state();
                else if (!partyState.equals(current.state())) return false;
            }
            if ("COMMITTED".equals(partyState)) {
                // See the single-player commit path: a replay is also the
                // self-healing opportunity for any still-HELD ticket rows.
                settleCommittedTickets(connection, immutable);
                return true;
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_dungeon_energy_reservations
                    SET state = 'COMMITTED', settled_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND player_uuid = ? AND state = 'RESERVED'
                      AND owner_node = ? AND owner_process = ?
                    """)) {
                for (Map.Entry<UUID, UUID> entry : immutable.entrySet()) {
                    update.setString(1, entry.getValue().toString());
                    update.setString(2, entry.getKey().toString());
                    update.setString(3, nodeLease.nodeId());
                    update.setString(4, nodeLease.processToken());
                    update.addBatch();
                }
                for (int changed : update.executeBatch()) {
                    if (changed != 1) throw new IllegalStateException(
                            "Dungeon party reservation changed during commit");
                }
            }
            settleCommittedTickets(connection, immutable);
            return true;
        }));
    }

    @Override
    public CompletionStage<PartyCommitStatus> partyCommitStatus(Map<UUID, UUID> reservations) {
        if (reservations == null || reservations.isEmpty() || reservations.size() > 8
                || reservations.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null)) {
            return CompletableFuture.completedFuture(PartyCommitStatus.INVALID);
        }
        Map<UUID, UUID> immutable = Map.copyOf(reservations);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            nodeLease.requireOwned(connection, nodeLease.now());
            String state = null;
            for (Map.Entry<UUID, UUID> entry : immutable.entrySet()) {
                Reservation current = reservation(connection, entry.getValue());
                if (!ownedByThisProcess(current) || !current.playerId().equals(entry.getKey())
                        || (!current.state().equals("RESERVED") && !current.state().equals("COMMITTED"))) {
                    return PartyCommitStatus.INVALID;
                }
                if (state == null) state = current.state();
                else if (!state.equals(current.state())) return PartyCommitStatus.INVALID;
            }
            return "COMMITTED".equals(state) ? PartyCommitStatus.COMMITTED : PartyCommitStatus.RESERVED;
        }));
    }

    @Override
    public CompletionStage<Integer> refund(UUID reservationId, UUID playerId) {
        if (reservationId == null || playerId == null) return CompletableFuture.completedFuture(0);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            nodeLease.requireOwned(connection, nodeLease.now());
            ProfileEnergy profile = lockProfile(connection, playerId);
            Reservation current = reservation(connection, reservationId);
            if (!ownedByThisProcess(current) || !current.playerId().equals(playerId)
                    || !current.state().equals("RESERVED")) return 0;
            int updated = Math.min(profile.energyCap(), profile.energy() + current.energyCost());
            updateEnergy(connection, playerId, updated);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_dungeon_energy_reservations
                    SET state = 'REFUNDED', settled_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND state = 'RESERVED' AND owner_node = ? AND owner_process = ?
                    """)) {
                update.setString(1, reservationId.toString());
                update.setString(2, nodeLease.nodeId());
                update.setString(3, nodeLease.processToken());
                if (update.executeUpdate() != 1) throw new IllegalStateException("Dungeon reservation changed during refund");
            }
            returnTicket(connection, reservationId, playerId);
            return updated - profile.energy();
        })).thenApply(refunded -> {
            if (refunded > 0) profiles.evict(playerId);
            return refunded;
        });
    }

    @Override
    public CompletionStage<EnergyRecovery> recoverAll() {
        return AsyncJdbc.supply(executor, this::recoverOrphaned).thenApply(result -> {
            result.players().forEach(profiles::evict);
            return result.recovery();
        });
    }

    /**
     * Recovers only reservations whose exact process token has no live node
     * lease. Every owner group uses a short transaction so one large cleanup
     * cannot hold locks for healthy nodes or starve their heartbeats.
     */
    private RecoveryResult recoverOrphaned() {
        RecoveryResult total = recoverOwner(null);
        for (ReservationOwner owner : reservationOwners()) {
            RecoveryResult recovered = inTransaction(connection -> {
                if (nodeLease.ownerIsActiveForUpdate(
                        connection, owner.nodeId(), owner.processToken(), nodeLease.now())) {
                    return RecoveryResult.EMPTY;
                }
                return recoverOwner(connection, owner);
            });
            total = total.plus(recovered);
        }
        return total;
    }

    private RecoveryResult recoverOwner(ReservationOwner owner) {
        return inTransaction(connection -> recoverOwner(connection, owner));
    }

    private RecoveryResult recoverOwner(Connection connection, ReservationOwner owner) throws SQLException {
        List<UUID> candidates = new ArrayList<>();
        String playerSql = owner == null ? """
                SELECT DISTINCT player_uuid FROM rpg_dungeon_energy_reservations
                WHERE state = 'RESERVED' AND (owner_node IS NULL OR owner_process IS NULL)
                ORDER BY player_uuid
                """ : """
                SELECT DISTINCT player_uuid FROM rpg_dungeon_energy_reservations
                WHERE state = 'RESERVED' AND owner_node = ? AND owner_process = ?
                ORDER BY player_uuid
                """;
        try (PreparedStatement query = connection.prepareStatement(playerSql)) {
            bindOwner(query, owner, 1);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) candidates.add(UUID.fromString(result.getString(1)));
            }
        }

        int reservations = 0;
        int restored = 0;
        Set<UUID> changedPlayers = new java.util.HashSet<>();
        for (UUID playerId : candidates) {
            ProfileEnergy profile = lockProfile(connection, playerId);
            int count = 0;
            int reservedEnergy = 0;
            String reservationSql = owner == null ? """
                    SELECT energy_cost FROM rpg_dungeon_energy_reservations
                    WHERE player_uuid = ? AND state = 'RESERVED'
                      AND (owner_node IS NULL OR owner_process IS NULL) FOR UPDATE
                    """ : """
                    SELECT energy_cost FROM rpg_dungeon_energy_reservations
                    WHERE player_uuid = ? AND state = 'RESERVED'
                      AND owner_node = ? AND owner_process = ? FOR UPDATE
                    """;
            try (PreparedStatement query = connection.prepareStatement(reservationSql)) {
                query.setString(1, playerId.toString());
                bindOwner(query, owner, 2);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        count++;
                        reservedEnergy = Math.addExact(reservedEnergy, result.getInt(1));
                    }
                }
            }
            if (count == 0) continue;
            int updated = Math.min(profile.energyCap(), Math.addExact(profile.energy(), reservedEnergy));
            updateEnergy(connection, playerId, updated);
            String updateSql = owner == null ? """
                    UPDATE rpg_dungeon_energy_reservations
                    SET state = 'REFUNDED', settled_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ? AND state = 'RESERVED'
                      AND (owner_node IS NULL OR owner_process IS NULL)
                    """ : """
                    UPDATE rpg_dungeon_energy_reservations
                    SET state = 'REFUNDED', settled_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ? AND state = 'RESERVED'
                      AND owner_node = ? AND owner_process = ?
                    """;
            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                update.setString(1, playerId.toString());
                bindOwner(update, owner, 2);
                if (update.executeUpdate() != count) throw new IllegalStateException("Dungeon recovery count changed");
            }
            returnHeldTickets(connection, playerId, owner);
            reservations += count;
            restored += updated - profile.energy();
            changedPlayers.add(playerId);
        }
        return new RecoveryResult(new EnergyRecovery(reservations, restored), Set.copyOf(changedPlayers));
    }

    private List<ReservationOwner> reservationOwners() {
        return inTransaction(connection -> {
            List<ReservationOwner> owners = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT DISTINCT owner_node, owner_process
                    FROM rpg_dungeon_energy_reservations
                    WHERE state = 'RESERVED' AND owner_node IS NOT NULL AND owner_process IS NOT NULL
                    """); ResultSet result = query.executeQuery()) {
                while (result.next()) owners.add(new ReservationOwner(
                        result.getString("owner_node"), result.getString("owner_process")));
            }
            owners.sort(Comparator.comparing(ReservationOwner::nodeId)
                    .thenComparing(ReservationOwner::processToken));
            return List.copyOf(owners);
        });
    }

    private static void bindOwner(PreparedStatement statement, ReservationOwner owner, int firstIndex)
            throws SQLException {
        if (owner == null) return;
        statement.setString(firstIndex, owner.nodeId());
        statement.setString(firstIndex + 1, owner.processToken());
    }

    private boolean ownedByThisProcess(Reservation reservation) {
        return reservation != null && nodeLease.nodeId().equals(reservation.ownerNode())
                && nodeLease.processToken().equals(reservation.ownerProcess());
    }

    private ProfileEnergy lockProfile(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_profiles (
                    player_uuid, archetype, ascension, level, experience, energy,
                    unspent_attribute_points, skill_points, defense, strength, agility, intelligence, stamina
                ) VALUES (?, ?, 0, 1, 0, ?, 5, 1, 0, 0, 0, 0, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, Archetype.UNCHOSEN.name());
            insert.setInt(3, startingEnergy);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT energy, stamina FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Dungeon profile disappeared");
                int cap = Math.addExact(startingEnergy, Math.multiplyExact(result.getInt("stamina"), energyPerStamina));
                return new ProfileEnergy(Math.min(result.getInt("energy"), cap), cap);
            }
        }
    }

    private Reservation reservation(Connection connection, UUID reservationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, energy_cost, state, owner_node, owner_process
                FROM rpg_dungeon_energy_reservations
                WHERE id = ? FOR UPDATE
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new Reservation(
                        UUID.fromString(result.getString("player_uuid")), result.getInt("energy_cost"),
                        result.getString("state"), result.getString("owner_node"),
                        result.getString("owner_process")) : null;
            }
        }
    }

    private DungeonTicketEscrow ticketEscrow(Connection connection, UUID reservationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, ticket_id, item_payload, created_at
                FROM rpg_dungeon_ticket_escrows WHERE reservation_id = ? FOR UPDATE
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new DungeonTicketEscrow(
                        reservationId, UUID.fromString(result.getString("player_uuid")),
                        result.getString("ticket_id"), result.getString("item_payload"),
                        result.getTimestamp("created_at").toInstant()) : null;
            }
        }
    }

    private void settleCommittedTickets(Connection connection, Map<UUID, UUID> reservations) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_dungeon_ticket_escrows SET state = 'CONSUMED', settled_at = CURRENT_TIMESTAMP
                WHERE reservation_id = ? AND player_uuid = ? AND state = 'HELD'
                """)) {
            for (Map.Entry<UUID, UUID> entry : reservations.entrySet()) {
                update.setString(1, entry.getValue().toString());
                update.setString(2, entry.getKey().toString());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void returnTicket(Connection connection, UUID reservationId, UUID playerId) throws SQLException {
        DungeonTicketEscrow escrow = ticketEscrow(connection, reservationId);
        if (escrow == null || !escrow.playerId().equals(playerId)) return;
        if (!ticketState(connection, reservationId).equals("HELD")) return;
        UUID deliveryId = stableTicketDeliveryId(reservationId);
        enqueueTicketDelivery(connection, deliveryId, escrow);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_dungeon_ticket_escrows
                SET state = 'RETURNED', delivery_id = ?, settled_at = CURRENT_TIMESTAMP
                WHERE reservation_id = ? AND player_uuid = ? AND state = 'HELD'
                """)) {
            update.setString(1, deliveryId.toString());
            update.setString(2, reservationId.toString());
            update.setString(3, playerId.toString());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Dungeon ticket escrow changed during refund");
        }
    }

    private void returnHeldTickets(Connection connection, UUID playerId, ReservationOwner owner) throws SQLException {
        String sql = owner == null ? """
                SELECT t.reservation_id FROM rpg_dungeon_ticket_escrows t
                JOIN rpg_dungeon_energy_reservations r ON r.id = t.reservation_id
                WHERE t.player_uuid = ? AND t.state = 'HELD'
                  AND (r.owner_node IS NULL OR r.owner_process IS NULL)
                """ : """
                SELECT t.reservation_id FROM rpg_dungeon_ticket_escrows t
                JOIN rpg_dungeon_energy_reservations r ON r.id = t.reservation_id
                WHERE t.player_uuid = ? AND t.state = 'HELD'
                  AND r.owner_node = ? AND r.owner_process = ?
                """;
        List<UUID> reservations = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            bindOwner(query, owner, 2);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) reservations.add(UUID.fromString(result.getString(1)));
            }
        }
        for (UUID reservationId : reservations) returnTicket(connection, reservationId, playerId);
    }

    private String ticketState(Connection connection, UUID reservationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT state FROM rpg_dungeon_ticket_escrows WHERE reservation_id = ?
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        }
    }

    private void enqueueTicketDelivery(
            Connection connection, UUID deliveryId, DungeonTicketEscrow escrow
    ) throws SQLException {
        String source = "dungeon-ticket-return:" + escrow.ticketId();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, deliveryId.toString());
            insert.setString(2, escrow.playerId().toString());
            insert.setString(3, escrow.itemPayload());
            insert.setString(4, source);
            if (insert.executeUpdate() == 1) return;
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, item_payload, source FROM rpg_market_deliveries WHERE id = ?
                """)) {
            query.setString(1, deliveryId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (result.next() && escrow.playerId().toString().equals(result.getString("player_uuid"))
                        && escrow.itemPayload().equals(result.getString("item_payload"))
                        && source.equals(result.getString("source"))) return;
            }
        }
        throw new IllegalStateException("Dungeon ticket return delivery identity collision");
    }

    private static UUID stableTicketDeliveryId(UUID reservationId) {
        return UUID.nameUUIDFromBytes(("dungeon-ticket-return:" + reservationId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void updateEnergy(Connection connection, UUID playerId, int energy) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_profiles SET energy = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?
                """)) {
            update.setInt(1, energy);
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Dungeon profile update failed");
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T value = operation.apply(connection);
                connection.commit();
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Dungeon entry transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open dungeon entry transaction", exception);
        }
    }

    private record ProfileEnergy(int energy, int energyCap) {
    }

    private record Reservation(
            UUID playerId, int energyCost, String state, String ownerNode, String ownerProcess
    ) {
    }

    private record RecoveryResult(EnergyRecovery recovery, Set<UUID> players) {
        private static final RecoveryResult EMPTY = new RecoveryResult(new EnergyRecovery(0, 0), Set.of());

        private RecoveryResult plus(RecoveryResult other) {
            if (other.recovery().reservations() == 0) return this;
            Set<UUID> combined = new java.util.HashSet<>(players);
            combined.addAll(other.players);
            return new RecoveryResult(new EnergyRecovery(
                    Math.addExact(recovery.reservations(), other.recovery.reservations()),
                    Math.addExact(recovery.energy(), other.recovery.energy())), Set.copyOf(combined));
        }
    }

    private record ReservationOwner(String nodeId, String processToken) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
