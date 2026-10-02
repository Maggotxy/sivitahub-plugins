package cn.originalrpg.hub;

final class HomeWorldRules {
    private HomeWorldRules() {
    }

    static boolean isSurvivalWorld(String actualWorld, boolean normalEnvironment, String configuredWorld) {
        return normalEnvironment
                && actualWorld != null
                && configuredWorld != null
                && !configuredWorld.isBlank()
                && actualWorld.equals(configuredWorld);
    }
}
