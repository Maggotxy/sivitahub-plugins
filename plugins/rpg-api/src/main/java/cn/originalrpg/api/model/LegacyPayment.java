package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable historical payment evidence for operator reconciliation. Reading this
 * record does not establish membership, entitlement, eligibility or new credit.
 * Historical values are returned as stored instead of being normalized into a
 * new benefit; the audit ID remains an opaque original identifier.
 */
public record LegacyPayment(String id, UUID playerId, BigDecimal amount, int membershipDays,
                            String externalReference, String operator, Instant createdAt) {
    public LegacyPayment {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(amount, "amount"); Objects.requireNonNull(externalReference, "externalReference");
        Objects.requireNonNull(operator, "operator"); Objects.requireNonNull(createdAt, "createdAt");
    }
}
