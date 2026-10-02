package cn.originalrpg.guide;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Code-owned navigation for the compact free-journey screens. */
final class GuideFreeplayMenuPolicy {
    private static final String COMPLETED_JOURNEY_ACTION = "open:current";
    private static final String REVIEW_JOURNEY_ACTION = "open:onboarding";
    private static final String OPEN_ALTERNATIVES_ACTION = "open:freeplay-routes";
    private static final Set<String> TRUSTED_ALTERNATIVE_ACTIONS = Set.of(
            "help:tasks", "help:mobs", "help:classes", "help:equipment", "help:economy");

    enum Alternative {
        TASKS("tasks", "help:tasks"),
        LEVEL("level", "help:mobs"),
        BUILD("build", "help:classes"),
        EQUIPMENT("equipment", "help:equipment"),
        ECONOMY("economy", "help:economy");

        private final String id;
        private final String action;

        Alternative(String id, String action) {
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

    private static final List<Alternative> ALTERNATIVES = List.copyOf(Arrays.asList(Alternative.values()));

    private GuideFreeplayMenuPolicy() {
    }

    static String completedJourneyAction() {
        return COMPLETED_JOURNEY_ACTION;
    }

    static String reviewJourneyAction() {
        return REVIEW_JOURNEY_ACTION;
    }

    static String openAlternativesAction() {
        return OPEN_ALTERNATIVES_ACTION;
    }

    static List<Alternative> alternatives() {
        return ALTERNATIVES;
    }

    static boolean isTrustedAlternativeAction(String action) {
        return action != null && TRUSTED_ALTERNATIVE_ACTIONS.contains(action);
    }
}
