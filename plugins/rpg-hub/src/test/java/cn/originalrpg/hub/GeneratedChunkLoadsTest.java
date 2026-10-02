package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratedChunkLoadsTest {
    @Test
    void returnsEveryLoadedNeighborInRequestOrder() {
        List<String> result = GeneratedChunkLoads.collect(List.of(
                CompletableFuture.completedFuture("north"),
                CompletableFuture.completedFuture("center"),
                CompletableFuture.completedFuture("south"))).join();

        assertEquals(List.of("north", "center", "south"), result);
    }

    @Test
    void rejectsTheWholeLandingWhenOneUngeneratedChunkResolvesNull() {
        List<String> result = GeneratedChunkLoads.collect(List.of(
                CompletableFuture.completedFuture("north"),
                CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture("south"))).join();

        assertTrue(result.isEmpty());
    }
}
