package cn.originalrpg.world;

/** Pure timing and probability rules shared by live events and their status UI. */
final class WorldEventRules {
    static final long DAY_TICKS = 24_000L;
    static final long NIGHT_START = 13_000L;
    static final long NIGHT_END = 23_000L;

    private WorldEventRules() {
    }

    static boolean bloodMoonSpecialsAllowed(int lowestNearbyLevel, int minimumLevel) {
        return lowestNearbyLevel >= Math.max(1, minimumLevel);
    }

    static BloodMoonTiming bloodMoon(long fullTime, long worldTime, int configuredInterval) {
        int interval = Math.max(1, configuredInterval);
        long day = Math.max(0L, fullTime) / DAY_TICKS;
        long time = Math.floorMod(worldTime, DAY_TICKS);
        boolean scheduledDay = day > 0L && day % interval == 0L;
        // NIGHT_END is the first daylight tick. Keeping the upper bound
        // exclusive prevents a one-tick state where the event is still active
        // while its countdown already reads zero seconds.
        boolean active = scheduledDay && time >= NIGHT_START && time < NIGHT_END;

        long nightsUntil;
        if (scheduledDay && time < NIGHT_END) {
            nightsUntil = 0L;
        } else {
            long firstCandidate = scheduledDay ? day + 1L : day;
            long nextDay = ceilToMultiple(firstCandidate, interval);
            if (nextDay <= 0L) nextDay = interval;
            nightsUntil = Math.max(0L, nextDay - day);
        }
        long remainingTicks = Math.max(0L, NIGHT_END - time);
        long remainingSeconds = active ? (remainingTicks + 19L) / 20L : 0L;
        return new BloodMoonTiming(active, interval, nightsUntil, remainingSeconds);
    }

    static float bloodMoonProgress(long worldTime) {
        long time = Math.floorMod(worldTime, DAY_TICKS);
        double duration = NIGHT_END - NIGHT_START;
        return (float) Math.clamp((NIGHT_END - time) / duration, 0.0, 1.0);
    }

    static double huntBonus(int count, double chancePerStack, double maximumBonus) {
        double perStack = Math.clamp(chancePerStack, 0.0, 1.0);
        double maximum = Math.clamp(maximumBonus, 0.0, 1.0);
        return Math.min(maximum, Math.max(0, count - 1) * perStack);
    }

    static String huntIdentity(String entityType, String variant) {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("Hunt entity type is required");
        }
        String normalizedVariant = variant == null ? "" : variant.trim();
        return normalizedVariant.isEmpty() ? entityType : entityType + ":" + normalizedVariant;
    }

    static boolean continuesHunt(
            String previousType,
            String killedType,
            boolean sameWorld,
            double distanceSquared,
            double resetDistance,
            long elapsedMillis,
            long resetSeconds
    ) {
        if (previousType == null || killedType == null || resetDistance < 0.0 || resetSeconds < 0L) return false;
        double boundedDistance = Math.max(0.0, resetDistance);
        long resetMillis = saturatedMultiply(resetSeconds, 1_000L);
        return previousType.equals(killedType)
                && sameWorld
                && distanceSquared <= boundedDistance * boundedDistance
                && elapsedMillis >= 0L
                && elapsedMillis <= resetMillis;
    }

    static long huntSecondsRemaining(long elapsedMillis, long resetSeconds) {
        long resetMillis = saturatedMultiply(Math.max(0L, resetSeconds), 1_000L);
        long remaining = Math.max(0L, resetMillis - Math.max(0L, elapsedMillis));
        return remaining == 0L ? 0L : remaining / 1_000L + (remaining % 1_000L == 0L ? 0L : 1L);
    }

    private static long ceilToMultiple(long value, int multiple) {
        long remainder = Math.floorMod(value, multiple);
        if (remainder == 0L) return value;
        long increment = multiple - remainder;
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value <= 0L || multiplier <= 0L) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    record BloodMoonTiming(boolean active, int intervalNights, long nightsUntil, long remainingSeconds) {
    }
}
