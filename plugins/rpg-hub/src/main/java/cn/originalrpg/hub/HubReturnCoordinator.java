package cn.originalrpg.hub;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A small single-flight coordinator for hub returns.
 *
 * <p>One physical teleport is shared by all interested hub systems. Each
 * subscriber owns at most one callback, so command spam cannot grow an
 * unbounded callback list and a newer request from the same subsystem safely
 * replaces its stale callback.</p>
 */
final class HubReturnCoordinator<S> {
    enum Registration {
        START,
        JOINED
    }

    private final Map<UUID, LinkedHashMap<S, Consumer<Boolean>>> requests = new LinkedHashMap<>();

    synchronized Registration register(UUID playerId, S subscriber, Consumer<Boolean> completion) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(subscriber, "subscriber");
        Objects.requireNonNull(completion, "completion");
        LinkedHashMap<S, Consumer<Boolean>> subscribers = requests.get(playerId);
        if (subscribers != null) {
            subscribers.put(subscriber, completion);
            return Registration.JOINED;
        }
        LinkedHashMap<S, Consumer<Boolean>> first = new LinkedHashMap<>();
        first.put(subscriber, completion);
        requests.put(playerId, first);
        return Registration.START;
    }

    synchronized List<Consumer<Boolean>> finish(UUID playerId) {
        Map<S, Consumer<Boolean>> subscribers = requests.remove(playerId);
        return subscribers == null ? List.of() : List.copyOf(subscribers.values());
    }

    synchronized void clear() {
        requests.clear();
    }

    synchronized void cancel(UUID playerId) {
        if (playerId != null) requests.remove(playerId);
    }

    synchronized int subscriberCount(UUID playerId) {
        Map<S, Consumer<Boolean>> subscribers = requests.get(playerId);
        return subscribers == null ? 0 : subscribers.size();
    }

}
