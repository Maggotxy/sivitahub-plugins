package cn.originalrpg.dungeons;

import java.util.Collection;
import java.util.EnumSet;

final class SeasonExpeditionAffixRules {
    private SeasonExpeditionAffixRules() {
    }

    static Modifiers modifiers(Collection<SeasonExpeditionAffix> affixes) {
        EnumSet<SeasonExpeditionAffix> set = affixes == null || affixes.isEmpty()
                ? EnumSet.noneOf(SeasonExpeditionAffix.class)
                : EnumSet.copyOf(affixes);
        return new Modifiers(
                set.contains(SeasonExpeditionAffix.FORTIFIED) ? 1.25 : 1.0,
                set.contains(SeasonExpeditionAffix.FORTIFIED) ? 1.15 : 1.0,
                set.contains(SeasonExpeditionAffix.BRUTAL) ? 1.15 : 1.0,
                set.contains(SeasonExpeditionAffix.SWARMING) ? 1.30 : 1.0,
                set.contains(SeasonExpeditionAffix.RELENTLESS) ? 0.82 : 1.0);
    }

    record Modifiers(
            double bossHealthMultiplier,
            double waveHealthMultiplier,
            double damageMultiplier,
            double waveCountMultiplier,
            double attackIntervalMultiplier
    ) {
        int waveCount(int base) {
            return Math.max(1, (int) Math.ceil(base * waveCountMultiplier));
        }

        double bossHealth(double base) {
            return base * bossHealthMultiplier;
        }

        double waveHealth(double base) {
            return base * waveHealthMultiplier;
        }

        double damage(double base) {
            return base * damageMultiplier;
        }

        long attackInterval(long base) {
            return Math.max(1L, Math.round(base * attackIntervalMultiplier));
        }
    }
}
