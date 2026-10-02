package cn.originalrpg.skills;

import cn.originalrpg.api.service.PartyService;

import java.util.Objects;
import java.util.UUID;

/** Pure fail-closed target policy shared by every friendly area skill. */
final class SkillAllyRules {
    private SkillAllyRules() {
    }

    static boolean canReceive(UUID caster, UUID candidate, PartyService parties) {
        Objects.requireNonNull(caster, "caster");
        Objects.requireNonNull(candidate, "candidate");
        if (caster.equals(candidate)) return true;
        if (parties == null) return false;
        try {
            return parties.areAllies(caster, candidate);
        } catch (RuntimeException ignored) {
            // Relationship failure must never leak a cooperative effect to an
            // unverified target or consume a cast through an exception.
            return false;
        }
    }
}
