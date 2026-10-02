package cn.originalrpg.hub;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Loads only existing chunks and treats any absent neighbor as one failed landing. */
final class GeneratedChunkLoads {
    private GeneratedChunkLoads() {
    }

    static CompletableFuture<List<Chunk>> square(World world, int centerX, int centerZ, int radius) {
        return rectangle(world, centerX - radius, centerX + radius, centerZ - radius, centerZ + radius, false);
    }

    static CompletableFuture<List<Chunk>> rectangle(
            World world, int minX, int maxX, int minZ, int maxZ, boolean generate
    ) {
        int width = Math.max(0, maxX - minX + 1);
        int depth = Math.max(0, maxZ - minZ + 1);
        List<CompletableFuture<Chunk>> loads = new ArrayList<>(Math.multiplyExact(width, depth));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Paper resolves an absent ungenerated chunk as null when gen=false.
                // This keeps both region-file lookup and loading out of the caller's
                // synchronous command/event path without turning travel into generation.
                loads.add(world.getChunkAtAsync(x, z, generate));
            }
        }
        return collect(loads);
    }

    static <T> CompletableFuture<List<T>> collect(List<CompletableFuture<T>> loads) {
        if (loads.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<T> completed = new ArrayList<>(loads.size());
            for (CompletableFuture<T> load : loads) {
                T value = load.getNow(null);
                if (value == null) return List.of();
                completed.add(value);
            }
            return List.copyOf(completed);
        });
    }
}
