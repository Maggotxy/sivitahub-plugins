package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.HiddenPath;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything a hidden route references outside its own definition: the skill
 * that must already be learned, the ascension stage its class branch opens at,
 * the hidden dungeon gate and the boss book which credits the route. Those
 * values live in RpgSkills / RpgClasses / RpgDungeons, so callers pass them in
 * and {@link #unattainable} decides whether the route is satisfiable at all.
 *
 * <p>This is a contract, not a simulation: it refuses to guess XP curves, gold
 * income or player skill, and only checks the hard authored gates.
 */
public record HiddenPathReachability(
        String requiredSkill,
        int requiredSkillAscension,
        Set<String> requiredSkillBranches,
        int requiredSkillEffectiveLevel,
        String requiredDungeon,
        int hiddenDungeonEffectiveLevel,
        String ticketSource,
        int ticketSourceEffectiveLevel,
        String requiredSkillBook,
        int skillBookEffectiveLevel
) {
    public static final String BLOODPACT_SKILL = "vanished-horizon";
    public static final String BLOODPACT_SKILL_BRANCH = "unseen";
    public static final String BLOODPACT_DUNGEON = "storm-vault-hidden";
    public static final String BLOODPACT_TICKET_SOURCE = "storm-vault";
    public static final String BLOODPACT_SKILL_BOOK = "silent-thunder";
    public static final String TALISMAN_SKILL = "rune-reversal";
    public static final String TALISMAN_SKILL_BRANCH = "rune-scholar";
    public static final String TALISMAN_DUNGEON = "sulfur-depths-hidden";
    public static final String TALISMAN_TICKET_SOURCE = "sulfur-depths";
    public static final String TALISMAN_SKILL_BOOK = "cinnabar-revelation";

    public HiddenPathReachability {
        Objects.requireNonNull(requiredSkill, "requiredSkill");
        Objects.requireNonNull(requiredSkillBranches, "requiredSkillBranches");
        requiredSkillBranches = Set.copyOf(requiredSkillBranches);
        Objects.requireNonNull(requiredDungeon, "requiredDungeon");
        Objects.requireNonNull(requiredSkillBook, "requiredSkillBook");
    }

    /**
     * The authored facts of both shipped routes, validated against
     * {@link HiddenPathRules} so a rules/config drift fails at startup instead
     * of silently making a route unattainable.
     */
    public static Map<HiddenPath, HiddenPathReachability> authored() {
        Map<HiddenPath, HiddenPathReachability> facts = Map.of(
                HiddenPath.BLOODPACT, new HiddenPathReachability(
                        BLOODPACT_SKILL, 3, Set.of(BLOODPACT_SKILL_BRANCH), 20,
                        BLOODPACT_DUNGEON, 28, BLOODPACT_TICKET_SOURCE, 22, BLOODPACT_SKILL_BOOK, 28),
                HiddenPath.TALISMAN, new HiddenPathReachability(
                        TALISMAN_SKILL, 2, Set.of(TALISMAN_SKILL_BRANCH), 10,
                        TALISMAN_DUNGEON, 20, TALISMAN_TICKET_SOURCE, 12, TALISMAN_SKILL_BOOK, 20));
        facts.forEach((path, fact) -> {
            requireEquals(HiddenPathRules.requiredSkill(path), fact.requiredSkill(),
                    path + " required skill");
            requireEquals(HiddenPathRules.requiredDungeon(path), fact.requiredDungeon(),
                    path + " required dungeon");
            requireEquals(HiddenPathRules.requiredSkillBook(path), fact.requiredSkillBook(),
                    path + " required skill book");
        });
        return facts;
    }

    /** The authored facts of one route. */
    public static HiddenPathReachability authored(HiddenPath path) {
        return authored().get(Objects.requireNonNull(path, "path"));
    }

    /** The highest level a route's own gate can ever be evaluated at. */
    private static int routeGateAbsolute(HiddenPath path) {
        return ProgressionRules.effectiveLevel(
                HiddenPathRules.minimumAscension(path), ProgressionRules.LOCAL_LEVEL_CAP);
    }

    /** Turn an authored stage-local level into an absolute effective level. */
    private static int absolute(int authoredLevel, int routeAscension) {
        if (!isStageLocal(authoredLevel)) return Math.min(authoredLevel, ProgressionRules.effectiveLevelCap());
        // A gate tied to an earlier stage is already past; only a gate which
        // needs the route's own or a later ascension can block the route.
        int requiredAscension = authoredLevel / ProgressionRules.LEVELS_PER_ASCENSION;
        return ProgressionRules.effectiveLevel(
                Math.min(Math.max(requiredAscension, routeAscension), ProgressionRules.MAX_ASCENSION),
                authoredLevel);
    }

    /** Stage-local gates are 1..30; anything larger is already absolute. */
    private static boolean isStageLocal(int authoredLevel) {
        return authoredLevel <= ProgressionRules.LOCAL_LEVEL_CAP;
    }

    /**
     * Requirement codes which no player can ever satisfy on this route. An
     * empty result means every gate of the route fits inside the route's own
     * ascension and level budget; codes match
     * {@code HiddenPathEligibility.missingRequirements()}.
     *
     * <p>The route itself declares local levels against its own ascension, but
     * its prerequisites declare local levels against the ascension that opens
     * them ("minimum-ascension" + "minimum-level" in RpgSkills / RpgDungeons).
     * Both are offset onto the same absolute ladder before comparing, which is
     * exactly the class of contradiction G-106 shipped.
     *
     * <p>Called with the live RpgSkills / RpgClasses / RpgDungeons facts at
     * startup, this catches every "route requires something that opens later
     * than the route itself" configuration contradiction.
     */
    public static Set<String> unattainable(HiddenPath path, HiddenPathReachability facts) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(facts, "facts");
        int routeAscension = HiddenPathRules.minimumAscension(path);
        // The route gate itself is satisfiable for any level inside its own
        // stage, so the budget to compare against is that stage's top level.
        int routeGate = routeGateAbsolute(path);
        Objects.requireNonNull(facts, "facts");
        Set<String> blockers = new TreeSet<>();
        // The required skill is authored against the branch stage which opens
        // it, so its own ascension is part of the gate.
        if (facts.requiredSkillAscension() > routeAscension
                || facts.requiredSkillAscension() > ProgressionRules.MAX_ASCENSION) {
            blockers.add(limitCode(path, Limit.SKILL));
        } else if (facts.requiredSkillEffectiveLevel() > ProgressionRules.LOCAL_LEVEL_CAP) {
            // An absolute skill level must still fit inside the route stage.
            if (facts.requiredSkillEffectiveLevel() > routeGate) blockers.add(limitCode(path, Limit.SKILL));
        }
        // The credential book drops from the route's hidden dungeon; the hidden
        // ticket in turn comes from the matching normal dungeon.
        if (absolute(facts.hiddenDungeonEffectiveLevel(), routeAscension) > routeGate) {
            blockers.add(limitCode(path, Limit.HIDDEN_DUNGEON));
        }
        if (absolute(facts.skillBookEffectiveLevel(), routeAscension) > routeGate) {
            blockers.add(limitCode(path, Limit.SKILL_BOOK));
        }
        if (absolute(facts.ticketSourceEffectiveLevel(), routeAscension) > routeGate) {
            blockers.add(limitCode(path, Limit.SKILL_BOOK));
        }
        return Set.copyOf(blockers);
    }

    /**
     * The stall a contradictory route would produce. Each limit maps to the
     * requirement code players already see in {@code /class hidden}, so the
     * startup failure names the same thing the menu would have hidden forever.
     */
    public enum Limit {
        SKILL,
        HIDDEN_DUNGEON,
        SKILL_BOOK
    }

    /** The player-facing requirement code for a limit of a route. */
    public static String limitCode(HiddenPath path, Limit limit) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(limit, "limit");
        return switch (limit) {
            case SKILL -> HiddenPathRules.requiredSkill(path);
            case HIDDEN_DUNGEON -> "hidden_dungeon";
            case SKILL_BOOK -> "skill_book";
        };
    }

    /**
     * The ascension stage which first reaches an effective level.
     *
     * <p>Stage {@code a} covers effective levels
     * {@code a * 30 + 1 .. a * 30 + 30}, so this is a ceil-division, not the
     * floor {@code (level - 1) / 30} that {@code totalExperienceToEffective}
     * still uses for its own span arithmetic.
     */
    public static int ascensionOf(int effectiveLevel) {
        if (effectiveLevel < 1 || effectiveLevel > ProgressionRules.effectiveLevelCap()) {
            throw new IllegalArgumentException("effectiveLevel must be between 1 and "
                    + ProgressionRules.effectiveLevelCap());
        }
        int perStage = ProgressionRules.LEVELS_PER_ASCENSION;
        return Math.min(ProgressionRules.MAX_ASCENSION, effectiveLevel / perStage);
    }

    private static void requireEquals(String expected, String actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Hidden-path reachability drift on " + what + ": rules=" + expected + " facts=" + actual);
        }
    }
}
