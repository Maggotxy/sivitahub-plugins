package cn.originalrpg.api.model;

public record EnergyRecovery(int reservations, int energy) {
    public EnergyRecovery {
        if (reservations < 0 || energy < 0) throw new IllegalArgumentException("Recovery values cannot be negative");
    }
}
