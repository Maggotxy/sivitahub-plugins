package cn.originalrpg.enhancement;

import java.util.Arrays;

final class RefinementWeights {
    private static final int BASE_WEIGHT = 100;
    private static final int BONUS_PER_STRENGTH = 75;

    private RefinementWeights() {
    }

    static int totalWeight(String[] stats, String bias, int strength) {
        int safeStrength = validBias(stats, bias) ? Math.clamp(strength, 0, 3) : 0;
        return stats.length * BASE_WEIGHT + safeStrength * BONUS_PER_STRENGTH;
    }

    static String select(String[] stats, String bias, int strength, int ticket) {
        if (stats.length == 0) throw new IllegalArgumentException("stats cannot be empty");
        int total = totalWeight(stats, bias, strength);
        if (ticket < 0 || ticket >= total) throw new IllegalArgumentException("ticket outside weight range");
        int bonus = validBias(stats, bias) ? Math.clamp(strength, 0, 3) * BONUS_PER_STRENGTH : 0;
        int cursor = ticket;
        for (String stat : stats) {
            int weight = BASE_WEIGHT + (stat.equals(bias) ? bonus : 0);
            if (cursor < weight) return stat;
            cursor -= weight;
        }
        throw new IllegalStateException("weighted selection did not resolve");
    }

    private static boolean validBias(String[] stats, String bias) {
        return bias != null && Arrays.asList(stats).contains(bias);
    }
}
