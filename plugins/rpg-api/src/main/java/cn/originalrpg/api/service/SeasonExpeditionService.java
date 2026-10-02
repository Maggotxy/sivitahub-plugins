package cn.originalrpg.api.service;

import cn.originalrpg.api.model.SeasonExpeditionCompletion;
import cn.originalrpg.api.model.SeasonExpeditionLeaderboardEntry;
import cn.originalrpg.api.model.SeasonExpeditionProfile;
import cn.originalrpg.api.model.SeasonExpeditionResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface SeasonExpeditionService {
    CompletionStage<SeasonExpeditionResult> complete(SeasonExpeditionCompletion completion);

    CompletionStage<SeasonExpeditionProfile> profile(
            UUID playerId, String seasonId, String rotationId, int partySize
    );

    CompletionStage<List<SeasonExpeditionLeaderboardEntry>> leaderboard(
            String seasonId, String rotationId, int partySize, int limit
    );

    CompletionStage<Optional<SeasonExpeditionLeaderboardEntry>> firstClear(
            String seasonId, String rotationId
    );
}
