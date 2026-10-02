package cn.originalrpg.dungeons;

import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.SeasonExpeditionCompletion;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, all-party reward intent for one defeated managed boss. */
record DungeonRewardBatch(
        UUID runId,
        List<DungeonRewardRequest> requests,
        Optional<SeasonExpeditionCompletion> seasonCompletion
) {
    private static final int MAXIMUM_PARTY_SIZE = 8;

    DungeonRewardBatch(UUID runId, List<DungeonRewardRequest> requests) {
        this(runId, requests, Optional.empty());
    }

    DungeonRewardBatch {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requests, "requests");
        seasonCompletion = Objects.requireNonNull(seasonCompletion, "seasonCompletion");
        if (requests.isEmpty() || requests.size() > MAXIMUM_PARTY_SIZE) {
            throw new IllegalArgumentException("Invalid dungeon reward batch size");
        }
        requests = requests.stream()
                .sorted(Comparator.comparing(request -> request.playerId().toString()))
                .toList();
        HashSet<UUID> players = new HashSet<>();
        String dungeon = requests.getFirst().dungeonId();
        for (DungeonRewardRequest request : requests) {
            if (!runId.equals(request.runId()) || !dungeon.equals(request.dungeonId())
                    || !players.add(request.playerId())) {
                throw new IllegalArgumentException("Reward batch must contain one request per player and run");
            }
        }
        seasonCompletion.ifPresent(completion -> {
            if (!runId.equals(completion.runId()) || !dungeon.equals(completion.dungeonId())
                    || !players.equals(new HashSet<>(completion.participantIds()))
                    || completion.partySize() != players.size()) {
                throw new IllegalArgumentException(
                        "Season completion must describe the same dungeon reward run and party");
            }
        });
    }
}
