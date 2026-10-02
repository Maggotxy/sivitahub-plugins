package cn.originalrpg.hub;

import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class HubBuilder {
    private final Plugin plugin;
    private BukkitTask task;
    private boolean preparing;

    HubBuilder(Plugin plugin) {
        this.plugin = plugin;
    }

    boolean running() {
        return preparing || task != null;
    }

    void start(HubSite site, int blocksPerTick, Consumer<HubSite> completion, Runnable failure) {
        if (running()) return;
        preparing = true;
        prepareChunks(site).whenComplete((chunks, error) -> sync(() -> {
            preparing = false;
            if (error != null || chunks == null || chunks.isEmpty()) {
                plugin.getLogger().severe("Hub construction could not prepare its complete chunk area");
                stop();
                failure.run();
                return;
            }
            beginChanges(site, blocksPerTick, completion);
        }));
    }

    private void beginChanges(HubSite site, int blocksPerTick, Consumer<HubSite> completion) {
        List<HubBlueprint.BlockChange> changes = HubBlueprint.create(site);
        plugin.getLogger().info("Hub blueprint queued with " + changes.size() + " block changes at "
                + site.centerX() + "," + site.baseY() + "," + site.centerZ());
        int batch = Math.clamp(blocksPerTick, 1_000, 20_000);
        int[] cursor = {0};
        int[] reportedQuarter = {-1};
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(changes.size(), cursor[0] + batch);
            World world = site.world();
            for (; cursor[0] < end; cursor[0]++) {
                HubBlueprint.BlockChange change = changes.get(cursor[0]);
                Block block = world.getBlockAt(change.x(), change.y(), change.z());
                block.setType(change.material(), false);
                // Leaves created by a blueprint are decorative and must not
                // decay before a player ever reaches the hub.
                if (Tag.LEAVES.isTagged(change.material()) && block.getBlockData() instanceof Leaves leaves) {
                    leaves.setPersistent(true);
                    block.setBlockData(leaves, false);
                }
            }
            int quarter = changes.isEmpty() ? 4 : cursor[0] * 4 / changes.size();
            if (quarter > reportedQuarter[0]) {
                reportedQuarter[0] = quarter;
                plugin.getLogger().info("Hub construction progress " + Math.min(100, quarter * 25) + "%");
            }
            if (cursor[0] < changes.size()) return;
            task.cancel();
            task = null;
            completion.accept(site);
        }, 1L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        preparing = false;
    }

    private CompletableFuture<List<org.bukkit.Chunk>> prepareChunks(HubSite site) {
        int minChunkX = (site.centerX() - site.radius() - 8) >> 4;
        int maxChunkX = (site.centerX() + site.radius() + 8) >> 4;
        int minChunkZ = (site.centerZ() - site.radius() - 8) >> 4;
        int maxChunkZ = (site.centerZ() + site.radius() + 8) >> 4;
        // The hub world is isolated and may legitimately be new during first
        // construction, so generation is allowed here. Paper's async API keeps
        // region I/O and generation off the initiating tick.
        return GeneratedChunkLoads.rectangle(
                site.world(), minChunkX, maxChunkX, minChunkZ, maxChunkZ, true);
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Disable cancels the construction placeholder/task.
        }
    }
}
