package cn.originalrpg.skills;

import cn.originalrpg.api.service.PartyService;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkillAllyRulesTest {
    @Test
    void casterAlwaysReceivesOwnFriendlySkillWithoutPartyProvider() {
        UUID caster = UUID.randomUUID();

        assertTrue(SkillAllyRules.canReceive(caster, caster, null));
    }

    @Test
    void unrelatedNearbyPlayerIsRejectedAndPartyMemberIsAccepted() {
        UUID caster = UUID.randomUUID();
        UUID ally = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Set<UUID> group = Set.of(caster, ally);
        PartyService parties = (first, second) -> group.contains(first) && group.contains(second);

        assertTrue(SkillAllyRules.canReceive(caster, ally, parties));
        assertFalse(SkillAllyRules.canReceive(caster, stranger, parties));
        assertFalse(SkillAllyRules.canReceive(caster, stranger, null));
        assertFalse(SkillAllyRules.canReceive(caster, stranger, (first, second) -> {
            throw new IllegalStateException("relationship backend unavailable");
        }));
    }
}
