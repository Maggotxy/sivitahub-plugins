package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CivilizationProject;
import cn.originalrpg.api.model.CivilizationSeason;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Read-only, bounded public history from existing project and season authorities. */
public interface CivilizationHistoryService {
    int PAGE_SIZE = 8;
    int MAX_PAGE = 1_000;

    /** Pages are one-based; only completed projects are visible, including dissolved communities. */
    CompletionStage<List<CivilizationProject>> completedProjects(int page);

    /** Only COMPLETED settlements are visible; drafts, failures and rollbacks are never achievements. */
    CompletionStage<List<CivilizationSeason>> completedSeasons(int page);
}
