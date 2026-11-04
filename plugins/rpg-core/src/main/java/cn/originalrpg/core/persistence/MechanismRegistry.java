package cn.originalrpg.core.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

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

    /**
     * C-005 四件套里 Kill Switch 一腿的权威清单：机制 id -> 该机制的消费者入口。
     * 门禁测试按此清单逐个校验每个机制都有真实调用点。
     */
    public static final Map<String, String> FAUCET_ENTRY_POINTS = Map.of(
            HUNT_GOLD, "JdbcActivityEconomyService",
            DUNGEON_GOLD, "JdbcDungeonRewardService",
            COMMISSION_GOLD, "JdbcActivityEconomyService",
            MARKET_ESCROW, "JdbcMarketService",
            SEASON_SETTLEMENT, "JdbcSeasonSettlementService",
            STARLIGHT_GRANT, "JdbcCommerceService",
            COMMUNITY_PROJECT, "JdbcCommunityService"
    );

    private final DataSource dataSource;
    // Replacing the whole snapshot also removes switches deleted by an operator.
    // Mutating methods serialize their database/cache updates; readers never wait on JDBC.
    private volatile Map<String, Boolean> cache = new ConcurrentHashMap<>();
    /** C-005 指标一腿：被 Kill Switch 拒绝的发币/发物次数。 */
    private final Map<String, LongAdder> deniedCounts = new ConcurrentHashMap<>();
    /** C-005 指标一腿：通过 Kill Switch 的发币/发物次数。 */
    private final Map<String, LongAdder> allowedCounts = new ConcurrentHashMap<>();

    public MechanismRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public synchronized void initialize() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String sql = isH2(connection) ? """
                        MERGE INTO rpg_mechanism_switches AS target
                        USING (VALUES (CAST(? AS VARCHAR(64)), CAST(? AS BOOLEAN))) AS source(mechanism_id, enabled)
                        ON target.mechanism_id = source.mechanism_id
                        WHEN NOT MATCHED THEN
                            INSERT (mechanism_id, enabled, updated_by)
                            VALUES (source.mechanism_id, source.enabled, 'system')
                        """ : """
                        INSERT INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by)
                        VALUES (?, ?, 'system') ON CONFLICT (mechanism_id) DO NOTHING
                        """;
                try (PreparedStatement insert = connection.prepareStatement(sql)) {
                    for (Map.Entry<String, Boolean> entry : DEFAULTS.entrySet()) {
                        insert.setString(1, entry.getKey());
                        insert.setBoolean(2, entry.getValue());
                        insert.executeUpdate();
                    }
                }
                Map<String, Boolean> snapshot = readSwitches(connection);
                connection.commit();
                cache = snapshot;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Mechanism registry init failed", exception);
        }
    }

    public synchronized void refresh() {
        try (Connection connection = dataSource.getConnection()) {
            cache = readSwitches(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Mechanism registry refresh failed", exception);
        }
    }

    private static Map<String, Boolean> readSwitches(Connection connection) throws SQLException {
        Map<String, Boolean> snapshot = new ConcurrentHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT mechanism_id, enabled FROM rpg_mechanism_switches");
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                snapshot.put(result.getString(1), result.getBoolean(2));
            }
        }
        return snapshot;
    }

    public boolean isEnabled(String mechanismId) {
        Boolean value = cache.get(mechanismId);
        if (value != null) return value;
        // Unknown mechanisms fail closed per C-005
        return false;
    }

    /**
     * Faucet 口子的统一 Kill Switch 校验点。
     *
     * <p>与 C-005 一致：未登记的机制 fail closed（未配置即未启用）。
     * 返回 false 时调用方必须不产生任何输出，不得用内存补偿绕过。</p>
     */
    public boolean requireEnabled(String mechanismId) {
        if (isEnabled(mechanismId)) {
            allowedCounts.computeIfAbsent(mechanismId, ignored -> new LongAdder()).increment();
            return true;
        }
        deniedCounts.computeIfAbsent(mechanismId, ignored -> new LongAdder()).increment();
        return false;
    }

    /** C-005 指标：只读报告各机制的通过/拒绝计数。 */
    public Map<String, MechanismMetric> metrics() {
        Map<String, MechanismMetric> snapshot = new java.util.TreeMap<>();
        for (Map.Entry<String, Boolean> entry : DEFAULTS.entrySet()) {
            LongAdder denied = deniedCounts.get(entry.getKey());
            LongAdder allowed = allowedCounts.get(entry.getKey());
            snapshot.put(entry.getKey(), new MechanismMetric(
                    isEnabled(entry.getKey()),
                    allowed == null ? 0L : allowed.sum(),
                    denied == null ? 0L : denied.sum()));
        }
        return Map.copyOf(snapshot);
    }

    public List<String> mechanismIds() {
        return List.copyOf(DEFAULTS.keySet());
    }

    /** 单个机制的 Kill Switch 指标快照。 */
    public record MechanismMetric(boolean enabled, long allowed, long denied) {
    }

    public synchronized void setEnabled(String mechanismId, boolean enabled, String actor) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String sql = isH2(connection) ? """
                        MERGE INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by, updated_at)
                        KEY(mechanism_id) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """ : """
                        INSERT INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by) VALUES (?, ?, ?)
                        ON CONFLICT (mechanism_id) DO UPDATE SET enabled=EXCLUDED.enabled,
                            updated_by=EXCLUDED.updated_by, updated_at=CURRENT_TIMESTAMP
                        """;
                try (PreparedStatement update = connection.prepareStatement(sql)) {
                    update.setString(1, mechanismId);
                    update.setBoolean(2, enabled);
                    update.setString(3, actor);
                    update.executeUpdate();
                }
                connection.commit();
                cache.put(mechanismId, enabled);
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Mechanism switch update failed", exception);
        }
    }

    private static boolean isH2(Connection connection) throws SQLException {
        return switch (connection.getMetaData().getDatabaseProductName()) {
            case "H2" -> true;
            case "PostgreSQL" -> false;
            default -> throw new SQLException("Unsupported mechanism registry database");
        };
    }

    private static void rollback(Connection connection, SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
