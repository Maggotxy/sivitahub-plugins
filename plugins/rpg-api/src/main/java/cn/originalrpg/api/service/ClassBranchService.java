package cn.originalrpg.api.service;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.ClassBranchResult;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ClassBranchService {
    CompletionStage<Map<Integer, String>> branches(UUID playerId);

    default CompletionStage<ClassBranchResult> choose(
            UUID playerId,
            Archetype requiredArchetype,
            int stage,
            String branchId
    ) {
        return choose(playerId, requiredArchetype, stage, branchId, Set.of());
    }

    CompletionStage<ClassBranchResult> choose(
            UUID playerId,
            Archetype requiredArchetype,
            int stage,
            String branchId,
            Set<String> allowedPreviousBranches
    );
}
