package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.HiddenPath;

/** Static, reviewable requirements for the first two hidden routes. */
public final class HiddenPathRules {
    private HiddenPathRules() {
    }

    public static Archetype archetype(HiddenPath path) {
        return switch (path) {
            case BLOODPACT -> Archetype.ASSASSIN;
            case TALISMAN -> Archetype.FREEFORM;
        };
    }

    public static int minimumAscension(HiddenPath path) {
        return 2;
    }

    public static int minimumLevel(HiddenPath path) {
        return 20;
    }

    public static String requiredSkill(HiddenPath path) {
        return switch (path) {
            case BLOODPACT -> "vanished-horizon";
            case TALISMAN -> "rune-reversal";
        };
    }

    public static String requiredDungeon(HiddenPath path) {
        return switch (path) {
            case BLOODPACT -> "storm-vault-hidden";
            case TALISMAN -> "sulfur-depths-hidden";
        };
    }

    /**
     * The hidden-boss book is consumed through the ordinary idempotent
     * skill-book flow. Its durable redemption row is the route credential;
     * keeping a duplicate item in an inventory can never satisfy this gate.
     */
    public static String requiredSkillBook(HiddenPath path) {
        return switch (path) {
            case BLOODPACT -> "silent-thunder";
            case TALISMAN -> "cinnabar-revelation";
        };
    }

    public static int capacity(HiddenPath path) {
        return 3;
    }
}
