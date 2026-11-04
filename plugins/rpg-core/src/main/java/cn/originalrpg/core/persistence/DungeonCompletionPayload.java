package cn.originalrpg.core.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Objects;
import java.util.UUID;

/** Version-one outbox payload; IDs are data, never concatenated JSON or SQL. */
record DungeonCompletionPayload(UUID runId, UUID playerId, String dungeonId) {
    DungeonCompletionPayload {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(playerId, "playerId");
        if (dungeonId == null || dungeonId.isBlank() || dungeonId.length() > 64) {
            throw new IllegalArgumentException("Invalid dungeon completion identity");
        }
    }

    String json() {
        JsonObject json = new JsonObject();
        json.addProperty("runId", runId.toString());
        json.addProperty("player", playerId.toString());
        json.addProperty("dungeon", dungeonId);
        return json.toString();
    }

    static DungeonCompletionPayload parse(OutboxEvent event) {
        JsonObject json = JsonParser.parseString(event.payload()).getAsJsonObject();
        if (json.size() != 3) throw new IllegalArgumentException("Unknown dungeon completion payload schema");
        for (String key : new String[]{"runId", "player", "dungeon"}) {
            if (!json.has(key) || !json.get(key).isJsonPrimitive()
                    || !json.getAsJsonPrimitive(key).isString()) {
                throw new IllegalArgumentException("Dungeon completion field must be a string: " + key);
            }
        }
        DungeonCompletionPayload payload = new DungeonCompletionPayload(
                UUID.fromString(json.get("runId").getAsString()),
                UUID.fromString(json.get("player").getAsString()), json.get("dungeon").getAsString());
        if (!payload.runId().toString().equals(event.aggregateId())) {
            throw new IllegalArgumentException("Dungeon completion aggregate does not match the payload");
        }
        return payload;
    }
}
