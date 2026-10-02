package cn.originalrpg.api.service;

import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.DungeonRewardSettlement;

import java.util.concurrent.CompletionStage;
import java.util.UUID;

/** Atomically settles dungeon progression, pity and durable item delivery. */
public interface DungeonRewardService {
    CompletionStage<DungeonRewardSettlement> settle(DungeonRewardRequest request);

    /**
     * Returns durable completion evidence for onboarding and reconnect recovery.
     * This query never infers a clear from inventory contents or a transient Bukkit event.
     */
    CompletionStage<Boolean> hasAnyCompletion(UUID playerId);
}
