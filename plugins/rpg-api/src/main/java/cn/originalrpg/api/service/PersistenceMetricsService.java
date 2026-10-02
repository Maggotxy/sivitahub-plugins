package cn.originalrpg.api.service;

import cn.originalrpg.api.model.PersistenceSnapshot;

public interface PersistenceMetricsService {
    PersistenceSnapshot snapshot();
}
