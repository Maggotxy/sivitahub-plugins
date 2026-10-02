package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persisted immutable receipt for one incident compensation. */
public record IncidentCompensationAudit(
        String incidentKey,
        UUID playerId,
        LocalDate activityDate,
        long experience,
        BigDecimal totalGold,
        long totalMerit,
        List<IncidentActivityCompensation> activities,
        List<IncidentConditionalActivityAudit> conditionalActivities,
        List<String> evidenceIds,
        String actor,
        String reason,
        String requestHash,
        PlayerProfile beforeProfile,
        PlayerProfile afterProfile,
        Instant appliedAt
) {
    public IncidentCompensationAudit {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(activityDate, "activityDate");
        Objects.requireNonNull(totalGold, "totalGold");
        activities = List.copyOf(activities);
        conditionalActivities = List.copyOf(conditionalActivities);
        evidenceIds = List.copyOf(evidenceIds);
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(beforeProfile, "beforeProfile");
        Objects.requireNonNull(afterProfile, "afterProfile");
        Objects.requireNonNull(appliedAt, "appliedAt");
    }
}
