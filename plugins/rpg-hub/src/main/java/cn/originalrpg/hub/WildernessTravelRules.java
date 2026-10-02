package cn.originalrpg.hub;

/** Pure geometry rules for loading a random-travel neighborhood safely. */
final class WildernessTravelRules {
    private static final int CHUNK_WIDTH = 16;

    private WildernessTravelRules() {
    }

    static boolean neighborhoodInsideBorder(
            int chunkX,
            int chunkZ,
            int radius,
            double borderCenterX,
            double borderCenterZ,
            double borderDiameter
    ) {
        if (radius < 0 || !Double.isFinite(borderCenterX) || !Double.isFinite(borderCenterZ)
                || !Double.isFinite(borderDiameter) || borderDiameter <= 0.0) return false;
        double half = borderDiameter / 2.0;
        double borderMinimumX = borderCenterX - half;
        double borderMaximumX = borderCenterX + half;
        double borderMinimumZ = borderCenterZ - half;
        double borderMaximumZ = borderCenterZ + half;
        long minimumX = ((long) chunkX - radius) * CHUNK_WIDTH;
        long maximumXExclusive = ((long) chunkX + radius + 1L) * CHUNK_WIDTH;
        long minimumZ = ((long) chunkZ - radius) * CHUNK_WIDTH;
        long maximumZExclusive = ((long) chunkZ + radius + 1L) * CHUNK_WIDTH;
        return minimumX >= borderMinimumX && maximumXExclusive <= borderMaximumX
                && minimumZ >= borderMinimumZ && maximumZExclusive <= borderMaximumZ;
    }
}
