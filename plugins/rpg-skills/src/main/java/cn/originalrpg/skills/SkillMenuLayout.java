package cn.originalrpg.skills;

import java.util.List;

/** Three content rows plus a dedicated bottom navigation row. */
final class SkillMenuLayout {
    static final int HEADER = 4;
    static final int NEXT_OBJECTIVE = 40;
    static final int MAIN_MENU = 45;
    static final int MENTOR = 46;
    static final int PREVIOUS = 47;
    static final int CLASS_AND_ATTRIBUTES = 48;
    static final int CONTROLS = 49;
    static final int HUD = 50;
    static final int HELP = 51;
    static final int JOURNEY = 52;
    static final int NEXT = 53;
    static final List<Integer> CONTENT = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    );

    private SkillMenuLayout() {
    }
}
