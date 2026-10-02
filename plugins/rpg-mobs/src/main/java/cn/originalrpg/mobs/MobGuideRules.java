package cn.originalrpg.mobs;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.ProgressionRules;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Truthful recommendations derived from the same adaptive offsets and live dungeon configuration. */
final class MobGuideRules {
    enum HuntLocation {
        HUB,
        OVERWORLD_FRONTIER,
        OTHER
    }

    private MobGuideRules() {
    }

    static int minimumAdaptiveLevel(int effectiveLevel) {
        return Math.max(1, effectiveLevel - 3);
    }

    static int maximumAdaptiveLevel(int effectiveLevel) {
        return Math.max(1, effectiveLevel + MobScalingRules.positiveOffsetUpperBound(
                effectiveLevel, 10, 3));
    }

    static DungeonOption recommendedNormal(int effectiveLevel, List<DungeonOption> options) {
        return options.stream()
                .filter(option -> !option.hidden() && option.minimumLevel() <= effectiveLevel)
                .max(Comparator.comparingInt(DungeonOption::minimumLevel))
                .orElse(null);
    }

    static DungeonOption recommendedHidden(int effectiveLevel, List<DungeonOption> options) {
        return options.stream()
                .filter(option -> option.hidden() && option.minimumLevel() <= effectiveLevel)
                .max(Comparator.comparingInt(DungeonOption::minimumLevel))
                .orElse(null);
    }

    static DungeonOption nextUnlock(int effectiveLevel, List<DungeonOption> options) {
        return options.stream()
                .filter(option -> option.minimumLevel() > effectiveLevel)
                .min(Comparator.comparingInt(DungeonOption::minimumLevel)
                        .thenComparing(DungeonOption::hidden))
                .orElse(null);
    }

    /**
     * Builds the data shown by the client dialog without depending on Bukkit or
     * Paper UI classes. Keeping this calculation here makes the recommendation
     * truthful, deterministic, and inexpensive to test.
     */
    static GuidePlan guidePlan(
            PlayerProfile profile,
            long minimumExperience,
            long normalMultiplier,
            long eliteMultiplier,
            List<DungeonOption> options
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(options, "options");
        minimumExperience = Math.max(1L, minimumExperience);
        normalMultiplier = Math.max(1L, normalMultiplier);
        eliteMultiplier = Math.max(1L, eliteMultiplier);

        int effectiveLevel = profile.effectiveLevel();
        int minimumMob = minimumAdaptiveLevel(effectiveLevel);
        int maximumMob = maximumAdaptiveLevel(effectiveLevel);
        boolean capped = profile.level() >= ProgressionRules.levelCap(profile.ascension());
        long needed = capped ? 0L
                : ProgressionRules.experienceToNextLevel(profile.ascension(), profile.level());
        // 阶段总预算（名义值）来自 ProgressionRules.totalExperienceForAscension()；
        // 已投入本阶段的经验用 totalExperienceToEffective() 的差分还原，两者共用同一套
        // 逐级经验来源，因此不会与升级循环（JdbcProfileService / JdbcDungeonRewardService）漂移。
        long stageBudget = ProgressionRules.totalExperienceForAscension(profile.ascension());
        long stageSpent = ProgressionRules.stageExperienceSpent(
                profile.ascension(), profile.level(), profile.experience());
        return new GuidePlan(
                profile.ascension(),
                profile.level(),
                effectiveLevel,
                profile.experience(),
                needed,
                capped ? 0L : Math.max(0L, needed - profile.experience()),
                stageBudget,
                stageSpent,
                ProgressionRules.stageExperienceRemaining(
                        profile.ascension(), profile.level(), profile.experience()),
                ProgressionRules.totalExperienceToEffective(effectiveLevel),
                capped,
                minimumMob,
                maximumMob,
                MobExperienceRules.reward(minimumMob, false,
                        minimumExperience, normalMultiplier, eliteMultiplier),
                MobExperienceRules.reward(maximumMob, false,
                        minimumExperience, normalMultiplier, eliteMultiplier),
                MobExperienceRules.reward(minimumMob, true,
                        minimumExperience, normalMultiplier, eliteMultiplier),
                MobExperienceRules.reward(maximumMob, true,
                        minimumExperience, normalMultiplier, eliteMultiplier),
                recommendedNormal(effectiveLevel, options),
                recommendedHidden(effectiveLevel, options),
                nextUnlock(effectiveLevel, options));
    }

    /**
     * Location-aware next action for the hunting guide. The frontier route is
     * intentionally commandless: the player is already where the objective
     * happens, so sending them back through /rpghelp would create a loop.
     */
    static HuntRoute huntRoute(HuntLocation location) {
        Objects.requireNonNull(location, "location");
        return switch (location) {
            case HUB -> new HuntRoute("hub", "/wild");
            case OVERWORLD_FRONTIER -> new HuntRoute("frontier", null);
            case OTHER -> new HuntRoute("other", "/hub");
        };
    }

    /** Overflow-safe equivalent of the hub's circular protection check. */
    static boolean insideHub(int centerX, int centerZ, int radius, int x, int z) {
        if (radius < 0) return false;
        long dx = (long) x - centerX;
        long dz = (long) z - centerZ;
        long safeRadius = radius;
        if (Math.abs(dx) > safeRadius || Math.abs(dz) > safeRadius) return false;
        return dx * dx <= safeRadius * safeRadius - dz * dz;
    }

    record GuidePlan(
            int ascension,
            int level,
            int effectiveLevel,
            long experience,
            long neededExperience,
            long remainingExperience,
            long stageExperienceBudget,
            long stageExperienceSpent,
            long stageExperienceRemaining,
            long totalExperienceToEffective,
            boolean capped,
            int minimumMobLevel,
            int maximumMobLevel,
            long minimumNormalExperience,
            long maximumNormalExperience,
            long minimumEliteExperience,
            long maximumEliteExperience,
            DungeonOption recommendedNormal,
            DungeonOption recommendedHidden,
            DungeonOption nextUnlock
    ) {
    }

    record DungeonOption(String id, int minimumLevel, long experience, boolean hidden) {
        DungeonOption {
            if (id == null || id.isBlank() || minimumLevel < 1 || experience < 0L) {
                throw new IllegalArgumentException("Invalid dungeon guide option");
            }
        }
    }

    record HuntRoute(String id, String command) {
        HuntRoute {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Hunt route requires an id");
        }

        boolean alreadyThere() {
            return command == null;
        }
    }
}
