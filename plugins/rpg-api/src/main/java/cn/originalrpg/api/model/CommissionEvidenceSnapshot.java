package cn.originalrpg.api.model;

import java.util.Map;
import java.util.Objects;

/** Database-authoritative daily proof used to gate renewable-resource commissions. */
public record CommissionEvidenceSnapshot(
        int earned,
        int used,
        int submissions,
        int dailyCap,
        Map<String, Integer> offerClaims
) {
    public CommissionEvidenceSnapshot {
        Objects.requireNonNull(offerClaims, "offerClaims");
        offerClaims = Map.copyOf(offerClaims);
        if (earned < 0 || used < 0 || used > earned || submissions < 0 || dailyCap < 1) {
            throw new IllegalArgumentException("Invalid commission evidence snapshot");
        }
        if (offerClaims.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("Invalid commission offer usage");
        }
    }

    public int available() {
        return Math.max(0, earned - used);
    }

    public int offerClaims(String offerId) {
        return offerClaims.getOrDefault(offerId, 0);
    }
}
