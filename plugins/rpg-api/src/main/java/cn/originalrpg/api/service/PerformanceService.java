package cn.originalrpg.api.service;

import cn.originalrpg.api.model.PerformanceSnapshot;
import cn.originalrpg.api.model.ServerLoadState;

public interface PerformanceService {
    PerformanceSnapshot snapshot();

    default ServerLoadState state() {
        return snapshot().state();
    }

    default boolean allowBackgroundWork() {
        return state() == ServerLoadState.NORMAL;
    }
}
