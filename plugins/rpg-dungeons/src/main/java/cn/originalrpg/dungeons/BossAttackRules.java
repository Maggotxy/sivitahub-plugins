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

    /**
     * 预警窗口的表现层硬下限（G-209）。
     *
     * <p>文档早期承诺 0.4–0.8 秒（8–16 tick），但同帧发送的预警文案是中位数 24 个中文字符
     * 的完整躲避指令，读不完就等于没有预警。实测下限：
     * <ul>
     *   <li>图案在 tick 0 立即绘制，之后每 5 tick 重绘一次 —— 8 tick 窗口只有 1 次重绘；</li>
     *   <li>中文快读约 7 字/秒，18–31 字需要 2.6–4.4 秒。</li>
     * </ul>
     * 因此配置最低只能取 20 tick（1.0 秒）：0.4–0.8 秒能做到"画面有图案"，但做不到
     * "玩家看得懂要往哪躲"，而后者才是预警的设计意图（见 docs/GAME_DESIGN.md §Boss 预警）。
     */
    static final int MINIMUM_READABLE_TELEGRAPH_TICKS = 20;

    /** 把配置的预警窗口收敛到表现层能保证的区间：下限见上，上限 80 tick。 */
    static long readableTelegraphTicks(long configured) {
        return Math.clamp(configured, MINIMUM_READABLE_TELEGRAPH_TICKS, 80L);
    }

    /** 预警窗口内实际发生的重绘次数（tick 0 的首次绘制另算）。 */
    static int telegraphRedraws(long telegraphTicks) {
        int redraws = 0;
        for (long elapsed = 1; elapsed < telegraphTicks; elapsed++) {
            if ((telegraphTicks - elapsed) % 5L == 0L) redraws++;
        }
        return redraws;
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
