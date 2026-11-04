package cn.originalrpg.core.persistence;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class LegacyPaymentAuditTest {
    @Test void auditIsBoundedStableImmutableAndIsolatedToTheRequestedUuidWithoutCreatingAccounts() throws Exception {
        try (var fixture = new SupportCreditFixture()) {
            UUID player = UUID.randomUUID(); UUID another = UUID.randomUUID();
            try (var connection = fixture.dataSource.getConnection(); var insert = connection.prepareStatement("""
                    INSERT INTO rpg_payment_ledger
                    (id,external_reference,player_uuid,amount,membership_days,operator_name,created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """)) {
                for (int i = 0; i < 57; i++) {
                    insert.setString(1, String.format(java.util.Locale.ROOT, "%036d", i));
                    insert.setString(2, "provider:historic-" + i); insert.setString(3, (i < 55 ? player : another).toString());
                    insert.setBigDecimal(4, new BigDecimal(i + ".25")); insert.setInt(5, i % 31);
                    insert.setString(6, "historical-operator");
                    insert.setTimestamp(7, Timestamp.from(fixture.clock.instant().plusSeconds(i == 0 || i >= 55 ? 1 : 0)));
                    insert.addBatch();
                }
                insert.executeBatch(); connection.commit();
            }
            var commerce = new JdbcCommerceService(fixture.dataSource, fixture.executor);
            var rows = commerce.legacyPayments(player, 50).toCompletableFuture().join();
            assertEquals(50, rows.size()); assertTrue(rows.stream().allMatch(row -> row.playerId().equals(player)));
            assertEquals(String.format(java.util.Locale.ROOT, "%036d", 0), rows.getFirst().id(), "Timestamp wins over the ID tie-break");
            assertEquals(String.format(java.util.Locale.ROOT, "%036d", 54), rows.get(1).id());
            assertEquals("provider:historic-54", rows.get(1).externalReference());
            assertEquals("historical-operator", rows.get(1).operator());
            assertEquals(0, rows.get(1).amount().compareTo(new BigDecimal("54.25")));
            assertEquals(23, rows.get(1).membershipDays());
            assertEquals(rows, commerce.legacyPayments(player, 50).toCompletableFuture().join(), "Stable ordering across reads");
            assertEquals(1, commerce.legacyPayments(player, 1).toCompletableFuture().join().size());
            assertEquals(2, commerce.legacyPayments(another, 50).toCompletableFuture().join().size());
            assertTrue(commerce.legacyPayments(UUID.randomUUID(), 50).toCompletableFuture().join().isEmpty());
            assertThrows(UnsupportedOperationException.class, rows::clear);
            assertEquals(57, fixture.count("rpg_payment_ledger"));
            for (String table : new String[]{"rpg_accounts", "rpg_commerce_accounts", "rpg_credit_ledger",
                    "rpg_market_deliveries", "rpg_earned_membership_grants", "rpg_support_credit_accounts"}) {
                assertEquals(0, fixture.count(table), "A history read cannot create or mutate " + table);
            }
        }
    }

    @Test void invalidAuditBoundsAreRejectedBeforeOpeningAnyConnection() {
        DataSource unreachable = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> { throw new AssertionError("Audit validation opened JDBC"); });
        var commerce = new JdbcCommerceService(unreachable, Runnable::run);
        UUID player = UUID.randomUUID();
        for (int limit : new int[]{Integer.MIN_VALUE, -1, 0, 51, Integer.MAX_VALUE}) {
            var failure = assertThrows(CompletionException.class, () -> commerce.legacyPayments(player, limit).toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        assertThrows(CompletionException.class, () -> commerce.legacyPayments(null, 10).toCompletableFuture().join());
    }
}
