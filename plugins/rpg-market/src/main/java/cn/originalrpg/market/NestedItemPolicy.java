package cn.originalrpg.market;

import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.List;
import java.util.function.Predicate;

/** Bounded inspection for items stored inside portable vanilla containers. */
final class NestedItemPolicy {
    private static final int MAX_DEPTH = 4;
    private static final int MAX_ITEMS = 256;

    private NestedItemPolicy() {
    }

    static boolean contains(ItemStack root, Predicate<ItemStack> directRestriction) {
        return contains(root, directRestriction, 0, new int[]{MAX_ITEMS});
    }

    static boolean isContainerWithContents(ItemStack item) {
        return !contents(item).isEmpty();
    }

    private static boolean contains(
            ItemStack item, Predicate<ItemStack> directRestriction, int depth, int[] remaining
    ) {
        if (item == null || item.getType().isAir()) return false;
        if (--remaining[0] < 0) return true;
        if (directRestriction.test(item)) return true;
        List<ItemStack> children = contents(item);
        if (children.isEmpty()) return false;
        if (depth >= MAX_DEPTH) return true;
        for (ItemStack child : children) {
            if (contains(child, directRestriction, depth + 1, remaining)) return true;
        }
        return false;
    }

    private static List<ItemStack> contents(ItemStack item) {
        if (item == null || item.getType().isAir()) return List.of();
        var meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) return liveItems(bundle.getItems());
        if (meta instanceof BlockStateMeta block && block.hasBlockState()
                && block.getBlockState() instanceof InventoryHolder holder) {
            return liveItems(java.util.Arrays.asList(holder.getInventory().getContents()));
        }
        return List.of();
    }

    private static List<ItemStack> liveItems(List<ItemStack> items) {
        return items.stream().filter(item -> item != null && !item.getType().isAir()).toList();
    }
}
