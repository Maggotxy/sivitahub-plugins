package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.EconomyFlow;
import cn.originalrpg.api.model.EconomyReasonTotal;
import cn.originalrpg.api.model.EconomyReport;
import cn.originalrpg.api.service.EconomyAnalyticsService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class JdbcEconomyAnalyticsService implements EconomyAnalyticsService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final DataSource dataSource;
    private final Executor executor;
    private final ZoneId zoneId;

    public JdbcEconomyAnalyticsService(DataSource dataSource, Executor executor) {
        this(dataSource, executor, ZoneId.of("Asia/Shanghai"));
    }

    public JdbcEconomyAnalyticsService(DataSource dataSource, Executor executor, ZoneId zoneId) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.zoneId = zoneId;
    }

    @Override
    public CompletionStage<EconomyReport> dailyReport() {
        return AsyncJdbc.supply(executor, this::load);
    }

    private EconomyReport load() {
        LocalDate today = LocalDate.now(zoneId);
        try (Connection connection = dataSource.getConnection()) {
            List<BigDecimal> balances = balances(connection);
            BigDecimal totalGold = money(balances.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            long totalMerit = scalarLong(connection,
                    "SELECT COALESCE(SUM(merit), 0) FROM rpg_progression_wallets");
            long totalStarlight = scalarLong(connection,
                    "SELECT COALESCE(SUM(credits), 0) FROM rpg_commerce_accounts");
            Timestamp since30d = start(today, 30);
            BigDecimal activeGold30d = activeGold(connection, since30d);
            BigDecimal marketTopSellerShare30d = marketTopSellerShare(connection, since30d);
            long activeListings = stateCount(connection, "rpg_market_listings", "ACTIVE");
            long pendingDeliveries = stateCount(connection, "rpg_market_deliveries", "PENDING");
            long quarantinedListings = stateCount(connection, "rpg_market_listings", "QUARANTINED");
            ListingInventory inventory = listingInventory(connection, today);
            EconomyFlow daily = flow(connection, today, 1);
            EconomyFlow sevenDays = flow(connection, today, 7);
            EconomyFlow thirtyDays = flow(connection, today, 30);
            List<EconomyReasonTotal> sources = reasonTotals(connection, start(today, 7), true);
            List<EconomyReasonTotal> sinks = reasonTotals(connection, start(today, 7), false);
            connection.commit();
            return new EconomyReport(today, balances.size(), totalGold, totalMerit, totalStarlight,
                    activeGold30d, percentile(balances, 0.50), percentile(balances, 0.90),
                    percentile(balances, 0.99), topOnePercentShare(balances, totalGold),
                    marketTopSellerShare30d, activeListings, pendingDeliveries, quarantinedListings,
                    inventory.medianAgeHours(), inventory.p90AgeHours(), inventory.oldestAgeHours(),
                    inventory.sold7d(), inventory.expired7d(),
                    daily, sevenDays, thirtyDays, sources, sinks);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not build economy report", exception);
        }
    }

    private List<BigDecimal> balances(Connection connection) throws SQLException {
        List<BigDecimal> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts ORDER BY balance"); ResultSet rows = query.executeQuery()) {
            while (rows.next()) result.add(money(rows.getBigDecimal(1)));
        }
        return List.copyOf(result);
    }

    private EconomyFlow flow(Connection connection, LocalDate today, int days) throws SQLException {
        Timestamp since = start(today, days);
        BigDecimal minted;
        BigDecimal burned;
        BigDecimal fees;
        BigDecimal volume;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT
                  COALESCE(SUM(CASE WHEN ledger.sender_uuid IS NULL AND ledger.receiver_uuid IS NOT NULL
                                         AND ledger.reversal_of IS NULL
                                    THEN amount ELSE 0 END), 0) AS minted,
                  COALESCE(SUM(CASE WHEN ledger.sender_uuid IS NOT NULL AND ledger.receiver_uuid IS NULL
                                         AND NOT EXISTS (
                                             SELECT 1 FROM rpg_ledger reversal
                                             WHERE reversal.reversal_of = ledger.idempotency_key
                                         )
                                    THEN amount ELSE 0 END), 0) AS burned,
                  COALESCE(SUM(fee), 0) AS fees,
                  COALESCE(SUM(CASE WHEN ledger.sender_uuid IS NOT NULL AND ledger.receiver_uuid IS NOT NULL
                                    THEN amount ELSE 0 END), 0) AS volume
                FROM rpg_ledger ledger WHERE ledger.created_at >= ?
                """)) {
            query.setTimestamp(1, since);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                minted = money(result.getBigDecimal("minted"));
                burned = money(result.getBigDecimal("burned"));
                fees = money(result.getBigDecimal("fees"));
                volume = money(result.getBigDecimal("volume"));
            }
        }
        long mintedMerit;
        long spentMerit;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT
                  COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0) AS minted,
                  COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END), 0) AS spent
                FROM rpg_merit_ledger WHERE created_at >= ?
                """)) {
            query.setTimestamp(1, since);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                mintedMerit = saturatedLong(result.getBigDecimal("minted"));
                spentMerit = saturatedLong(result.getBigDecimal("spent"));
            }
        }
        long mintedStarlight;
        long spentStarlight;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT
                  COALESCE(SUM(CASE WHEN delta > 0 THEN delta ELSE 0 END), 0) AS minted,
                  COALESCE(SUM(CASE WHEN delta < 0 THEN -delta ELSE 0 END), 0) AS spent
                FROM rpg_credit_ledger WHERE created_at >= ?
                """)) {
            query.setTimestamp(1, since);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                mintedStarlight = saturatedLong(result.getBigDecimal("minted"));
                spentStarlight = saturatedLong(result.getBigDecimal("spent"));
            }
        }
        return new EconomyFlow(days, minted, burned, fees, volume,
                mintedMerit, spentMerit, mintedStarlight, spentStarlight);
    }

    private BigDecimal activeGold(Connection connection, Timestamp since) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(balance), 0) FROM rpg_accounts
                WHERE player_uuid IN (
                    SELECT sender_uuid FROM rpg_ledger
                    WHERE created_at >= ? AND sender_uuid IS NOT NULL
                    UNION
                    SELECT receiver_uuid FROM rpg_ledger
                    WHERE created_at >= ? AND receiver_uuid IS NOT NULL
                )
                """)) {
            query.setTimestamp(1, since);
            query.setTimestamp(2, since);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return money(result.getBigDecimal(1));
            }
        }
    }

    private BigDecimal marketTopSellerShare(Connection connection, Timestamp since) throws SQLException {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal maximum = BigDecimal.ZERO;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT seller_uuid, COALESCE(SUM(price), 0) AS volume
                FROM rpg_market_listings
                WHERE state = 'SOLD' AND completed_at >= ?
                GROUP BY seller_uuid
                """)) {
            query.setTimestamp(1, since);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    BigDecimal volume = result.getBigDecimal("volume");
                    total = total.add(volume);
                    maximum = maximum.max(volume);
                }
            }
        }
        return ratio(maximum, total);
    }

    private List<EconomyReasonTotal> reasonTotals(
            Connection connection, Timestamp since, boolean source
    ) throws SQLException {
        Map<String, BigDecimal> totals = new HashMap<>();
        String predicate = source
                ? "ledger.sender_uuid IS NULL AND ledger.receiver_uuid IS NOT NULL "
                    + "AND ledger.reversal_of IS NULL"
                : "ledger.sender_uuid IS NOT NULL AND ledger.receiver_uuid IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM rpg_ledger reversal "
                    + "WHERE reversal.reversal_of = ledger.idempotency_key)";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT ledger.reason, COALESCE(SUM(ledger.amount), 0) AS total "
                        + "FROM rpg_ledger ledger WHERE ledger.created_at >= ? AND "
                        + predicate + " GROUP BY ledger.reason")) {
            query.setTimestamp(1, since);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) totals.put(result.getString("reason"), money(result.getBigDecimal("total")));
            }
        }
        if (!source) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COALESCE(SUM(fee), 0) FROM rpg_ledger WHERE created_at >= ?")) {
                query.setTimestamp(1, since);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    BigDecimal fees = money(result.getBigDecimal(1));
                    if (fees.signum() > 0) totals.put("market-fees", fees);
                }
            }
        }
        return totals.entrySet().stream()
                .map(entry -> new EconomyReasonTotal(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(EconomyReasonTotal::amount).reversed())
                .limit(5)
                .toList();
    }

    private long scalarLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql); ResultSet result = query.executeQuery()) {
            result.next();
            return saturatedLong(result.getBigDecimal(1));
        }
    }

    /**
     * Analytics must never make the economy-health endpoint unavailable just
     * because an append-only ledger has accumulated beyond a signed 64-bit
     * display field. The authoritative rows stay exact; only this legacy
     * report projection saturates at its public model boundary.
     */
    private static long saturatedLong(BigDecimal value) {
        if (value == null || value.signum() <= 0) return 0L;
        BigInteger integer = value.toBigInteger();
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        return integer.compareTo(maximum) >= 0 ? Long.MAX_VALUE : integer.longValue();
    }

    private long stateCount(Connection connection, String table, String state) throws SQLException {
        // The table name is selected only by the two constant call sites above; the state remains bound.
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE state = ?")) {
            query.setString(1, state);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private ListingInventory listingInventory(Connection connection, LocalDate today) throws SQLException {
        Instant now = Instant.now();
        List<BigDecimal> ages = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT created_at FROM rpg_market_listings WHERE state = 'ACTIVE' ORDER BY created_at");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                long minutes = Math.max(0L, Duration.between(rows.getTimestamp(1).toInstant(), now).toMinutes());
                ages.add(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
            }
        }
        ages.sort(Comparator.naturalOrder());
        long sold = completedStateCount(connection, "SOLD", start(today, 7));
        long expired = completedStateCount(connection, "EXPIRED", start(today, 7));
        BigDecimal oldest = ages.isEmpty() ? ZERO : ages.getLast();
        return new ListingInventory(percentile(ages, 0.50), percentile(ages, 0.90), oldest, sold, expired);
    }

    private long completedStateCount(Connection connection, String state, Timestamp since) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_market_listings
                WHERE state = ? AND completed_at >= ?
                """)) {
            query.setString(1, state);
            query.setTimestamp(2, since);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static BigDecimal percentile(List<BigDecimal> sorted, double percentile) {
        if (sorted.isEmpty()) return ZERO;
        double position = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return money(sorted.get(lower));
        BigDecimal fraction = BigDecimal.valueOf(position - lower);
        return money(sorted.get(lower).add(sorted.get(upper).subtract(sorted.get(lower)).multiply(fraction)));
    }

    private static BigDecimal topOnePercentShare(List<BigDecimal> sorted, BigDecimal total) {
        if (sorted.isEmpty() || total.signum() == 0) return BigDecimal.ZERO.setScale(4);
        int count = Math.max(1, (int) Math.ceil(sorted.size() * 0.01));
        BigDecimal top = BigDecimal.ZERO;
        for (int index = sorted.size() - count; index < sorted.size(); index++) top = top.add(sorted.get(index));
        return ratio(top, total);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return BigDecimal.ZERO.setScale(4);
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static Timestamp start(LocalDate today, int days) {
        return Timestamp.valueOf(today.minusDays(days - 1L).atStartOfDay());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record ListingInventory(
            BigDecimal medianAgeHours,
            BigDecimal p90AgeHours,
            BigDecimal oldestAgeHours,
            long sold7d,
            long expired7d
    ) {
    }
}
