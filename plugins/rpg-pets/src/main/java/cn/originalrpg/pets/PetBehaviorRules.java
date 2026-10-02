package cn.originalrpg.pets;

/** Maps the two public modes onto the deliberately smaller MyPet mode set. */
final class PetBehaviorRules {
    private PetBehaviorRules() { }

    static String publicMode(String requested) {
        return "aggressive".equalsIgnoreCase(requested) ? "aggressive" : "friendly";
    }

    static String engineMode(String publicMode) {
        return "aggressive".equals(publicMode) ? "aggressive" : "raid";
    }
}
