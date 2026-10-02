package cn.originalrpg.economy;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists player-placed resonant ore coordinates in chunk PDC.
 *
 * <p>Vanilla does not retain block provenance: a placed diamond ore can still
 * drop experience when mined without Silk Touch. Keeping the marker on the
 * chunk makes that distinction survive restarts without a database lookup on
 * every block break.</p>
 */
final class PlayerPlacedOreTracker {
    private final NamespacedKey placedOres;

    PlayerPlacedOreTracker(Plugin plugin) {
        placedOres = new NamespacedKey(plugin, "player_placed_resonant_ores");
    }

    void mark(Block block) {
        Set<Integer> coordinates = read(block.getChunk());
        if (coordinates.add(pack(block))) write(block.getChunk(), coordinates);
    }

    boolean remove(Block block) {
        Set<Integer> coordinates = read(block.getChunk());
        boolean removed = coordinates.remove(pack(block));
        if (removed) write(block.getChunk(), coordinates);
        return removed;
    }

    void removeAll(Collection<Block> blocks) {
        Map<Chunk, Set<Integer>> changed = new HashMap<>();
        for (Block block : blocks) {
            if (!VanillaIntegrationRules.isResonantOre(block.getType().name())) continue;
            Set<Integer> coordinates = changed.computeIfAbsent(block.getChunk(), this::read);
            coordinates.remove(pack(block));
        }
        changed.forEach(this::write);
    }

    void move(List<Block> blocks, BlockFace movement) {
        Map<Chunk, Set<Integer>> changed = new HashMap<>();
        List<Block> markedSources = new ArrayList<>();

        // Inspect the original state before changing any set. Adjacent blocks
        // may move into one another's old coordinates during the same piston event.
        for (Block source : blocks) {
            if (!VanillaIntegrationRules.isResonantOre(source.getType().name())) continue;
            Set<Integer> coordinates = changed.computeIfAbsent(source.getChunk(), this::read);
            if (coordinates.contains(pack(source))) markedSources.add(source);
        }
        if (markedSources.isEmpty()) return;

        for (Block source : markedSources) {
            changed.get(source.getChunk()).remove(pack(source));
        }
        for (Block source : markedSources) {
            Block destination = source.getRelative(movement);
            changed.computeIfAbsent(destination.getChunk(), this::read).add(pack(destination));
        }
        changed.forEach(this::write);
    }

    private Set<Integer> read(Chunk chunk) {
        int[] stored = chunk.getPersistentDataContainer().get(placedOres, PersistentDataType.INTEGER_ARRAY);
        Set<Integer> result = new HashSet<>();
        if (stored != null) for (int coordinate : stored) result.add(coordinate);
        return result;
    }

    private void write(Chunk chunk, Set<Integer> coordinates) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (coordinates.isEmpty()) {
            data.remove(placedOres);
            return;
        }
        data.set(placedOres, PersistentDataType.INTEGER_ARRAY,
                coordinates.stream().mapToInt(Integer::intValue).sorted().toArray());
    }

    static int pack(Block block) {
        return block.getY() << 8 | (block.getX() & 15) << 4 | block.getZ() & 15;
    }
}
