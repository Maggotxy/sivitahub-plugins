package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CurrencyExchangeQuote;
import cn.originalrpg.api.model.CurrencyExchangeResult;
import cn.originalrpg.api.model.WealthBalance;
import cn.originalrpg.api.service.CurrencyExchangeService;
import org.bukkit.configuration.ConfigurationSection;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.logging.Logger;

public final class JdbcCurrencyExchangeService implements CurrencyExchangeService {
    private static final Logger LOGGER = Logger.getLogger(JdbcCurrencyExchangeService.class.getName());
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{3,80}");
    private static final Pattern ROUTE = Pattern.compile("[a-z0-9-]{3,48}");
    private static final BigDecimal MAX_GOLD = new BigDecimal("1000000000000.00");
    private static final long MAX_BALANCE = 1_000_000_000L;
    private static final long MAX_UNITS = 10_000L;

    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;
    private final ZoneId zoneId;
    private final Map<String, Rule> rules;

    public JdbcCurrencyExchangeService(
            DataSource dataSource,
            Executor executor,
            BigDecimal startingBalance,
            ConfigurationSection config
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
        zoneId = ZoneId.of(config == null ? "Asia/Shanghai" : config.getString("timezone", "Asia/Shanghai"));
        Map<String, Rule> loadedRules = loadRules(config == null ? null : config.getConfigurationSection("routes"));
        WealthExchangeIntegrity.Values values = referenceValues(config);
        Set<String> unsafe = WealthExchangeIntegrity.unsafeRoutes(
                loadedRules.values().stream().map(rule -> rule.quote(0L, 0L)).toList(), values);
        if (!unsafe.isEmpty()) {
            LOGGER.severe("Disabled value-increasing wealth exchange routes: " + String.join(", ", unsafe));
            loadedRules = loadedRules.entrySet().stream().filter(entry -> !unsafe.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        rules = loadedRules;
        if (rules.isEmpty()) throw new IllegalArgumentException("At least one wealth exchange route is required");
    }

    public JdbcCurrencyExchangeService(
            DataSource dataSource,
            Executor executor,
            BigDecimal startingBalance,
            ZoneId zoneId,
            List<CurrencyExchangeQuote> configuredRules
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
        this.zoneId = zoneId;
        Map<String, Rule> loaded = new LinkedHashMap<>();
        for (CurrencyExchangeQuote quote : configuredRules) {
            Rule rule = new Rule(quote.routeId(), quote.goldCost(), quote.meritCost(), quote.starlightCost(),
                    quote.goldReward(), quote.meritReward(), quote.starlightReward(),
                    quote.dailyLimit(), quote.weeklyLimit());
            validateRule(rule);
            loaded.put(rule.id(), rule);
        }
        rules = java.util.Collections.unmodifiableMap(loaded);
        if (rules.isEmpty()) throw new IllegalArgumentException("At least one wealth exchange route is required");
    }

    @Override
    public CompletionStage<WealthBalance> balance(UUID playerId) {
        if (playerId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerId"));
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> loadBalance(connection, playerId, false)));
    }

    @Override
    public CompletionStage<List<CurrencyExchangeQuote>> quotes(UUID playerId) {
        if (playerId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerId"));
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            LocalDate today = LocalDate.now(zoneId);
            List<CurrencyExchangeQuote> result = new ArrayList<>();
            for (Rule rule : rules.values()) {
                long dailyUsed = used(connection, playerId, rule.id(), "DAY", dayKey(today), false);
                long weeklyUsed = used(connection, playerId, rule.id(), "WEEK", weekKey(today), false);
                result.add(rule.quote(dailyUsed, weeklyUsed));
            }
            return List.copyOf(result);
        }));
    }

    @Override
    public CompletionStage<CurrencyExchangeResult> exchange(
            UUID playerId, String routeId, long units, String idempotencyKey
    ) {
        String normalizedRoute = routeId == null ? "" : routeId.trim().toLowerCase(Locale.ROOT);
        Rule rule = rules.get(normalizedRoute);
        if (playerId == null || rule == null || units <= 0 || units > MAX_UNITS
                || idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            return CompletableFuture.completedFuture(new CurrencyExchangeResult(
                    CurrencyExchangeResult.Status.INVALID, normalizedRoute, Math.max(0L, units), null, 0L, 0L));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> exchange(
                connection, playerId, rule, units, idempotencyKey)));
    }

    private CurrencyExchangeResult exchange(
            Connection connection, UUID playerId, Rule rule, long units, String idempotencyKey
    ) throws SQLException {
        LocalDate today = LocalDate.now(zoneId);
        WealthBalance current = loadBalance(connection, playerId, true);
        long dailyUsed = lockLimit(connection, playerId, rule.id(), "DAY", dayKey(today));
        long weeklyUsed = lockLimit(connection, playerId, rule.id(), "WEEK", weekKey(today));
        if (exchangeExists(connection, idempotencyKey)) {
            return result(CurrencyExchangeResult.Status.DUPLICATE, rule, units, current, dailyUsed, weeklyUsed);
        }
        if (units > rule.dailyLimit() - dailyUsed || units > rule.weeklyLimit() - weeklyUsed) {
            return result(CurrencyExchangeResult.Status.LIMIT_EXCEEDED, rule, units, current, dailyUsed, weeklyUsed);
        }

        BigDecimal goldCost = rule.goldCost().multiply(BigDecimal.valueOf(units));
        BigDecimal goldReward = rule.goldReward().multiply(BigDecimal.valueOf(units));
        long meritCost;
        long meritReward;
        long starlightCost;
        long starlightReward;
        try {
            meritCost = Math.multiplyExact(rule.meritCost(), units);
            meritReward = Math.multiplyExact(rule.meritReward(), units);
            starlightCost = Math.multiplyExact(rule.starlightCost(), units);
            starlightReward = Math.multiplyExact(rule.starlightReward(), units);
        } catch (ArithmeticException exception) {
            return result(CurrencyExchangeResult.Status.INVALID, rule, units, current, dailyUsed, weeklyUsed);
        }
        if (current.gold().compareTo(goldCost) < 0 || current.merit() < meritCost
                || current.starlight() < starlightCost) {
            return result(CurrencyExchangeResult.Status.INSUFFICIENT_FUNDS, rule, units,
                    current, dailyUsed, weeklyUsed);
        }

        BigDecimal updatedGold = money(current.gold().subtract(goldCost).add(goldReward));
        long updatedMerit;
        long updatedStarlight;
        try {
            updatedMerit = Math.addExact(Math.subtractExact(current.merit(), meritCost), meritReward);
            updatedStarlight = Math.addExact(Math.subtractExact(current.starlight(), starlightCost), starlightReward);
        } catch (ArithmeticException exception) {
            return result(CurrencyExchangeResult.Status.BALANCE_LIMIT, rule, units,
                    current, dailyUsed, weeklyUsed);
        }
        if (updatedGold.signum() < 0 || updatedGold.compareTo(MAX_GOLD) > 0
                || updatedMerit < 0 || updatedMerit > MAX_BALANCE
                || updatedStarlight < 0 || updatedStarlight > MAX_BALANCE) {
            return result(CurrencyExchangeResult.Status.BALANCE_LIMIT, rule, units,
                    current, dailyUsed, weeklyUsed);
        }

        updateBalances(connection, playerId, updatedGold, updatedMerit, updatedStarlight);
        BigDecimal goldDelta = money(goldReward.subtract(goldCost));
        long meritDelta = meritReward - meritCost;
        long starlightDelta = starlightReward - starlightCost;
        insertCurrencyLedgers(connection, playerId, idempotencyKey, rule.id(),
                goldDelta, meritDelta, starlightDelta);
        insertExchangeLedger(connection, playerId, idempotencyKey, rule.id(), units,
                goldDelta, meritDelta, starlightDelta);
        updateLimit(connection, playerId, rule.id(), "DAY", dayKey(today), dailyUsed + units);
        updateLimit(connection, playerId, rule.id(), "WEEK", weekKey(today), weeklyUsed + units);
        WealthBalance updated = new WealthBalance(updatedGold, updatedMerit, updatedStarlight);
        return result(CurrencyExchangeResult.Status.SUCCESS, rule, units, updated,
                dailyUsed + units, weeklyUsed + units);
    }

    private CurrencyExchangeResult result(
            CurrencyExchangeResult.Status status,
            Rule rule,
            long units,
            WealthBalance balance,
            long dailyUsed,
            long weeklyUsed
    ) {
        return new CurrencyExchangeResult(status, rule.id(), units, balance,
                Math.max(0L, rule.dailyLimit() - dailyUsed),
                Math.max(0L, rule.weeklyLimit() - weeklyUsed));
    }

    private WealthBalance loadBalance(Connection connection, UUID playerId, boolean lock) throws SQLException {
        ensureAccounts(connection, playerId);
        String suffix = lock ? " FOR UPDATE" : "";
        BigDecimal gold;
        long merit;
        long starlight;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ?" + suffix)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing gold account after creation");
                gold = money(result.getBigDecimal(1));
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ?" + suffix)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing merit wallet after creation");
                merit = result.getLong(1);
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT credits FROM rpg_commerce_accounts WHERE player_uuid = ?" + suffix)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing Starlight account after creation");
                starlight = result.getLong(1);
            }
        }
        return new WealthBalance(gold, merit, starlight);
    }

    private void ensureAccounts(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES (?, 0) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_commerce_accounts (player_uuid, lifetime_paid, credits) VALUES (?, 0, 0) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.executeUpdate();
        }
    }

    private void updateBalances(
            Connection connection, UUID playerId, BigDecimal gold, long merit, long starlight
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, gold);
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_progression_wallets SET merit = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setLong(1, merit);
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_commerce_accounts SET credits = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setLong(1, starlight);
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
    }

    private long lockLimit(
            Connection connection, UUID playerId, String route, String periodType, String periodKey
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_currency_exchange_limits
                (player_uuid, route_id, period_type, period_key, used_units)
                VALUES (?, ?, ?, ?, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, route);
            insert.setString(3, periodType);
            insert.setString(4, periodKey);
            insert.executeUpdate();
        }
        return used(connection, playerId, route, periodType, periodKey, true);
    }

    private long used(
            Connection connection, UUID playerId, String route, String periodType, String periodKey, boolean lock
    ) throws SQLException {
        String sql = "SELECT used_units FROM rpg_currency_exchange_limits "
                + "WHERE player_uuid = ? AND route_id = ? AND period_type = ? AND period_key = ?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            query.setString(2, route);
            query.setString(3, periodType);
            query.setString(4, periodKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private void updateLimit(
            Connection connection, UUID playerId, String route, String periodType, String periodKey, long used
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_currency_exchange_limits SET used_units = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND route_id = ? AND period_type = ? AND period_key = ?
                """)) {
            update.setLong(1, used);
            update.setString(2, playerId.toString());
            update.setString(3, route);
            update.setString(4, periodType);
            update.setString(5, periodKey);
            update.executeUpdate();
        }
    }

    private boolean exchangeExists(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_currency_exchange_ledger WHERE idempotency_key = ?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private void insertExchangeLedger(
            Connection connection,
            UUID playerId,
            String key,
            String route,
            long units,
            BigDecimal goldDelta,
            long meritDelta,
            long starlightDelta
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_currency_exchange_ledger
                (id, idempotency_key, player_uuid, route_id, units, gold_delta, merit_delta, starlight_delta)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, playerId.toString());
            insert.setString(4, route);
            insert.setLong(5, units);
            insert.setBigDecimal(6, goldDelta);
            insert.setLong(7, meritDelta);
            insert.setLong(8, starlightDelta);
            insert.executeUpdate();
        }
    }

    private void insertCurrencyLedgers(
            Connection connection,
            UUID playerId,
            String key,
            String route,
            BigDecimal goldDelta,
            long meritDelta,
            long starlightDelta
    ) throws SQLException {
        if (goldDelta.signum() != 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_ledger
                    (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                    VALUES (?, ?, ?, ?, ?, 0, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, key + ":gold");
                insert.setString(3, goldDelta.signum() < 0 ? playerId.toString() : null);
                insert.setString(4, goldDelta.signum() > 0 ? playerId.toString() : null);
                insert.setBigDecimal(5, goldDelta.abs());
                insert.setString(6, "wealth-exchange:" + route);
                insert.executeUpdate();
            }
        }
        if (meritDelta != 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_merit_ledger
                    (id, idempotency_key, player_uuid, amount, direction, reason)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, key + ":merit");
                insert.setString(3, playerId.toString());
                insert.setLong(4, Math.abs(meritDelta));
                insert.setString(5, meritDelta > 0 ? "CREDIT" : "DEBIT");
                insert.setString(6, "wealth-exchange:" + route);
                insert.executeUpdate();
            }
        }
        if (starlightDelta != 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_credit_ledger
                    (id, external_reference, player_uuid, delta, operator_name)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, key + ":starlight");
                insert.setString(3, playerId.toString());
                insert.setLong(4, starlightDelta);
                insert.setString(5, "wealth-exchange");
                insert.executeUpdate();
            }
        }
    }

    private Map<String, Rule> loadRules(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Rule> loaded = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            String normalized = id.toLowerCase(Locale.ROOT);
            if (!ROUTE.matcher(normalized).matches()) throw new IllegalArgumentException("Invalid exchange route: " + id);
            ConfigurationSection route = section.getConfigurationSection(id);
            if (route == null) continue;
            Rule rule = new Rule(
                    normalized,
                    money(new BigDecimal(route.getString("cost.gold", "0.00"))),
                    route.getLong("cost.merit", 0L),
                    route.getLong("cost.starlight", 0L),
                    money(new BigDecimal(route.getString("reward.gold", "0.00"))),
                    route.getLong("reward.merit", 0L),
                    route.getLong("reward.starlight", 0L),
                    route.getLong("limits.daily", 0L),
                    route.getLong("limits.weekly", 0L)
            );
            validateRule(rule);
            loaded.put(normalized, rule);
        }
        return java.util.Collections.unmodifiableMap(loaded);
    }

    private static void validateRule(Rule rule) {
        boolean hasCost = rule.goldCost().signum() > 0 || rule.meritCost() > 0 || rule.starlightCost() > 0;
        boolean hasReward = rule.goldReward().signum() > 0 || rule.meritReward() > 0 || rule.starlightReward() > 0;
        if (!hasCost || !hasReward || rule.goldCost().signum() < 0 || rule.meritCost() < 0
                || rule.starlightCost() < 0 || rule.goldReward().signum() < 0 || rule.meritReward() < 0
                || rule.starlightReward() < 0 || rule.dailyLimit() <= 0
                || rule.weeklyLimit() < rule.dailyLimit()) {
            throw new IllegalArgumentException("Invalid exchange rule: " + rule.id());
        }
        if ((rule.goldCost().signum() > 0 && rule.goldReward().signum() > 0)
                || (rule.meritCost() > 0 && rule.meritReward() > 0)
                || (rule.starlightCost() > 0 && rule.starlightReward() > 0)) {
            throw new IllegalArgumentException("A route cannot consume and reward the same currency: " + rule.id());
        }
    }

    private String dayKey(LocalDate date) {
        return date.toString();
    }

    private String weekKey(LocalDate date) {
        WeekFields fields = WeekFields.ISO;
        return date.get(fields.weekBasedYear()) + "-W" + String.format(Locale.ROOT, "%02d", date.get(fields.weekOfWeekBasedYear()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static WealthExchangeIntegrity.Values referenceValues(ConfigurationSection config) {
        ConfigurationSection values = config == null ? null
                : config.getConfigurationSection("integrity-reference-values");
        try {
            return new WealthExchangeIntegrity.Values(
                    new BigDecimal(values == null ? "1.00" : values.getString("gold", "1.00")),
                    new BigDecimal(values == null ? "600.00" : values.getString("merit", "600.00")),
                    new BigDecimal(values == null ? "1400.00" : values.getString("starlight", "1400.00"))
            );
        } catch (NumberFormatException exception) {
            return new WealthExchangeIntegrity.Values(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Currency exchange transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Currency exchange database connection failed", exception);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    private record Rule(
            String id,
            BigDecimal goldCost,
            long meritCost,
            long starlightCost,
            BigDecimal goldReward,
            long meritReward,
            long starlightReward,
            long dailyLimit,
            long weeklyLimit
    ) {
        CurrencyExchangeQuote quote(long dailyUsed, long weeklyUsed) {
            return new CurrencyExchangeQuote(id, goldCost, meritCost, starlightCost,
                    goldReward, meritReward, starlightReward, dailyLimit, weeklyLimit,
                    Math.max(0L, dailyLimit - dailyUsed), Math.max(0L, weeklyLimit - weeklyUsed));
        }
    }
}
