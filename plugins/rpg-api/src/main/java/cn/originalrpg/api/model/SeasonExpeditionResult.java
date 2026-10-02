package cn.originalrpg.api.model;

import java.util.List;
import java.util.Objects;

public record SeasonExpeditionResult(
        Status status,
        boolean serverFirst,
        List<SeasonExpeditionPlayerResult> players
) {
    public SeasonExpeditionResult {
        Objects.requireNonNull(status, "status");
        players = List.copyOf(Objects.requireNonNull(players, "players"));
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        PROGRESS_LIMIT,
        INVALID
    }
}
