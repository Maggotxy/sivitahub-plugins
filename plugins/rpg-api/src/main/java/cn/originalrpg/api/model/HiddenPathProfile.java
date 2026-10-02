package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistent ownership record. A hidden path cannot be traded or reset by reconnecting. */
public record HiddenPathProfile(UUID playerId, HiddenPath path, Instant activatedAt) {
    public HiddenPathProfile {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }
}
