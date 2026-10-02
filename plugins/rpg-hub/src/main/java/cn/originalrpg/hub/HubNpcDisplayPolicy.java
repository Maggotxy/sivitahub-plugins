package cn.originalrpg.hub;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Sanitised client-rendering limits for the fixed hub NPC labels.
 *
 * <p>Paper exposes {@code Display#setViewRange} as the vanilla display
 * multiplier, not as a number of blocks. Display entities use a 64 block base
 * tracking range in the target 26.2 server. Keeping the conversion here avoids
 * accidentally restoring the old {@code 20.0F} multiplier (roughly 1,280
 * blocks), which made every label in the hub render at once.</p>
 */
record HubNpcDisplayPolicy(
        double labelHeight,
        int lineWidthPixels,
        float scale,
        float viewRangeMultiplier,
        int reconcileTicks
) {
    static final float LABEL_DISPLAY_WIDTH = 4.0F;
    static final float SINGLE_LINE_DISPLAY_HEIGHT = 0.55F;
    static final double DEFAULT_VIEW_DISTANCE_BLOCKS = 20.0;
    static final double MIN_VIEW_DISTANCE_BLOCKS = 12.0;
    static final double MAX_VIEW_DISTANCE_BLOCKS = 24.0;
    static final float DISPLAY_BASE_TRACKING_RANGE_BLOCKS = 64.0F;

    static HubNpcDisplayPolicy load(ConfigurationSection root) {
        ConfigurationSection section = root.getConfigurationSection("npc-display");
        if (section == null) {
            return sanitise(2.72, 280, 0.72, DEFAULT_VIEW_DISTANCE_BLOCKS, 100);
        }
        return sanitise(
                section.getDouble("height-blocks", 2.72),
                section.getInt("line-width-pixels", 280),
                section.getDouble("scale", 0.72),
                section.getDouble("view-distance-blocks", DEFAULT_VIEW_DISTANCE_BLOCKS),
                section.getInt("reconcile-ticks", 100)
        );
    }

    static HubNpcDisplayPolicy sanitise(
            double labelHeight,
            int lineWidthPixels,
            double scale,
            double viewDistanceBlocks,
            int reconcileTicks
    ) {
        double safeHeight = Math.clamp(finiteOr(labelHeight, 2.72), 2.55, 3.10);
        int safeWidth = Math.clamp(lineWidthPixels, 180, 360);
        float safeScale = (float) Math.clamp(finiteOr(scale, 0.72), 0.60, 0.90);
        float safeRange = viewRangeMultiplier(viewDistanceBlocks);
        int safeReconcileTicks = Math.clamp(reconcileTicks, 40, 400);
        return new HubNpcDisplayPolicy(safeHeight, safeWidth, safeScale, safeRange, safeReconcileTicks);
    }

    static float viewRangeMultiplier(double requestedBlocks) {
        double blocks = Math.clamp(finiteOr(requestedBlocks, DEFAULT_VIEW_DISTANCE_BLOCKS),
                MIN_VIEW_DISTANCE_BLOCKS, MAX_VIEW_DISTANCE_BLOCKS);
        return (float) (blocks / DISPLAY_BASE_TRACKING_RANGE_BLOCKS);
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
