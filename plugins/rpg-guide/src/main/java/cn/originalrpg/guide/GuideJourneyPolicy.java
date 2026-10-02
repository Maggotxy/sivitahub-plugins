package cn.originalrpg.guide;

import cn.originalrpg.api.model.JourneyStep;

import java.util.Optional;

/**
 * One player-facing route for each onboarding step.
 *
 * <p>Not every objective is an NPC destination. Hunting and equipment-slot
 * revealing both happen through real combat, so a hub visit must never replace
 * the local monster guide as their primary action.</p>
 */
final class GuideJourneyPolicy {
    enum DungeonRoute {
        READY("ready", "open:dungeon"),
        NEEDS_LEVEL("needs-level", "command:mobguide"),
        NEEDS_ENERGY("needs-energy", "command:mobguide");

        private final String id;
        private final String action;

        DungeonRoute(String id, String action) {
            this.id = id;
            this.action = action;
        }

        String id() {
            return id;
        }

        String action() {
            return action;
        }
    }

    record Alternative(String id, String action) {
        Alternative {
            if (id == null || id.isBlank() || action == null || action.isBlank()) {
                throw new IllegalArgumentException("Journey alternative requires an id and action");
            }
        }
    }

    private GuideJourneyPolicy() {
    }

    static Optional<String> npcRole(JourneyStep step) {
        // Hunting and slot revealing advance in live combat. Treating either
        // as NPC-owned makes /rpghelp pull an already-hunting player back to
        // the hub, which is the opposite of the next executable action.
        if (step == null || step == JourneyStep.HUNT || step == JourneyStep.REVEAL) {
            return Optional.empty();
        }
        return Optional.of(switch (step) {
            case HUB -> "guide";
            case CLASS, ATTRIBUTE -> "class";
            case EXPLORE -> "wilderness";
            case FORGE -> "forge";
            case DUNGEON -> "dungeon";
            case HUNT, REVEAL -> throw new IllegalStateException("Combat goals have no NPC destination");
        });
    }

    static String primaryAction(JourneyStep step) {
        if (step == JourneyStep.HUNT || step == JourneyStep.REVEAL) return "command:mobguide";
        // The first button is deliberately a destination, not an encyclopedia
        // entry. If an NPC owns the next real action, guide the player there
        // on foot. Hunting and revealing
        // stay local because both advance only through real combat.
        return npcRole(step)
                .map(role -> "command:hubfind " + role)
                .orElse("command:mobguide");
    }

    /**
     * Optional no-travel shortcut for players who already understand the hub.
     * It stays secondary because opening a GUI must never look equivalent to
     * actually meeting an NPC when the journey completion check requires that
     * conversation or its world hand-off.
     */
    static Optional<String> systemAction(JourneyStep step) {
        if (step == null) return Optional.empty();
        return switch (step) {
            case HUB, HUNT, REVEAL -> Optional.empty();
            case CLASS, ATTRIBUTE -> Optional.of("open:class");
            case EXPLORE -> Optional.of("command:wild");
            case FORGE -> Optional.of("command:forge");
            case DUNGEON -> Optional.of("open:dungeon");
        };
    }

    /**
     * The reasoning page has at most one alternate route. Together with back,
     * overview, and encyclopedia this keeps the page to four unambiguous
     * choices instead of rebuilding the main menu inside a dialog.
     */
    static Optional<Alternative> detailsAlternative(JourneyStep step) {
        if (step == null) return Optional.empty();
        return switch (step) {
            case CLASS -> Optional.of(new Alternative("class", "open:class"));
            case ATTRIBUTE -> Optional.of(new Alternative("skills", "command:skills"));
            case EXPLORE, HUNT, REVEAL -> Optional.of(new Alternative("wild", "command:wild"));
            case FORGE -> Optional.of(new Alternative("forge", "command:forge"));
            // A static dungeon shortcut can be a dead entrance. Its action
            // page owns the live level/Energy decision instead.
            case HUB, DUNGEON -> Optional.empty();
        };
    }

    /** Live first-normal-dungeon decision, with level taking precedence. */
    static DungeonRoute dungeonRoute(int effectiveLevel, int energy, int minimumLevel, int energyCost) {
        if (effectiveLevel < 0 || energy < 0 || minimumLevel < 1 || energyCost < 1) {
            throw new IllegalArgumentException("Invalid dungeon readiness values");
        }
        if (effectiveLevel < minimumLevel) return DungeonRoute.NEEDS_LEVEL;
        if (energy < energyCost) return DungeonRoute.NEEDS_ENERGY;
        return DungeonRoute.READY;
    }

    /** Dedicated original resource-pack art; no unrelated loot icon is reused as a journey step. */
    static String itemModel(JourneyStep step) {
        return switch (step) {
            case HUB -> "journey_hub";
            case CLASS -> "journey_class";
            case ATTRIBUTE -> "journey_attribute";
            case EXPLORE -> "journey_explore";
            case HUNT -> "journey_hunt";
            case FORGE -> "journey_forge";
            case REVEAL -> "journey_reveal";
            case DUNGEON -> "journey_dungeon";
        };
    }

    static String helpTopic(JourneyStep step) {
        return switch (step) {
            case HUB -> "start";
            case CLASS, ATTRIBUTE -> "classes";
            case EXPLORE, HUNT -> "mobs";
            case FORGE, REVEAL -> "equipment";
            case DUNGEON -> "dungeons";
        };
    }
}
