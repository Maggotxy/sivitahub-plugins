package cn.originalrpg.hub;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class HubBlueprint {
    record BlockChange(int x, int y, int z, Material material) {
    }

    private HubBlueprint() {
    }

    static int selectBaseY(World world, int centerX, int centerZ) {
        int[] heights = new int[25];
        int index = 0;
        for (int x = -32; x <= 32; x += 16) {
            for (int z = -32; z <= 32; z += 16) {
                heights[index++] = world.getHighestBlockYAt(
                        centerX + x, centerZ + z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            }
        }
        Arrays.sort(heights);
        int median = heights[heights.length / 2];
        return Math.clamp(Math.max(world.getSeaLevel() + 1, median), world.getMinHeight() + 8, world.getMaxHeight() - 24);
    }

    static List<BlockChange> create(HubSite site) {
        ArrayList<BlockChange> changes = new ArrayList<>(180_000);
        terrain(changes, site);
        wayfindingRoutes(changes, site);
        arrivalAndOnboardingCourt(changes, site);
        wallsAndGates(changes, site);
        centralTree(changes, site);
        for (HubRole role : HubRole.values()) {
            if (role != HubRole.GUIDE && role != HubRole.QUESTS && role != HubRole.AI) pavilion(changes, site, role);
        }
        pavilionLandmarks(changes, site);
        lamps(changes, site);
        return changes;
    }

    /**
     * One persistent colour line per specialist. These routes are readable
     * without a resource pack and have no entity, scheduler, or packet cost
     * after the hub is built. Short crossbars make diagonal routes easier to
     * follow on Minecraft's block grid without turning the plaza into a solid
     * rainbow floor.
     */
    private static void wayfindingRoutes(List<BlockChange> changes, HubSite site) {
        for (HubRole role : HubRole.values()) {
            if (role == HubRole.GUIDE || role == HubRole.QUESTS || role == HubRole.AI) continue;
            double length = Math.hypot(role.offsetX(), role.offsetZ());
            double directionX = role.offsetX() / length;
            double directionZ = role.offsetZ() / length;
            int startX = (int) Math.round(directionX * 10.0);
            int startZ = (int) Math.round(directionZ * 10.0);
            int endX = (int) Math.round(role.offsetX() - directionX * 7.0);
            int endZ = (int) Math.round(role.offsetZ() - directionZ * 7.0);
            int steps = Math.max(Math.abs(endX - startX), Math.abs(endZ - startZ));
            Material route = routeMaterial(role);
            int previousX = Integer.MIN_VALUE;
            int previousZ = Integer.MIN_VALUE;
            for (int index = 0; index <= steps; index++) {
                double progress = steps == 0 ? 1.0 : (double) index / steps;
                int x = (int) Math.round(startX + (endX - startX) * progress);
                int z = (int) Math.round(startZ + (endZ - startZ) * progress);
                if (x == previousX && z == previousZ) continue;
                set(changes, site, x, 0, z, route);
                // A sparse perpendicular crossbar acts as a breadcrumb and
                // removes ambiguity where a diagonal line stair-steps.
                if (index > 0 && index < steps && index % 6 == 0) {
                    int perpendicularX = (int) Math.round(-directionZ);
                    int perpendicularZ = (int) Math.round(directionX);
                    set(changes, site, x + perpendicularX, 0, z + perpendicularZ, route);
                    set(changes, site, x - perpendicularX, 0, z - perpendicularZ, route);
                }
                previousX = x;
                previousZ = z;
            }
        }
    }

    /** Distinct roof crests keep every POI visible before its NPC label enters tracking range. */
    private static void pavilionLandmarks(List<BlockChange> changes, HubSite site) {
        for (HubRole role : HubRole.values()) {
            if (role == HubRole.GUIDE || role == HubRole.QUESTS || role == HubRole.AI) continue;
            Material route = routeMaterial(role);
            // Five floor pixels repeat the route colour underneath the NPC.
            set(changes, site, role.offsetX(), 0, role.offsetZ(), route);
            set(changes, site, role.offsetX() - 1, 0, role.offsetZ(), route);
            set(changes, site, role.offsetX() + 1, 0, role.offsetZ(), route);
            set(changes, site, role.offsetX(), 0, role.offsetZ() - 1, route);
            set(changes, site, role.offsetX(), 0, role.offsetZ() + 1, route);
            // A small illuminated crest is block-only and visible from the
            // central ring without adding holograms, armor stands, or banners.
            set(changes, site, role.offsetX(), 7, role.offsetZ(), route);
            set(changes, site, role.offsetX(), 8, role.offsetZ(), route);
            set(changes, site, role.offsetX(), 9, role.offsetZ(), Material.SEA_LANTERN);
        }
    }

    static Material routeMaterial(HubRole role) {
        return switch (role) {
            case CLASS -> Material.PURPLE_CONCRETE;
            case SKILLS -> Material.BLUE_CONCRETE;
            case FORGE -> Material.ORANGE_CONCRETE;
            case ENHANCEMENT -> Material.CYAN_CONCRETE;
            case DUNGEON -> Material.RED_CONCRETE;
            case MARKET -> Material.LIME_CONCRETE;
            case ECONOMY -> Material.YELLOW_CONCRETE;
            case WILDERNESS -> Material.GREEN_CONCRETE;
            case GUIDE -> Material.LIGHT_BLUE_CONCRETE;
            case QUESTS -> Material.MOSS_BLOCK;
            case AI -> Material.LIGHT_BLUE_CONCRETE;
        };
    }

    /**
     * A readable first thirty seconds: arrive on a distinct platform, follow
     * one illuminated lane, and meet the guide/quest/listener trio before the
     * road branches into specialist districts. Everything is block based, so
     * it adds no ticking entities or per-player particle traffic.
     */
    private static void arrivalAndOnboardingCourt(List<BlockChange> changes, HubSite site) {
        for (int x = -7; x <= 7; x++) {
            for (int z = 21; z <= 29; z++) {
                boolean border = Math.abs(x) == 7 || z == 21 || z == 29;
                Material floor = border ? Material.TUFF_BRICKS
                        : Math.floorMod(x * 17 + z * 11, 13) == 0
                        ? Material.EXPOSED_CUT_COPPER : Material.SMOOTH_STONE;
                set(changes, site, x, 0, z, floor);
            }
        }
        // One unambiguous lane from spawn to the guide. Recessed lights avoid
        // collision and remain readable with particles disabled.
        for (int z = 12; z <= 25; z++) {
            set(changes, site, -2, 0, z, Material.POLISHED_TUFF);
            set(changes, site, 2, 0, z, Material.POLISHED_TUFF);
            set(changes, site, -1, 0, z, Material.SMOOTH_STONE);
            set(changes, site, 1, 0, z, Material.SMOOTH_STONE);
            set(changes, site, 0, 0, z,
                    Math.floorMod(z - 12, 4) == 0 ? Material.SEA_LANTERN : Material.POLISHED_ANDESITE);
        }
        for (int x : new int[]{-7, 7}) {
            for (int y = 1; y <= 3; y++) set(changes, site, x, y, 25, Material.TUFF_BRICK_WALL);
            set(changes, site, x, 4, 25, Material.LANTERN);
        }

        // Individual pads keep the three introductory NPCs visually separate
        // without adding more floating text layers.
        npcPad(changes, site, 0, 12, Material.LODESTONE);
        npcPad(changes, site, -6, 14, Material.MOSS_BLOCK);
        npcPad(changes, site, 6, 14, Material.OXIDIZED_CUT_COPPER);
    }

    private static void npcPad(
            List<BlockChange> changes, HubSite site, int centerX, int centerZ, Material accent
    ) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                boolean edge = Math.abs(x) == 2 || Math.abs(z) == 2;
                set(changes, site, centerX + x, 0, centerZ + z,
                        edge ? Material.TUFF_BRICKS : Material.SMOOTH_STONE);
            }
        }
        set(changes, site, centerX, 0, centerZ, accent);
    }

    private static void terrain(List<BlockChange> changes, HubSite site) {
        int radius = site.radius();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!HubGeometry.inside(0, 0, radius, x, z)) continue;
                for (int y = 1; y <= 18; y++) set(changes, site, x, y, z, Material.AIR);
                set(changes, site, x, -3, z, Material.STONE);
                set(changes, site, x, -2, z, Material.DIRT);
                set(changes, site, x, -1, z, Material.DIRT);
                set(changes, site, x, 0, z, floor(x, z));
            }
        }
    }

    private static Material floor(int x, int z) {
        long distance = (long) x * x + (long) z * z;
        if (distance <= 11L * 11L) {
            return Math.floorMod(x * 31 + z * 17, 7) == 0 ? Material.TUFF_BRICKS : Material.POLISHED_ANDESITE;
        }
        boolean road = Math.abs(x) <= 3 || Math.abs(z) <= 3 || HubGeometry.onRing(30, 34, x, z);
        if (road) return Math.floorMod(x * 13 + z * 29, 9) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
        return Math.floorMod(x * 19 + z * 23, 17) == 0 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
    }

    private static void wallsAndGates(List<BlockChange> changes, HubSite site) {
        int radius = site.radius();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!HubGeometry.onRing(radius - 2, radius, x, z) || HubGeometry.gateOpening(radius, x, z)) continue;
                set(changes, site, x, 1, z, Material.STONE_BRICKS);
                if (Math.floorMod(x * 7 + z * 11, 5) == 0) {
                    set(changes, site, x, 2, z, Material.SPRUCE_FENCE);
                }
            }
        }
        gate(changes, site, 0, -radius, true);
        gate(changes, site, 0, radius, true);
        gate(changes, site, -radius, 0, false);
        gate(changes, site, radius, 0, false);
    }

    private static void gate(List<BlockChange> changes, HubSite site, int x, int z, boolean northSouth) {
        for (int side : new int[]{-5, 5}) {
            for (int y = 1; y <= 6; y++) {
                set(changes, site, x + (northSouth ? side : 0), y, z + (northSouth ? 0 : side),
                        y <= 2 ? Material.STONE_BRICKS : Material.STRIPPED_SPRUCE_LOG);
            }
        }
        for (int offset = -5; offset <= 5; offset++) {
            set(changes, site, x + (northSouth ? offset : 0), 6, z + (northSouth ? 0 : offset), Material.DARK_OAK_PLANKS);
            if (Math.abs(offset) <= 3) {
                set(changes, site, x + (northSouth ? offset : 0), 7, z + (northSouth ? 0 : offset), Material.SPRUCE_SLAB);
            }
        }
        set(changes, site, x + (northSouth ? -5 : 0), 5, z + (northSouth ? 0 : -5), Material.LANTERN);
        set(changes, site, x + (northSouth ? 5 : 0), 5, z + (northSouth ? 0 : 5), Material.LANTERN);
    }

    private static void centralTree(List<BlockChange> changes, HubSite site) {
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                if (HubGeometry.inside(0, 0, 9, x, z)) {
                    set(changes, site, x, 1, z,
                            Math.floorMod(x * 11 + z * 5, 8) == 0 ? Material.EXPOSED_CUT_COPPER : Material.SMOOTH_STONE);
                }
            }
        }
        for (int y = 2; y <= 11; y++) {
            set(changes, site, 0, y, 0, Material.STRIPPED_DARK_OAK_LOG);
            if (y <= 4) {
                set(changes, site, 1, y, 0, Material.STRIPPED_DARK_OAK_LOG);
                set(changes, site, 0, y, 1, Material.STRIPPED_DARK_OAK_LOG);
            }
        }
        for (int direction : new int[]{-1, 1}) {
            for (int offset = 1; offset <= 4; offset++) {
                set(changes, site, direction * offset, 9 + offset / 3, 0, Material.DARK_OAK_LOG);
                set(changes, site, 0, 8 + offset / 3, direction * offset, Material.DARK_OAK_LOG);
            }
        }
        for (int y = 8; y <= 14; y++) {
            int layerRadius = y == 8 || y == 14 ? 3 : (y == 9 || y == 13 ? 5 : 6);
            for (int x = -layerRadius; x <= layerRadius; x++) {
                for (int z = -layerRadius; z <= layerRadius; z++) {
                    if (!HubGeometry.inside(0, 0, layerRadius, x, z) || (Math.abs(x) <= 1 && Math.abs(z) <= 1)) continue;
                    Material leaves = Math.floorMod(x * 23 + y * 17 + z * 31, 11) == 0
                            ? Material.FLOWERING_AZALEA_LEAVES : Material.AZALEA_LEAVES;
                    set(changes, site, x, y, z, leaves);
                }
            }
        }
        set(changes, site, 0, 2, 0, Material.LODESTONE);
        set(changes, site, 2, 2, 0, Material.ANVIL);
        set(changes, site, -2, 2, 0, Material.ENCHANTING_TABLE);
        set(changes, site, 0, 2, 2, Material.SMITHING_TABLE);
        set(changes, site, 0, 2, -2, Material.CARTOGRAPHY_TABLE);
        for (int[] point : new int[][]{{4, 11, 0}, {-4, 11, 0}, {0, 10, 4}, {0, 10, -4}}) {
            set(changes, site, point[0], point[1], point[2], Material.SHROOMLIGHT);
        }
    }

    private static void pavilion(List<BlockChange> changes, HubSite site, HubRole role) {
        int centerX = role.offsetX();
        int centerZ = role.offsetZ();
        Material accent = accent(role);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                boolean edge = Math.abs(x) == 5 || Math.abs(z) == 5;
                Material floor = edge && Math.floorMod(x + z, 2) == 0
                        ? accent
                        : Math.floorMod(x * 3 + z * 5, 7) == 0
                        ? Material.POLISHED_DIORITE : Material.SMOOTH_STONE;
                set(changes, site, centerX + x, 0, centerZ + z, floor);
            }
        }
        for (int x : new int[]{-5, 5}) {
            for (int z : new int[]{-5, 5}) {
                for (int y = 1; y <= 5; y++) {
                    set(changes, site, centerX + x, y, centerZ + z, Material.STRIPPED_SPRUCE_LOG);
                }
            }
        }
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                Material roof = Math.abs(x) == 6 || Math.abs(z) == 6 ? Material.DARK_OAK_SLAB : Material.SPRUCE_SLAB;
                set(changes, site, centerX + x, 6, centerZ + z, roof);
            }
        }
        set(changes, site, centerX, 6, centerZ, accent);
        set(changes, site, centerX - 1, 1, centerZ, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        set(changes, site, centerX + 1, 1, centerZ, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        set(changes, site, centerX - 1, 2, centerZ, accent);
        set(changes, site, centerX + 1, 2, centerZ, accent);
        set(changes, site, centerX - 4, 4, centerZ - 4, Material.LANTERN);
        set(changes, site, centerX + 4, 4, centerZ + 4, Material.LANTERN);
    }

    private static Material accent(HubRole role) {
        return switch (role) {
            case CLASS -> Material.PURPLE_GLAZED_TERRACOTTA;
            case SKILLS -> Material.AMETHYST_BLOCK;
            case FORGE -> Material.COPPER_BLOCK;
            case ENHANCEMENT -> Material.PRISMARINE_BRICKS;
            case DUNGEON -> Material.REINFORCED_DEEPSLATE;
            case MARKET -> Material.EMERALD_BLOCK;
            case ECONOMY -> Material.GOLD_BLOCK;
            case WILDERNESS -> Material.MOSSY_COBBLESTONE;
            case GUIDE, QUESTS, AI -> Material.LODESTONE;
        };
    }

    private static void lamps(List<BlockChange> changes, HubSite site) {
        for (int index = 0; index < 16; index++) {
            double angle = Math.PI * 2.0 * index / 16.0;
            int x = (int) Math.round(Math.cos(angle) * 36.0);
            int z = (int) Math.round(Math.sin(angle) * 36.0);
            for (int y = 1; y <= 4; y++) set(changes, site, x, y, z, Material.DARK_OAK_FENCE);
            set(changes, site, x, 5, z, Material.LANTERN);
        }
    }

    private static void set(List<BlockChange> changes, HubSite site, int offsetX, int offsetY, int offsetZ, Material material) {
        changes.add(new BlockChange(site.centerX() + offsetX, site.baseY() + offsetY,
                site.centerZ() + offsetZ, material));
    }
}
