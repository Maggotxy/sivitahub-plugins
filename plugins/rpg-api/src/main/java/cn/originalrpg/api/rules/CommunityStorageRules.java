package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.MarketGoods;
import java.util.Set;

/**
 * Public construction supplies only. Equipment, rare materials and decorated/PDC-bearing
 * items have no community-storage route around the single taxed equipment market.
 * The Paper adapter must additionally verify that the actual stack has no item metadata.
 */
public final class CommunityStorageRules {
    private static final Set<String> MATERIALS = Set.of(
            "minecraft:stone", "minecraft:cobblestone", "minecraft:stone_bricks",
            "minecraft:andesite", "minecraft:polished_andesite", "minecraft:diorite",
            "minecraft:polished_diorite", "minecraft:granite", "minecraft:polished_granite",
            "minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:deepslate_bricks",
            "minecraft:deepslate_tiles", "minecraft:bricks", "minecraft:glass", "minecraft:terracotta",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:bamboo_planks"
    );

    private CommunityStorageRules() {}

    public static Set<String> materials() { return MATERIALS; }

    public static boolean accepts(MarketGoods goods) {
        return goods != null && goods.crafterId() == null && goods.quantity() >= 1 && goods.quantity() <= 64
                && MATERIALS.contains(goods.matcher());
    }
}
