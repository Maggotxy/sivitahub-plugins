package cn.originalrpg.dungeons;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Fixed combat identities for the six reviewed dungeon-wave archetypes. */
final class WaveSkillRules {
    private WaveSkillRules() { }

    enum Skill {
        RUIN_LUNGE("ruin-lunge", 14, 160L),
        ECHO_SEAL("echo-seal", 24, 180L),
        FUME_VENT("fume-vent", 20, 170L),
        SLAG_MORTAR("slag-mortar", 26, 190L),
        STATIC_LINK("static-link", 18, 180L),
        GUST_FLANK("gust-flank", 16, 155L);

        private final String key;
        private final int warningTicks;
        private final long intervalTicks;

        Skill(String key, int warningTicks, long intervalTicks) {
            this.key = key;
            this.warningTicks = warningTicks;
            this.intervalTicks = intervalTicks;
        }

        String key() {
            return key;
        }

        int warningTicks() {
            return warningTicks;
        }

        long intervalTicks() {
            return intervalTicks;
        }
    }

    static Optional<Skill> find(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return Arrays.stream(Skill.values()).filter(skill -> skill.key.equals(key)).findFirst();
    }

    static Set<String> keys() {
        return Arrays.stream(Skill.values()).map(Skill::key).collect(Collectors.toUnmodifiableSet());
    }
}
