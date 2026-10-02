package cn.originalrpg.skills;

/**
 * Pure balance policy for equipment-granted base skills. Cross-class access is
 * deliberately weaker and more expensive; owning the real skill turns the
 * same rune into a small, bounded mastery bonus instead of a second cast.
 */
final class EquipmentBorrowingRules {
    enum CastMode { LEARNED, BORROWED, MASTERED }

    private EquipmentBorrowingRules() {
    }

    static CastMode mode(boolean learned, boolean activeRune) {
        if (learned) return activeRune ? CastMode.MASTERED : CastMode.LEARNED;
        return activeRune ? CastMode.BORROWED : CastMode.LEARNED;
    }

    static int manaCost(int base, CastMode mode, double borrowedMultiplier) {
        double multiplier = mode == CastMode.BORROWED
                ? clampFinite(borrowedMultiplier, 1.0, 3.0, 1.5) : 1.0;
        return Math.max(1, (int) Math.ceil(Math.max(1, base) * multiplier));
    }

    static long cooldownMillis(
            long baseSeconds, CastMode mode, double borrowedMultiplier, double masteredMultiplier,
            int skillHaste
    ) {
        long boundedSeconds = Math.max(1L, baseSeconds);
        long baseMillis = boundedSeconds > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE : boundedSeconds * 1000L;
        return cooldownFromBaseMillis(
                baseMillis,
                mode, borrowedMultiplier, masteredMultiplier, skillHaste);
    }

    static long cooldownFromBaseMillis(
            long baseMillis, CastMode mode, double borrowedMultiplier, double masteredMultiplier,
            int skillHaste
    ) {
        double modeMultiplier = switch (mode) {
            case BORROWED -> clampFinite(borrowedMultiplier, 1.0, 3.0, 1.5);
            case MASTERED -> clampFinite(masteredMultiplier, 0.70, 1.0, 0.85);
            case LEARNED -> 1.0;
        };
        int boundedHaste = Math.clamp(skillHaste, -30, 60);
        double millis = Math.max(1000L, baseMillis) * modeMultiplier
                / (1.0 + boundedHaste / 100.0);
        return Math.max(1000L, Math.round(millis));
    }

    static double powerMultiplier(
            CastMode mode, double borrowedMultiplier, double masteredMultiplier
    ) {
        return switch (mode) {
            case BORROWED -> clampFinite(borrowedMultiplier, 0.25, 0.85, 0.65);
            case MASTERED -> clampFinite(masteredMultiplier, 1.0, 1.25, 1.15);
            case LEARNED -> 1.0;
        };
    }

    static int durationTicks(
            int baseTicks, CastMode mode, double borrowedMultiplier, double masteredMultiplier
    ) {
        double multiplier = switch (mode) {
            case BORROWED -> clampFinite(borrowedMultiplier, 0.40, 0.85, 0.65);
            case MASTERED -> clampFinite(masteredMultiplier, 1.0, 1.25, 1.15);
            case LEARNED -> 1.0;
        };
        return Math.max(1, (int) Math.round(Math.max(1, baseTicks) * multiplier));
    }

    static int strainTicks(int configured) {
        return Math.clamp(configured, 20, 120);
    }

    private static double clampFinite(double value, double minimum, double maximum, double fallback) {
        return Math.clamp(Double.isFinite(value) ? value : fallback, minimum, maximum);
    }
}
