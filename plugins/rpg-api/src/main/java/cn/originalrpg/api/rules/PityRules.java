package cn.originalrpg.api.rules;

/**
 * Deterministic boundary for a random drop with a hard failure guarantee.
 *
 * <p>The stored value is the number of consecutive eligible failures, not the
 * number of total attempts. A successful random roll and a guaranteed roll
 * both reset that value to zero.</p>
 */
public final class PityRules {
    private PityRules() {
    }

    public static Outcome attempt(int failures, int guarantee, double chance, double randomUnit) {
        if (failures < 0 || guarantee < 1 || !Double.isFinite(chance)
                || chance < 0.0 || chance > 1.0 || !Double.isFinite(randomUnit)
                || randomUnit < 0.0 || randomUnit >= 1.0) {
            throw new IllegalArgumentException("Invalid pity attempt input");
        }
        int attempt = failures == Integer.MAX_VALUE ? Integer.MAX_VALUE : failures + 1;
        boolean guaranteed = attempt >= guarantee;
        boolean dropped = guaranteed || randomUnit < chance;
        return new Outcome(dropped, dropped ? 0 : attempt, guaranteed);
    }

    public record Outcome(boolean dropped, int failures, boolean guaranteed) {
    }
}
