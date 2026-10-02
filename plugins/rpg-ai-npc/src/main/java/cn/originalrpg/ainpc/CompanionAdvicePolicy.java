package cn.originalrpg.ainpc;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.JourneyStep;

/** Deterministic next-action fallback shared by local and remote companion modes. */
final class CompanionAdvicePolicy {
    private CompanionAdvicePolicy() {
    }

    static String route(
            Archetype archetype, int unspentAttributes, boolean hasLearnedSkill,
            JourneyStep nextJourneyStep, int forgedEquipment, int skillPoints, int energy
    ) {
        if (archetype == null || archetype == Archetype.UNCHOSEN) return "class";
        if (unspentAttributes > 0) return "attributes";
        if (!hasLearnedSkill) return "skills";
        if (nextJourneyStep != null) return switch (nextJourneyStep) {
            case HUB -> "hub";
            case CLASS -> "class";
            case ATTRIBUTE -> "attributes";
            case EXPLORE -> "explore";
            case HUNT -> "hunt";
            case FORGE -> "forge";
            case REVEAL -> "reveal";
            case DUNGEON -> "dungeon";
        };
        if (forgedEquipment <= 0) return "forge";
        if (skillPoints > 0) return "skills";
        if (energy < 20) return "recovery";
        return "dungeon";
    }
}
