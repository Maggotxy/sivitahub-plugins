package cn.originalrpg.api.service;

import cn.originalrpg.api.model.AdminTestState;
import cn.originalrpg.api.model.Archetype;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Isolated persistence bridge for OP-only gameplay testing.
 *
 * <p>Activation snapshots the real profile and learned skills exactly once.
 * Restore atomically puts that snapshot back, so test progression never leaks
 * into the player's normal character.</p>
 */
public interface AdminTestService {
    CompletionStage<AdminTestState> status(UUID playerId);

    CompletionStage<AdminTestState> activate(
            UUID playerId,
            Archetype archetype,
            Map<String, Integer> skillLevels,
            int attributeValue,
            int skillPoints
    );

    CompletionStage<AdminTestState> switchArchetype(UUID playerId, Archetype archetype);

    CompletionStage<AdminTestState> restore(UUID playerId);
}
