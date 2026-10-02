package cn.originalrpg.pets;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class PetProgressRules {
    static final List<String> PERSONALITIES = List.of("brave", "calm", "curious", "loyal");
    static final List<String> TRAITS = List.of("ironhide", "quickstep", "resonant", "keen_eye");
    static final List<String> SKINS = List.of("natural", "resonant", "ascendant");

    private PetProgressRules() {
    }

    static Identity identity(UUID petId) {
        long mixed = petId.getMostSignificantBits() ^ Long.rotateLeft(petId.getLeastSignificantBits(), 23);
        int personality = Math.floorMod(Long.hashCode(mixed), PERSONALITIES.size());
        int trait = Math.floorMod(Long.hashCode(Long.rotateLeft(mixed, 17)), TRAITS.size());
        return new Identity(PERSONALITIES.get(personality), TRAITS.get(trait));
    }

    static int minimumLevelForNextStage(int currentStage) {
        return switch (currentStage) {
            case 0 -> 10;
            case 1 -> 20;
            default -> Integer.MAX_VALUE;
        };
    }

    static int minimumBondForNextStage(int currentStage) {
        return switch (currentStage) {
            case 0 -> 250;
            case 1 -> 750;
            default -> Integer.MAX_VALUE;
        };
    }

    static String skinForNextStage(int currentStage) {
        return switch (currentStage) {
            case 0 -> "resonant";
            case 1 -> "ascendant";
            default -> "natural";
        };
    }

    static int adjustedBond(String personalityId, int base) {
        if (base <= 0) return 0;
        return "loyal".equals(personalityId) ? Math.max(base + 1, (int) Math.ceil(base * 1.20)) : base;
    }

    static String route(String skilltreeName) {
        if (skilltreeName == null) return "none";
        String normalized = skilltreeName.toLowerCase(Locale.ROOT);
        if (normalized.contains("guardian")) return "guardian";
        if (normalized.contains("hunter")) return "hunter";
        if (normalized.contains("healer")) return "healer";
        if (normalized.contains("ranger")) return "ranger";
        return "none";
    }

    record Identity(String personalityId, String traitId) {
    }
}
