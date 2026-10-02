package cn.originalrpg.classes;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.StarterBuildRules;

import java.util.Set;

/** Defines which class-menu actions are safe to complete without closing it. */
final class ClassMenuPolicy {
    static final int ASCEND_SLOT = 34;
    static final int HIDDEN_PATH_SLOT = 25;

    private ClassMenuPolicy() {
    }

    static boolean keepsMenuOpen(String action) {
        return action != null && action.startsWith("attribute ");
    }

    static String starterSkill(Archetype archetype) {
        if (archetype == null || archetype == Archetype.UNCHOSEN) return null;
        return switch (archetype) {
            case HEALER -> "heal";
            case WARRIOR -> "war-cry";
            case ASSASSIN -> "shadow-step";
            case MAGE -> "fireball";
            case RANGER -> "power-shot";
            case FREEFORM -> "improvisation";
            case UNCHOSEN -> null;
        };
    }

    static boolean showsHiddenPath(Archetype archetype) {
        return archetype != null && archetype != Archetype.UNCHOSEN;
    }

    static boolean starterReady(PlayerProfile profile) {
        return profile != null && StarterBuildRules.ready(profile.archetype(), profile.attributes());
    }

    static boolean mayAllocateStarter(PlayerProfile profile, CoreAttribute attribute) {
        return profile != null && StarterBuildRules.mayAllocate(
                profile.archetype(), profile.attributes(), attribute);
    }

    static int branchSlot(int stage, int indexWithinStage) {
        return switch (stage) {
            case 1 -> indexWithinStage == 0 ? 37 : indexWithinStage == 1 ? 43 : -1;
            case 2 -> indexWithinStage == 0 ? 38 : indexWithinStage == 1 ? 42 : -1;
            case 3 -> indexWithinStage == 0 ? 40 : -1;
            default -> -1;
        };
    }

    static boolean prerequisiteSatisfied(int stage, Set<String> requiredPrevious, String selectedPrevious) {
        return stage <= 1 || requiredPrevious == null || requiredPrevious.isEmpty()
                || selectedPrevious != null && requiredPrevious.contains(selectedPrevious);
    }
}
