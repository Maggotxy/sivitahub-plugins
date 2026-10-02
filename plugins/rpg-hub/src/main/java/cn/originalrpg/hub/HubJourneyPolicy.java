package cn.originalrpg.hub;

import cn.originalrpg.api.model.JourneyStep;

/** Stable routing from a journey goal to its authoritative gameplay entry. */
final class HubJourneyPolicy {
    enum Target {
        MAIN_MENU,
        CLASS_MENU,
        WILDERNESS,
        FORGE,
        COMBAT_GUIDE,
        DUNGEON_MENU,
        QUEST_CENTER
    }

    private HubJourneyPolicy() {
    }

    static Target target(JourneyStep step) {
        if (step == null) return Target.QUEST_CENTER;
        return switch (step) {
            case HUB -> Target.MAIN_MENU;
            case CLASS, ATTRIBUTE -> Target.CLASS_MENU;
            case EXPLORE -> Target.WILDERNESS;
            case HUNT, REVEAL -> Target.COMBAT_GUIDE;
            case FORGE -> Target.FORGE;
            case DUNGEON -> Target.DUNGEON_MENU;
        };
    }

    static boolean roleAlreadyServes(HubRole role, JourneyStep step) {
        if (step == null) return role == HubRole.QUESTS;
        return switch (step) {
            case HUB -> role == HubRole.GUIDE;
            case CLASS, ATTRIBUTE -> role == HubRole.CLASS;
            case EXPLORE -> role == HubRole.WILDERNESS;
            case HUNT, REVEAL -> false;
            case FORGE -> role == HubRole.FORGE;
            case DUNGEON -> role == HubRole.DUNGEON;
        };
    }
}
