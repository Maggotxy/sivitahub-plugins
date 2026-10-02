package cn.originalrpg.guide;

import java.util.List;

/** Stable semantic slots for the uncluttered top-level navigation. */
final class GuideMenuLayout {
    static final int SIZE = 45;
    static final int PROFILE = 4;
    static final int JOURNEY = 10;
    static final int GROWTH = 12;
    static final int ADVENTURE = 14;
    static final int ECONOMY = 16;
    static final int SOCIAL = 28;
    static final int VISUALS = 30;
    static final int HELP = 32;
    static final int MAILBOX = 34;
    static final int BACK = 40;
    static final int ONBOARDING_CURRENT = 4;
    static final int ONBOARDING_STORY = 28;
    static final int ONBOARDING_SKILLS = 30;
    static final int ONBOARDING_FULL_GUIDE = 32;
    static final List<Integer> ONBOARDING_STEP_SLOTS = List.of(10, 12, 14, 16, 19, 21, 23, 25);
    static final int QUESTS_CURRENT = 4;
    static final int HELP_CURRENT = 4;
    static final int HELP_GROWTH = 10;
    static final int HELP_EQUIPMENT = 12;
    static final int HELP_ADVENTURE = 14;
    static final int HELP_ECONOMY = 16;
    static final int HELP_NPCS = 29;
    static final int HELP_ALL_TOPICS = 31;
    static final int HELP_AI = 33;
    static final int ADVENTURE_SEASON = 20;
    static final int DUNGEON_SEASON = 29;

    private GuideMenuLayout() {
    }

    static List<Integer> mainSlots() {
        return List.of(PROFILE, JOURNEY, GROWTH, ADVENTURE, ECONOMY,
                SOCIAL, VISUALS, HELP, MAILBOX);
    }
}
