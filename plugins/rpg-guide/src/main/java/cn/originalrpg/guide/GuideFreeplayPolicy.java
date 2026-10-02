package cn.originalrpg.guide;

import java.util.Objects;
import java.util.Set;

/**
 * Chooses one executable recommendation after the finite onboarding journey.
 *
 * <p>The policy is deliberately independent of Bukkit and persistence. Callers
 * assemble one validated snapshot from their live services, then render the
 * returned code-owned metadata. This prevents translated text or configuration
 * from becoming an action dispatcher.</p>
 */
final class GuideFreeplayPolicy {
    private static final Set<String> TRUSTED_ACTIONS = Set.of(
            "open:class",
            "command:skills",
            "command:forge",
            "command:mobguide",
            "open:dungeon");

    enum Route {
        ASCENSION("open:class", "classes", "journey_class", "NETHER_STAR"),
        SKILLS("command:skills", "skills", "common_insight_skill_book", "ENCHANTED_BOOK"),
        FORGE("command:forge", "equipment", "journey_forge", "ANVIL"),
        REVEAL("command:mobguide", "equipment", "journey_reveal", "EXPERIENCE_BOTTLE"),
        DUNGEON("open:dungeon", "dungeons", "journey_dungeon", "TRIAL_KEY"),
        HUNT("command:mobguide", "mobs", "journey_hunt", "ZOMBIE_HEAD");

        private final String action;
        private final String helpTopic;
        private final String itemModel;
        private final String vanillaIcon;

        Route(String action, String helpTopic, String itemModel, String vanillaIcon) {
            this.action = action;
            this.helpTopic = helpTopic;
            this.itemModel = itemModel;
            this.vanillaIcon = vanillaIcon;
        }

        String action() {
            return action;
        }

        String helpTopic() {
            return helpTopic;
        }

        String itemModel() {
            return itemModel;
        }

        String vanillaIcon() {
            return vanillaIcon;
        }
    }

    /** Immutable live inputs; counts describe the player's current usable state. */
    record PlayerState(
            boolean canAscendNow,
            int unusedSkillPoints,
            int learnedSkillCount,
            int craftedEquipmentCount,
            int revealedSlotCount,
            boolean hasEnterableDungeon) {
        PlayerState {
            if (unusedSkillPoints < 0 || learnedSkillCount < 0
                    || craftedEquipmentCount < 0 || revealedSlotCount < 0) {
                throw new IllegalArgumentException("Freeplay route counts cannot be negative");
            }
            if (craftedEquipmentCount == 0 && revealedSlotCount > 0) {
                throw new IllegalArgumentException("Revealed slots require crafted equipment");
            }
        }
    }

    /**
     * A recommendation whose presentation and click action all originate from
     * its bounded route. No caller-provided command can enter this result.
     */
    record Recommendation(Route route) {
        Recommendation {
            Objects.requireNonNull(route, "route");
        }

        String action() {
            return route.action();
        }

        String helpTopic() {
            return route.helpTopic();
        }

        String itemModel() {
            return route.itemModel();
        }

        String vanillaIcon() {
            return route.vanillaIcon();
        }
    }

    private GuideFreeplayPolicy() {
    }

    static Recommendation recommend(PlayerState state) {
        Objects.requireNonNull(state, "state");

        if (state.canAscendNow()) return recommendation(Route.ASCENSION);

        // One first skill is actionable and build-defining. Once any skill is
        // learned, unspent points may be an intentional save for a later node;
        // never trap that player on a permanent "spend points" recommendation.
        if (state.learnedSkillCount() == 0 && state.unusedSkillPoints() > 0) {
            return recommendation(Route.SKILLS);
        }
        if (state.craftedEquipmentCount() == 0) return recommendation(Route.FORGE);
        if (state.revealedSlotCount() == 0) return recommendation(Route.REVEAL);
        if (state.hasEnterableDungeon()) return recommendation(Route.DUNGEON);
        return recommendation(Route.HUNT);
    }

    static boolean isTrustedAction(String action) {
        return action != null && TRUSTED_ACTIONS.contains(action);
    }

    private static Recommendation recommendation(Route route) {
        if (!isTrustedAction(route.action())) {
            throw new IllegalStateException("Untrusted freeplay action: " + route.action());
        }
        return new Recommendation(route);
    }
}
