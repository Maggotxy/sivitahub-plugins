package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable subject of a signed role request. The existing approval ledger persists both
 * target and intended role as {@code UUID:ROLE}; an approver cannot substitute another role.
 * Legacy UUID-only subjects contain insufficient consent and may only be inspected or denied.
 */
public record CommunityRoleIntent(UUID targetId, CommunityRole role) {
    public CommunityRoleIntent {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(role, "role");
        if (role == CommunityRole.OWNER) throw new IllegalArgumentException("Ownership requires transfer or succession");
    }

    public String subject() {
        return targetId + ":" + role.name();
    }

    public static Optional<CommunityRoleIntent> parse(String subject) {
        if (subject == null) return Optional.empty();
        String value = subject.strip();
        int separator = value.indexOf(':');
        if (separator != 36) return Optional.empty();
        try {
            UUID target = UUID.fromString(value.substring(0, separator));
            CommunityRole role = CommunityRolePower.assignable(value.substring(separator + 1)).orElse(null);
            return role == null ? Optional.empty() : Optional.of(new CommunityRoleIntent(target, role));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
