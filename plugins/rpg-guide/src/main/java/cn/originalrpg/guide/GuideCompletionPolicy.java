package cn.originalrpg.guide;

final class GuideCompletionPolicy {
    private GuideCompletionPolicy() {
    }

    static boolean isVanillaExplorationAdvancement(
            String namespace, String key, boolean wildernessArrived, String currentWorld,
            String configuredWorld, boolean normalEnvironment
    ) {
        return wildernessArrived
                && normalEnvironment
                && configuredWorld != null
                && configuredWorld.equals(currentWorld)
                && "minecraft".equals(namespace)
                && key != null
                && !key.startsWith("recipes/");
    }

    static boolean starterBuildReady(int allocatedAttributePoints, int learnedSkills) {
        return allocatedAttributePoints >= 5 && learnedSkills > 0;
    }

    /** Completion follows the dynamic-level marker; reward eligibility is a separate combat rule. */
    static boolean adaptiveMonsterKillCompletes(Integer dynamicLevel) {
        return dynamicLevel != null;
    }
}
