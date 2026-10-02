package cn.originalrpg.pets;

final class PetAbilityState {
    private double energy = 100.0;
    private long updatedAt = System.currentTimeMillis();
    private long readyAt;

    void recharge(long now, double perSecond) {
        if (now <= updatedAt) return;
        energy = Math.min(100.0, energy + (now - updatedAt) / 1000.0 * perSecond);
        updatedAt = now;
    }

    boolean canUse(long now, double cost) {
        return now >= readyAt && energy >= cost;
    }

    void consume(long now, double cost, long cooldownMillis) {
        energy = Math.max(0.0, energy - cost);
        readyAt = now + cooldownMillis;
        updatedAt = now;
    }

    int energy() {
        return (int) Math.floor(energy);
    }

    long remainingSeconds(long now) {
        return Math.max(0L, (readyAt - now + 999L) / 1000L);
    }
}
