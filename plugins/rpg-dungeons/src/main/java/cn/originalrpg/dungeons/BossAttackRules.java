package cn.originalrpg.dungeons;

final class BossAttackRules {
    private BossAttackRules() {
    }

    enum Shape {
        PULSE("pulse"),
        CROSS("cross"),
        RING("ring"),
        RUIN_DIAGONALS("ruin-diagonals"),
        RUIN_QUADRANTS("ruin-quadrants"),
        SULFUR_VENTS("sulfur-vents"),
        SULFUR_FISSURES("sulfur-fissures"),
        STORM_SWEEP("storm-sweep"),
        STORM_EYE("storm-eye");

        private final String key;

        Shape(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    record Pattern(
            Shape shape,
            double radius,
            double innerRadius,
            double laneHalfWidth,
            double rotationRadians
    ) {
        Pattern(Shape shape, double radius, double innerRadius, double laneHalfWidth) {
            this(shape, radius, innerRadius, laneHalfWidth, 0.0);
        }

        Pattern {
            if (shape == null || radius <= 0.0 || innerRadius < 0.0 || innerRadius >= radius
                    || laneHalfWidth < 0.0 || !Double.isFinite(rotationRadians)) {
                throw new IllegalArgumentException("Invalid boss attack pattern");
            }
        }
    }

    static Pattern pattern(int sequence) {
        return switch (Math.floorMod(sequence, 3)) {
            case 0 -> new Pattern(Shape.PULSE, 5.0, 0.0, 0.0);
            case 1 -> new Pattern(Shape.CROSS, 9.0, 0.0, 1.15);
            default -> new Pattern(Shape.RING, 8.0, 3.0, 0.0);
        };
    }

    /**
     * Every shipped dungeon family owns two recognizable positioning checks.
     * The ordinary and hidden encounters share a visual language, while the
     * sequence rotation prevents a solved spot from staying safe forever.
     */
    static Pattern pattern(String dungeonId, int sequence) {
        if (dungeonId == null) return pattern(sequence);
        double quarterTurn = Math.floorMod(sequence, 4) * Math.PI / 4.0;
        if (dungeonId.startsWith("ancient-ruins")) {
            return Math.floorMod(sequence, 3) == 0
                    ? new Pattern(Shape.RUIN_DIAGONALS, 8.5, 0.0, 0.85, quarterTurn)
                    : Math.floorMod(sequence, 3) == 1
                    ? new Pattern(Shape.RUIN_QUADRANTS, 8.0, 0.0, 0.0, quarterTurn)
                    : pattern(2);
        }
        if (dungeonId.startsWith("sulfur-depths")) {
            return Math.floorMod(sequence, 3) == 0
                    ? new Pattern(Shape.SULFUR_VENTS, 8.0, 0.0, 1.65, quarterTurn)
                    : Math.floorMod(sequence, 3) == 1
                    ? new Pattern(Shape.SULFUR_FISSURES, 8.5, 0.0, 0.62, quarterTurn)
                    : pattern(2);
        }
        if (dungeonId.startsWith("storm-vault")) {
            return Math.floorMod(sequence, 3) == 0
                    ? new Pattern(Shape.STORM_SWEEP, 8.5, 0.0, 0.0, quarterTurn)
                    : Math.floorMod(sequence, 3) == 1
                    ? new Pattern(Shape.STORM_EYE, 8.0, 2.0, 1.35, quarterTurn)
                    : pattern(1);
        }
        return pattern(sequence);
    }

    static boolean hits(Pattern pattern, double deltaX, double deltaZ) {
        double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (distanceSquared > pattern.radius() * pattern.radius()) return false;
        double cosine = Math.cos(pattern.rotationRadians());
        double sine = Math.sin(pattern.rotationRadians());
        double rotatedX = deltaX * cosine + deltaZ * sine;
        double rotatedZ = -deltaX * sine + deltaZ * cosine;
        return switch (pattern.shape()) {
            case PULSE -> true;
            case CROSS -> Math.abs(deltaX) <= pattern.laneHalfWidth()
                    || Math.abs(deltaZ) <= pattern.laneHalfWidth();
            case RING -> distanceSquared >= pattern.innerRadius() * pattern.innerRadius();
            case RUIN_DIAGONALS -> Math.abs(rotatedX - rotatedZ) <= pattern.laneHalfWidth()
                    || Math.abs(rotatedX + rotatedZ) <= pattern.laneHalfWidth();
            case RUIN_QUADRANTS -> rotatedX * rotatedZ >= 0.0;
            case SULFUR_VENTS -> ventHit(rotatedX, rotatedZ, pattern.laneHalfWidth());
            case SULFUR_FISSURES -> fissureHit(rotatedX, rotatedZ, pattern.radius(), pattern.laneHalfWidth());
            case STORM_SWEEP -> rotatedX >= -0.20;
            case STORM_EYE -> distanceSquared <= pattern.innerRadius() * pattern.innerRadius()
                    || distanceSquared >= Math.pow(pattern.radius() - pattern.laneHalfWidth(), 2.0);
        };
    }

    private static boolean ventHit(double x, double z, double ventRadius) {
        double offset = 3.35;
        return insideCircle(x - offset, z, ventRadius)
                || insideCircle(x + offset, z, ventRadius)
                || insideCircle(x, z - offset, ventRadius)
                || insideCircle(x, z + offset, ventRadius);
    }

    private static boolean fissureHit(double x, double z, double radius, double halfWidth) {
        if (Math.abs(x) > radius) return false;
        return Math.abs(z) <= halfWidth
                || Math.abs(z - 3.0) <= halfWidth
                || Math.abs(z + 3.0) <= halfWidth;
    }

    private static boolean insideCircle(double x, double z, double radius) {
        return x * x + z * z <= radius * radius;
    }
}
