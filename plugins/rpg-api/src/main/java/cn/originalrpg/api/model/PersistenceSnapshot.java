package cn.originalrpg.api.model;

public record PersistenceSnapshot(
        int activeConnections,
        int idleConnections,
        int totalConnections,
        int threadsAwaitingConnection,
        int executorActiveThreads,
        int executorQueueSize,
        int executorQueueRemainingCapacity
) {
    public static PersistenceSnapshot unavailable() {
        return new PersistenceSnapshot(-1, -1, -1, -1, -1, -1, -1);
    }
}
