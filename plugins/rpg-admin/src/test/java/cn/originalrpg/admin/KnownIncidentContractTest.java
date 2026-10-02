package cn.originalrpg.admin;

import cn.originalrpg.api.model.EconomyActivity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class KnownIncidentContractTest {
    @Test
    void cureWhiteOutageFactsAreFixedAndAdvancementsRemainEvidenceOnly() {
        var request = RpgAdminPlugin.knownIncident(
                "ledger-outage-2026-08-12-cure-white", "console");

        assertEquals("b8229850-d84a-39ff-bd79-7529c4887403", request.playerId().toString());
        assertEquals(34_100L, request.experience());
        assertEquals(new BigDecimal("245.36"), request.activities().stream()
                .filter(line -> line.activity() == EconomyActivity.HUNTING).findFirst().orElseThrow().gold());
        assertEquals(7L, request.activities().stream()
                .filter(line -> line.activity() == EconomyActivity.HUNTING).findFirst().orElseThrow().merit());
        assertEquals(3, request.conditionalActivities().size());
        assertEquals(new BigDecimal("360.00"), request.conditionalActivities().stream()
                .map(line -> line.gold()).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add));
        assertEquals(3L, request.conditionalActivities().stream().mapToLong(line -> line.merit()).sum());
        assertEquals("adv:b8229850-d84a-39ff-bd79-7529c4887403:0420f744-99fa-3f2d-aa47-c8e0dc6d7bc1",
                request.conditionalActivities().stream()
                        .filter(line -> line.evidenceId().equals("minecraft:nether/find_bastion"))
                        .findFirst().orElseThrow().idempotencyKey());
        assertEquals(3, request.evidenceIds().size());
        assertNull(RpgAdminPlugin.knownIncident("unknown", "console"));
    }
}
