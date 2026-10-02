package cn.originalrpg.dungeons;

/** Selects one recognizable mechanic for each boss instead of reusing one generic rotation. */
final class BossSkillRules {
    private BossSkillRules() {
    }

    enum Skill {
        INSCRIPTION_CLEAVE("inscription-cleave", "attack"),
        FALLEN_SEAL("fallen-seal", "cast"),
        STONEWAKE_RING("stonewake-ring", "cast"),
        ECHO_REPLAY("echo-replay", "cast"),
        MEMORY_JUDGMENT("memory-judgment", "cast"),
        HOLLOW_VERDICT("hollow-verdict", "attack"),
        SULFUR_VENTS("sulfur-vents", "cast"),
        CRYSTAL_FISSURES("crystal-fissures", "attack"),
        MOLTEN_COLLAPSE("molten-collapse", "attack"),
        CINNABAR_MAGNET("cinnabar-magnet", "cast"),
        RED_PETAL_BURST("red-petal-burst", "cast"),
        REBIRTH_FISSURES("rebirth-fissures", "phase"),
        KEYSTONE_SWEEP("keystone-sweep", "attack"),
        CONDUCTOR_EYE("conductor-eye", "cast"),
        STATIC_CHAIN("static-chain", "cast"),
        SILENT_STEP("silent-step", "attack"),
        OATHBOUND_EYE("oathbound-eye", "cast"),
        THUNDER_SENTENCE("thunder-sentence", "attack");

        private final String key;
        private final String animation;

        Skill(String key, String animation) {
            this.key = key;
            this.animation = animation;
        }

        String key() {
            return key;
        }

        String animation() {
            return animation;
        }
    }

    record Selection(Skill skill, BossAttackRules.Pattern pattern) {
        Selection {
            if (skill == null || pattern == null) throw new IllegalArgumentException("Boss skill selection is incomplete");
        }
    }

    static Selection select(String dungeonId, int sequence, int phase) {
        int index = Math.floorMod(sequence + Math.max(0, phase), 3);
        double rotation = Math.floorMod(sequence, 4) * Math.PI / 4.0;
        return switch (dungeonId) {
            case "ancient-ruins" -> switch (index) {
                case 0 -> selection(Skill.INSCRIPTION_CLEAVE, BossAttackRules.Shape.RUIN_DIAGONALS, 8.5, 0.0, 0.85, rotation);
                case 1 -> selection(Skill.FALLEN_SEAL, BossAttackRules.Shape.RUIN_QUADRANTS, 8.0, 0.0, 0.0, rotation);
                default -> selection(Skill.STONEWAKE_RING, BossAttackRules.Shape.RING, 8.0, 3.0, 0.0, rotation);
            };
            case "ancient-ruins-hidden" -> switch (index) {
                case 0 -> selection(Skill.ECHO_REPLAY, BossAttackRules.Shape.RUIN_DIAGONALS, 8.5, 0.0, 0.85, rotation);
                case 1 -> selection(Skill.MEMORY_JUDGMENT, BossAttackRules.Shape.RUIN_QUADRANTS, 8.0, 0.0, 0.0, rotation);
                default -> selection(Skill.HOLLOW_VERDICT, BossAttackRules.Shape.RING, 8.0, 2.6, 0.0, rotation);
            };
            case "sulfur-depths" -> switch (index) {
                case 0 -> selection(Skill.SULFUR_VENTS, BossAttackRules.Shape.SULFUR_VENTS, 8.0, 0.0, 1.65, rotation);
                case 1 -> selection(Skill.CRYSTAL_FISSURES, BossAttackRules.Shape.SULFUR_FISSURES, 8.5, 0.0, 0.62, rotation);
                default -> selection(Skill.MOLTEN_COLLAPSE, BossAttackRules.Shape.PULSE, 5.4, 0.0, 0.0, rotation);
            };
            case "sulfur-depths-hidden" -> switch (index) {
                case 0 -> selection(Skill.CINNABAR_MAGNET, BossAttackRules.Shape.PULSE, 6.2, 0.0, 0.0, rotation);
                case 1 -> selection(Skill.RED_PETAL_BURST, BossAttackRules.Shape.SULFUR_VENTS, 8.2, 0.0, 1.55, rotation);
                default -> selection(Skill.REBIRTH_FISSURES, BossAttackRules.Shape.SULFUR_FISSURES, 8.5, 0.0, 0.60, rotation);
            };
            case "storm-vault" -> switch (index) {
                case 0 -> selection(Skill.KEYSTONE_SWEEP, BossAttackRules.Shape.STORM_SWEEP, 8.5, 0.0, 0.0, rotation);
                case 1 -> selection(Skill.CONDUCTOR_EYE, BossAttackRules.Shape.STORM_EYE, 8.0, 2.0, 1.35, rotation);
                default -> selection(Skill.STATIC_CHAIN, BossAttackRules.Shape.CROSS, 8.8, 0.0, 1.0, rotation);
            };
            case "storm-vault-hidden" -> switch (index) {
                case 0 -> selection(Skill.SILENT_STEP, BossAttackRules.Shape.CROSS, 9.0, 0.0, 1.05, rotation);
                case 1 -> selection(Skill.OATHBOUND_EYE, BossAttackRules.Shape.STORM_EYE, 8.0, 2.2, 1.25, rotation);
                default -> selection(Skill.THUNDER_SENTENCE, BossAttackRules.Shape.STORM_SWEEP, 9.0, 0.0, 0.0, rotation);
            };
            default -> new Selection(Skill.STONEWAKE_RING, BossAttackRules.pattern(sequence));
        };
    }

    static boolean recordsPositions(Skill skill) {
        return skill == Skill.ECHO_REPLAY || skill == Skill.MEMORY_JUDGMENT;
    }

    static boolean leavesHazard(Skill skill) {
        return skill == Skill.SULFUR_VENTS || skill == Skill.CRYSTAL_FISSURES
                || skill == Skill.RED_PETAL_BURST || skill == Skill.REBIRTH_FISSURES;
    }

    static boolean pulls(Skill skill) {
        return skill == Skill.CINNABAR_MAGNET || skill == Skill.HOLLOW_VERDICT;
    }

    static boolean chains(Skill skill) {
        return skill == Skill.STATIC_CHAIN || skill == Skill.THUNDER_SENTENCE;
    }

    private static Selection selection(
            Skill skill, BossAttackRules.Shape shape, double radius,
            double innerRadius, double laneHalfWidth, double rotation
    ) {
        return new Selection(skill, new BossAttackRules.Pattern(
                shape, radius, innerRadius, laneHalfWidth, rotation));
    }
}
