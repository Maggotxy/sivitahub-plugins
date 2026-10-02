package cn.originalrpg.guide;

import java.util.Locale;

/** Pure routing for the player-facing {@code /rpghelp} entry points. */
record HelpCommandRoute(Target target, String topic) {
    enum Target {
        CONTEXTUAL,
        CURRENT,
        HOME,
        NPCS,
        TOPICS,
        TOPIC
    }

    static HelpCommandRoute parse(String[] arguments) {
        if (arguments == null || arguments.length == 0
                || arguments[0] == null || arguments[0].isBlank()) {
            return new HelpCommandRoute(Target.CONTEXTUAL, null);
        }
        String token = arguments[0].strip().toLowerCase(Locale.ROOT);
        return switch (token) {
            case "current", "next" -> new HelpCommandRoute(Target.CURRENT, null);
            case "home", "menu" -> new HelpCommandRoute(Target.HOME, null);
            case "npc", "npcs" -> new HelpCommandRoute(Target.NPCS, null);
            case "all", "topics" -> new HelpCommandRoute(Target.TOPICS, null);
            default -> new HelpCommandRoute(Target.TOPIC, token);
        };
    }
}
