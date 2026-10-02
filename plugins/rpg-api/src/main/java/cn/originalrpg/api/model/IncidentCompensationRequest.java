package cn.originalrpg.api.model;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable facts approved for one operational incident. */
public record IncidentCompensationRequest(
        String incidentKey,
        UUID playerId,
        LocalDate activityDate,
        long experience,
        List<IncidentActivityCompensation> activities,
        List<IncidentConditionalActivityCompensation> conditionalActivities,
        List<String> evidenceIds,
        String actor,
        String reason
) {
    public IncidentCompensationRequest {
        incidentKey = Objects.requireNonNull(incidentKey, "incidentKey").strip();
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(activityDate, "activityDate");
        activities = List.copyOf(Objects.requireNonNull(activities, "activities"));
        conditionalActivities = List.copyOf(Objects.requireNonNull(conditionalActivities, "conditionalActivities"));
        evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        actor = Objects.requireNonNull(actor, "actor").strip();
        reason = Objects.requireNonNull(reason, "reason").strip();
        if (experience < 0 || experience == 0 && activities.isEmpty() && conditionalActivities.isEmpty()) {
            throw new IllegalArgumentException("Incident compensation is empty or negative");
        }
        if (activities.stream().anyMatch(Objects::isNull)
                || activities.stream().map(IncidentActivityCompensation::activity).distinct().count()
                != activities.size()) {
            throw new IllegalArgumentException("Incident activity lines must be unique");
        }
        if (conditionalActivities.stream().anyMatch(Objects::isNull)
                || conditionalActivities.stream().map(IncidentConditionalActivityCompensation::idempotencyKey)
                .distinct().count() != conditionalActivities.size()) {
            throw new IllegalArgumentException("Conditional incident rewards must have unique idempotency keys");
        }
        if (evidenceIds.stream().anyMatch(value -> value == null || value.isBlank())
                || new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
            throw new IllegalArgumentException("Incident evidence ids must be non-empty and unique");
        }
        HashSet<String> evidenceSet = new HashSet<>(evidenceIds);
        if (conditionalActivities.stream().map(IncidentConditionalActivityCompensation::evidenceId)
                .anyMatch(value -> !evidenceSet.contains(value))) {
            throw new IllegalArgumentException("Every conditional incident reward must reference recorded evidence");
        }
    }

    public IncidentCompensationRequest(
            String incidentKey, UUID playerId, LocalDate activityDate, long experience,
            List<IncidentActivityCompensation> activities, List<String> evidenceIds,
            String actor, String reason
    ) {
        this(incidentKey, playerId, activityDate, experience, activities, List.of(), evidenceIds, actor, reason);
    }
}
