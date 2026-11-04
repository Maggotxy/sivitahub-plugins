package cn.originalrpg.core.persistence;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises SQL report boundaries independently of the machine's local calendar. */
final class JdbcEconomyReportCalendarTest {
    private Database database;

    @BeforeEach
    void setUp() {
        assertTrue(System.getenv("RPG_DATABASE_URL") == null || System.getenv("RPG_DATABASE_URL").isBlank(),
                "Report tests refuse a runtime database override");
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", "jdbc:h2:mem:report_calendar_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        database = Database.open(config);
        database.migrate();
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void shanghaiReportIncludesNewDayWhileUtcMachineStillHasPreviousDate() throws Exception {
        assertDailyBoundary("Asia/Shanghai", "2026-11-04T17:00:00Z", "2026-11-04T16:00:00Z");
    }

    @Test
    void daylightSavingFallBackUsesTheEarlierOffsetAtMidnight() throws Exception {
        // 23:30 on a 25-hour New York reporting day, after the offset changed.
        assertDailyBoundary("America/New_York", "2026-11-02T04:30:00Z", "2026-11-01T04:00:00Z");
    }

    @Test
    void daylightSavingSpringForwardUsesCalendarMidnightRatherThanTwentyFourHoursAgo() throws Exception {
        // 23:30 on a 23-hour reporting day.
        assertDailyBoundary("America/New_York", "2026-03-09T03:30:00Z", "2026-03-08T05:00:00Z");
    }

    private void assertDailyBoundary(String zone, String nowText, String boundaryText) throws Exception {
        Instant now = Instant.parse(nowText);
        Instant boundary = Instant.parse(boundaryText);
        insertCredit("previous-day", boundary.minusMillis(1));
        insertCredit("midnight", boundary);
        insertCredit("later-today", now);
        var report = new JdbcEconomyAnalyticsService(database.dataSource(), Runnable::run,
                ZoneId.of(zone), Clock.fixed(now, ZoneOffset.UTC)).dailyReport().toCompletableFuture().join();
        assertEquals(new BigDecimal("200.00"), report.mintedGold());
        assertEquals(new BigDecimal("300.00"), report.sevenDays().mintedGold());
    }

    private void insertCredit(String key, Instant created) throws Exception {
        UUID player = UUID.randomUUID();
        new JdbcEconomyService(database.dataSource(), Runnable::run, BigDecimal.ZERO)
                .credit(player, new BigDecimal("100.00"), key, "calendar-fixture")
                .toCompletableFuture().join();
        try (Connection connection = database.dataSource().getConnection();
             var update = connection.prepareStatement("UPDATE rpg_ledger SET created_at = ? WHERE idempotency_key = ?")) {
            update.setTimestamp(1, Timestamp.from(created));
            update.setString(2, key);
            assertEquals(1, update.executeUpdate());
            connection.commit();
        }
    }
}
