package cn.originalrpg.api.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime-backed equipment traits shared by forging, inheritance and skill
 * casting. A trait may only enter the forge pool when a gameplay module has a
 * concrete consumer for it.
 */
public final class EquipmentTraitCatalog {
    private static final Map<String, String> BORROWED_SKILLS = Map.of(
            "skill-heal", "heal",
            "skill-war-cry", "war-cry",
            // Keep the original ID so equipment forged before this catalog was
            // introduced remains usable and inheritable.
            "shadow-step", "shadow-step",
            "skill-fireball", "fireball",
            "skill-power-shot", "power-shot",
            "skill-improvisation", "improvisation"
    );
    private static final Set<String> PASSIVE_TRAITS = Set.of(
            "life-steal", "fire-mastery", "healing-boost", "execute-resonance");
    private static final Set<String> IMPLEMENTED = java.util.stream.Stream.concat(
            BORROWED_SKILLS.keySet().stream(), PASSIVE_TRAITS.stream()).collect(
            java.util.stream.Collectors.toUnmodifiableSet());

    private EquipmentTraitCatalog() {
    }

    public static Set<String> implemented() {
        return IMPLEMENTED;
    }

    public static Map<String, String> borrowedSkills() {
        return BORROWED_SKILLS;
    }

    public static Optional<String> borrowedSkill(String traitId) {
        return Optional.ofNullable(BORROWED_SKILLS.get(traitId));
    }

    /**
     * A hidden rune becomes numerically active only after its whole item has
     * been revealed through use and that signed item is bound to this player.
     * Merely previewing or briefly equipping an unbound market item is never an
     * unlock.
     */
    public static boolean activeFor(EquipmentSnapshot snapshot, UUID playerId) {
        if (snapshot == null || playerId == null || snapshot.totalSlots() <= 0
                || snapshot.revealedSlots() != snapshot.totalSlots()) return false;
        return snapshot.boundOwner().filter(playerId::equals).isPresent();
    }
}
