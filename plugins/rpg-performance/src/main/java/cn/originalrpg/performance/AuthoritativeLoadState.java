package cn.originalrpg.performance;

import cn.originalrpg.api.model.ServerLoadState;

/**
 * Establishes the first ledger-backed load baseline without treating plugin
 * startup as a real recovery from overload.
 */
final class AuthoritativeLoadState {
    private final LoadStateHysteresis hysteresis;
    private boolean initialised;

    AuthoritativeLoadState(LoadStateHysteresis hysteresis) {
        this.hysteresis = java.util.Objects.requireNonNull(hysteresis, "hysteresis");
    }

    ServerLoadState update(
            ServerLoadState current, ServerLoadState measured, boolean ledgerAvailable
    ) {
        if (!ledgerAvailable) return ServerLoadState.CRITICAL;
        if (!initialised) {
            initialised = true;
            return measured;
        }
        return hysteresis.update(current, measured);
    }
}
