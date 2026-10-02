package cn.originalrpg.api.model;

import java.util.Objects;

public record ChapterTransitionResult(Status status, ChapterState state) {
    public ChapterTransitionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(state, "state");
    }

    public enum Status {
        SUCCESS,
        ALREADY_CURRENT,
        UNKNOWN_CHAPTER,
        NOT_NEXT_CHAPTER,
        INVALID_REQUEST
    }
}
