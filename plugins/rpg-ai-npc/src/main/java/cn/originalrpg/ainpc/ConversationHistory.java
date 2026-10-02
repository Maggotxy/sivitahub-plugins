package cn.originalrpg.ainpc;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

final class ConversationHistory {
    private final int maximumTurns;
    private final long ttlNanos;
    private final Deque<Turn> turns = new ArrayDeque<>();
    private long lastAccess = System.nanoTime();

    ConversationHistory(int maximumTurns, Duration ttl) {
        this.maximumTurns = maximumTurns;
        ttlNanos = ttl.toNanos();
    }

    synchronized String snapshot() {
        if (expired()) turns.clear();
        lastAccess = System.nanoTime();
        StringBuilder result = new StringBuilder();
        for (Turn turn : turns) {
            if (!result.isEmpty()) result.append('\n');
            result.append("Player: ").append(turn.question()).append('\n')
                    .append("Guide: ").append(turn.answer());
        }
        return result.toString();
    }

    synchronized void add(String question, String answer) {
        if (expired()) turns.clear();
        turns.addLast(new Turn(question, answer));
        while (turns.size() > maximumTurns) turns.removeFirst();
        lastAccess = System.nanoTime();
    }

    private boolean expired() {
        return System.nanoTime() - lastAccess > ttlNanos;
    }

    private record Turn(String question, String answer) {
    }
}
