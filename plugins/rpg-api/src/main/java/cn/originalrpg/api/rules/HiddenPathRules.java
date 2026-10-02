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
     * The ascension stage the route actually requires.
     *
     * <p>BLOODPACT is documented as "二转当前等级 20" in
     * {@code docs/GAME_DESIGN.md} ("hidden routes"), but its prerequisite skill
     * {@code vanished-horizon} is the assassin stage-3 branch skill
     * ({@code docs/SKILL_MECHANICS_MATRIX.md} plus the authored branch graph
     * {@code unseen: {stage: 3}}). Because every class branch is additionally
     * gated on {@code classes/config.yml} stage 2, the first ascension which
     * can learn that branch node is stage 3. Stating a stage-2 gate here is the
     * rules error: it makes the route unreachable forever, not harder.
     *
     * <p>This aligns the rule with the branch graph instead of weakening the
     * skill, so the documented build (Agi 32 / Sta 18, learned
     * {@code vanished-horizon}, storm-vault-hidden cleared) stays exactly as
     * authored.
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
