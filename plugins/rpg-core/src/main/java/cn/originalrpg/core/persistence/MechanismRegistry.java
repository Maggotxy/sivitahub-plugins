package cn.originalrpg.core.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 0: mechanism directory + Kill Switch.
 * Every Faucet / market / commerce / season path must be listed here
 * with an explicit enabled flag (C-005). Disabled mechanisms fail closed
 * with zero output rather than silently producing.
 */
public final class MechanismRegistry {
    public static final String HUNT_GOLD = "economy.faucet.hunt.gold";
    public static final String DUNGEON_GOLD = "economy.faucet.dungeon.gold";
    public static final String COMMISSION_GOLD = "economy.faucet.commission.gold";
    public static final String MARKET_ESCROW = "market.escrow.enabled";
    public static final String SEASON_SETTLEMENT = "season.settlement.enabled";
    public static final String STARLIGHT_GRANT = "commerce.starlight_grant.enabled";
    public static final String COMMUNITY_PROJECT = "community.project.enabled";

    public static final Map<String, Boolean> DEFAULTS = Map.of(
            HUNT_GOLD, true,
            DUNGEON_GOLD, true,
            COMMISSION_GOLD, true,
            MARKET_ESCROW, true,
            SEASON_SETTLEMENT, true,
            STARLIGHT_GRANT, true,
            COMMUNITY_PROJECT, true
    );

    private final DataSource dataSource;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    public MechanismRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initialize() {
        try (Connection connection = dataSource.getConnection()) {
            for (Map.Entry<String, Boolean> entry : DEFAULTS.entrySet()) {
                try {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by) VALUES (?, ?, 'system') ON CONFLICT DO NOTHING")) {
                        insert.setString(1, entry.getKey());
                        insert.setBoolean(2, entry.getValue());
                        insert.executeUpdate();
                    }
                } catch (SQLException pgEx) {
                    // H2 fallback: MERGE with KEY
                    try (PreparedStatement merge = connection.prepareStatement(
                            "MERGE INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by) KEY(mechanism_id) VALUES (?, ?, 'system')")) {
                        merge.setString(1, entry.getKey());
                        merge.setBoolean(2, entry.getValue());
                        merge.executeUpdate();
                    }
                }
            }
            connection.commit();
            refresh();
        } catch (SQLException exception) {
            throw new IllegalStateException("Mechanism registry init failed", exception);
        }
    }

    public void refresh() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT mechanism_id, enabled FROM rpg_mechanism_switches");
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                cache.put(result.getString(1), result.getBoolean(2));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Mechanism registry refresh failed", exception);
        }
    }

    public boolean isEnabled(String mechanismId) {
        Boolean value = cache.get(mechanismId);
        if (value != null) return value;
        // Unknown mechanisms fail closed per C-005
        return false;
    }

    public void setEnabled(String mechanismId, boolean enabled, String actor) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "INSERT INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by) VALUES (?, ?, ?) ON CONFLICT DO UPDATE SET enabled=EXCLUDED.enabled, updated_by=EXCLUDED.updated_by, updated_at=CURRENT_TIMESTAMP")) {
                    update.setString(1, mechanismId);
                    update.setBoolean(2, enabled);
                    update.setString(3, actor);
                    update.executeUpdate();
                }
            } catch (SQLException pgEx) {
                try (PreparedStatement merge = connection.prepareStatement(
                        "MERGE INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by) KEY(mechanism_id) VALUES (?, ?, ?)")) {
                    merge.setString(1, mechanismId);
                    merge.setBoolean(2, enabled);
                    merge.setString(3, actor);
                    merge.executeUpdate();
                    // H2 MERGE doesn't update updated_at automatically for existing, so follow with update
                    try (PreparedStatement upd = connection.prepareStatement(
                            "UPDATE rpg_mechanism_switches SET enabled=?, updated_by=?, updated_at=CURRENT_TIMESTAMP WHERE mechanism_id=?")) {
                        upd.setBoolean(1, enabled);
                        upd.setString(2, actor);
                        upd.setString(3, mechanismId);
                        upd.executeUpdate();
                    }
                }
            }
            connection.commit();
            cache.put(mechanismId, enabled);
        } catch (SQLException exception) {
            throw new IllegalStateException("Mechanism switch update failed", exception);
        }
    }
}
