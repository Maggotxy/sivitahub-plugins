package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PetCollectionEntry;
import cn.originalrpg.api.model.PetProgress;
import cn.originalrpg.api.model.PetProgressResult;
import cn.originalrpg.api.service.PetProgressService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** JDBC-authoritative companion collection, bond, evolution and skin state. */
public final class JdbcPetProgressService implements PetProgressService {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9._-]{1,31}");
    private static final int MAX_BOND = 10_000;
    private static final int MAX_CAPTURE_COUNT = 1_000_000_000;
    private static final long MAX_BATTLES = 1_000_000_000L;

    private final DataSource dataSource;
    private final Executor executor;

    public JdbcPetProgressService(DataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletionStage<PetProgress> registerCapture(
            UUID ownerId, UUID petId, String speciesId, String personalityId, String traitId
    ) {
        if (ownerId == null || petId == null || !validId(speciesId)
                || !validId(personalityId) || !validId(traitId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid companion capture"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rpg_pet_progress (
                        pet_uuid, owner_uuid, species_id, personality_id, trait_id,
                        bond_points, evolution_stage, selected_skin_id, battles
                    ) VALUES (?, ?, ?, ?, ?, 0, 0, 'natural', 0)
                    ON CONFLICT DO NOTHING
                    """)) {
                statement.setString(1, petId.toString());
                statement.setString(2, ownerId.toString());
                statement.setString(3, speciesId);
                statement.setString(4, personalityId);
                statement.setString(5, traitId);
                inserted = statement.executeUpdate();
            }
            if (inserted == 1) {
                try (PreparedStatement skin = connection.prepareStatement("""
                        INSERT INTO rpg_pet_skins (pet_uuid, skin_id, source)
                        VALUES (?, 'natural', 'capture') ON CONFLICT DO NOTHING
                        """)) {
                    skin.setString(1, petId.toString());
                    skin.executeUpdate();
                }
                incrementCodex(connection, ownerId, speciesId);
            }
            PetProgress progress = load(connection, petId, false);
            if (progress == null || !progress.ownerId().equals(ownerId)) {
                throw new IllegalStateException("Pet UUID is already registered to another owner");
            }
            return progress;
        }));
    }

    @Override
    public CompletionStage<Optional<PetProgress>> progress(UUID ownerId, UUID petId) {
        if (ownerId == null || petId == null) return CompletableFuture.completedFuture(Optional.empty());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PetProgress progress = load(connection, petId, false);
            return progress != null && progress.ownerId().equals(ownerId)
                    ? Optional.of(progress) : Optional.empty();
        }));
    }

    @Override
    public CompletionStage<List<PetCollectionEntry>> collection(UUID ownerId) {
        if (ownerId == null) return CompletableFuture.completedFuture(List.of());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            List<PetCollectionEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT species_id, capture_count, highest_evolution_stage,
                           first_captured_at, last_captured_at
                    FROM rpg_pet_collection WHERE player_uuid = ?
                    ORDER BY first_captured_at, species_id
                    """)) {
                statement.setString(1, ownerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        entries.add(new PetCollectionEntry(
                                result.getString("species_id"), result.getInt("capture_count"),
                                result.getInt("highest_evolution_stage"),
                                instant(result, "first_captured_at"), instant(result, "last_captured_at")));
                    }
                }
            }
            return List.copyOf(entries);
        }));
    }

    @Override
    public CompletionStage<PetProgressResult> rewardBond(
            UUID ownerId, UUID petId, int amount, String source, String idempotencyKey
    ) {
        if (ownerId == null || petId == null || amount < 1 || amount > 100
                || source == null || !SOURCE.matcher(source).matches()
                || idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 96) {
            return CompletableFuture.completedFuture(PetProgressResult.of(PetProgressResult.Status.INVALID, null));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PetProgress current = load(connection, petId, true);
            if (current == null) return PetProgressResult.of(PetProgressResult.Status.NOT_FOUND, null);
            if (!current.ownerId().equals(ownerId)) {
                return PetProgressResult.of(PetProgressResult.Status.OWNER_MISMATCH, current);
            }
            int inserted;
            try (PreparedStatement ledger = connection.prepareStatement("""
                    INSERT INTO rpg_pet_bond_ledger (
                        id, idempotency_key, pet_uuid, player_uuid, source, amount
                    ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                ledger.setString(1, UUID.randomUUID().toString());
                ledger.setString(2, idempotencyKey);
                ledger.setString(3, petId.toString());
                ledger.setString(4, ownerId.toString());
                ledger.setString(5, source);
                ledger.setInt(6, amount);
                inserted = ledger.executeUpdate();
            }
            if (inserted == 0) {
                return PetProgressResult.of(PetProgressResult.Status.DUPLICATE, current);
            }
            int bond = Math.min(MAX_BOND, current.bond() + amount);
            long battles = source.equals("combat") && current.battles() < MAX_BATTLES
                    ? current.battles() + 1L : current.battles();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_pet_progress SET bond_points = ?, battles = ?,
                        updated_at = CURRENT_TIMESTAMP WHERE pet_uuid = ?
                    """)) {
                update.setInt(1, bond);
                update.setLong(2, battles);
                update.setString(3, petId.toString());
                update.executeUpdate();
            }
            return PetProgressResult.of(PetProgressResult.Status.SUCCESS, load(connection, petId, false));
        }));
    }

    @Override
    public CompletionStage<PetProgressResult> evolve(
            UUID ownerId, UUID petId, int expectedStage, int minimumBond, String unlockedSkinId
    ) {
        if (ownerId == null || petId == null || expectedStage < 0 || expectedStage > 1
                || minimumBond < 0 || minimumBond > MAX_BOND || !validId(unlockedSkinId)) {
            return CompletableFuture.completedFuture(PetProgressResult.of(PetProgressResult.Status.INVALID, null));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PetProgress current = load(connection, petId, true);
            if (current == null) return PetProgressResult.of(PetProgressResult.Status.NOT_FOUND, null);
            if (!current.ownerId().equals(ownerId)) {
                return PetProgressResult.of(PetProgressResult.Status.OWNER_MISMATCH, current);
            }
            if (current.evolutionStage() != expectedStage || current.bond() < minimumBond) {
                return PetProgressResult.of(PetProgressResult.Status.REQUIREMENT_NOT_MET, current);
            }
            int nextStage = expectedStage + 1;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_pet_progress SET evolution_stage = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE pet_uuid = ?
                    """)) {
                update.setInt(1, nextStage);
                update.setString(2, petId.toString());
                update.executeUpdate();
            }
            try (PreparedStatement skin = connection.prepareStatement("""
                    INSERT INTO rpg_pet_skins (pet_uuid, skin_id, source)
                    VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                skin.setString(1, petId.toString());
                skin.setString(2, unlockedSkinId);
                skin.setString(3, "evolution_" + nextStage);
                skin.executeUpdate();
            }
            try (PreparedStatement codex = connection.prepareStatement("""
                    UPDATE rpg_pet_collection SET highest_evolution_stage = ?
                    WHERE player_uuid = ? AND species_id = ? AND highest_evolution_stage < ?
                    """)) {
                codex.setInt(1, nextStage);
                codex.setString(2, ownerId.toString());
                codex.setString(3, current.speciesId());
                codex.setInt(4, nextStage);
                codex.executeUpdate();
            }
            return PetProgressResult.of(PetProgressResult.Status.SUCCESS, load(connection, petId, false));
        }));
    }

    @Override
    public CompletionStage<PetProgressResult> selectSkin(UUID ownerId, UUID petId, String skinId) {
        if (ownerId == null || petId == null || !validId(skinId)) {
            return CompletableFuture.completedFuture(PetProgressResult.of(PetProgressResult.Status.INVALID, null));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PetProgress current = load(connection, petId, true);
            if (current == null) return PetProgressResult.of(PetProgressResult.Status.NOT_FOUND, null);
            if (!current.ownerId().equals(ownerId)) {
                return PetProgressResult.of(PetProgressResult.Status.OWNER_MISMATCH, current);
            }
            if (!current.unlockedSkinIds().contains(skinId)) {
                return PetProgressResult.of(PetProgressResult.Status.SKIN_LOCKED, current);
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_pet_progress SET selected_skin_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE pet_uuid = ?
                    """)) {
                update.setString(1, skinId);
                update.setString(2, petId.toString());
                update.executeUpdate();
            }
            return PetProgressResult.of(PetProgressResult.Status.SUCCESS, load(connection, petId, false));
        }));
    }

    private PetProgress load(Connection connection, UUID petId, boolean lock) throws SQLException {
        String sql = """
                SELECT pet_uuid, owner_uuid, species_id, personality_id, trait_id,
                       bond_points, evolution_stage, selected_skin_id, battles,
                       created_at, updated_at
                FROM rpg_pet_progress WHERE pet_uuid = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, petId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new PetProgress(
                        UUID.fromString(result.getString("pet_uuid")),
                        UUID.fromString(result.getString("owner_uuid")),
                        result.getString("species_id"), result.getString("personality_id"),
                        result.getString("trait_id"), result.getInt("bond_points"),
                        result.getInt("evolution_stage"), result.getString("selected_skin_id"),
                        result.getLong("battles"), skins(connection, petId),
                        instant(result, "created_at"), instant(result, "updated_at"));
            }
        }
    }

    private Set<String> skins(Connection connection, UUID petId) throws SQLException {
        Set<String> skins = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT skin_id FROM rpg_pet_skins WHERE pet_uuid = ? ORDER BY unlocked_at")) {
            statement.setString(1, petId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) skins.add(result.getString(1));
            }
        }
        return Set.copyOf(skins);
    }

    private void incrementCodex(Connection connection, UUID ownerId, String speciesId) throws SQLException {
        int updated;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_pet_collection SET capture_count = CASE
                        WHEN capture_count < ? THEN capture_count + 1 ELSE capture_count END,
                    last_captured_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND species_id = ?
                """)) {
            update.setInt(1, MAX_CAPTURE_COUNT);
            update.setString(2, ownerId.toString());
            update.setString(3, speciesId);
            updated = update.executeUpdate();
        }
        if (updated == 1) return;
        int inserted;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_pet_collection (
                    player_uuid, species_id, capture_count, highest_evolution_stage
                ) VALUES (?, ?, 1, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, ownerId.toString());
            insert.setString(2, speciesId);
            inserted = insert.executeUpdate();
        }
        if (inserted == 1) return;
        try (PreparedStatement retry = connection.prepareStatement("""
                UPDATE rpg_pet_collection SET capture_count = CASE
                        WHEN capture_count < ? THEN capture_count + 1 ELSE capture_count END,
                    last_captured_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND species_id = ?
                """)) {
            retry.setInt(1, MAX_CAPTURE_COUNT);
            retry.setString(2, ownerId.toString());
            retry.setString(3, speciesId);
            if (retry.executeUpdate() != 1) throw new IllegalStateException("Could not update companion codex");
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Pet progression transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open pet progression transaction", exception);
        }
    }

    private static boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws Exception;
    }
}
