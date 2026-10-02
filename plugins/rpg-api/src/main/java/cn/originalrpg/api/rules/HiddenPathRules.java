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

    /**
     * 血契（BLOODPACT）的前置技能 {@code vanished-horizon} 是刺客三转汇合分支
     * {@code unseen} 的技术（分支 stage 3，与技能门由 validateCatalog 强制相等），
     * 路线门低于前置门就会永久不可达（G-106）。因此血契与前置一起定在三转。
     * 符箓（TALISMAN）的前置 {@code rune-reversal} 挂在二转分支 rune-scholar，保持二转。
     */
    public static int minimumAscension(HiddenPath path) {
        return switch (path) {
            case BLOODPACT -> 3;
            case TALISMAN -> 2;
        };
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
