package cn.originalrpg.performance;

import cn.originalrpg.api.model.ServerLoadState;

/** Keeps optional-work degradation stable instead of oscillating per sample. */
final class LoadStateHysteresis {
    private final int samplesToDegrade;
    private final int samplesToRecover;
    private int badSamples;
    private int recoverySamples;

    LoadStateHysteresis(int samplesToDegrade, int samplesToRecover) {
        this.samplesToDegrade = Math.clamp(samplesToDegrade, 1, 20);
        this.samplesToRecover = Math.clamp(samplesToRecover, 2, 120);
    }

    ServerLoadState update(ServerLoadState current, ServerLoadState measured) {
        if (measured.ordinal() > current.ordinal()) {
            recoverySamples = 0;
            if (++badSamples >= samplesToDegrade) {
                badSamples = 0;
                return measured;
            }
            return current;
        }
        if (measured.ordinal() < current.ordinal()) {
            badSamples = 0;
            if (++recoverySamples >= samplesToRecover) {
                recoverySamples = 0;
                return measured;
            }
            return current;
        }
        badSamples = 0;
        recoverySamples = 0;
        return current;
    }
}
