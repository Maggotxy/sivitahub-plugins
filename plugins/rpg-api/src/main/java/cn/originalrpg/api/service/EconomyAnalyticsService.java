package cn.originalrpg.api.service;

import cn.originalrpg.api.model.EconomyReport;

import java.util.concurrent.CompletionStage;

public interface EconomyAnalyticsService {
    CompletionStage<EconomyReport> dailyReport();
}
