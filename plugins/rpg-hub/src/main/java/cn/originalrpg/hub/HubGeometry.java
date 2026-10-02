package cn.originalrpg.hub;

final class HubGeometry {
    private HubGeometry() {
    }

    static boolean inside(int centerX, int centerZ, int radius, int x, int z) {
        if (radius < 0) return false;
        long dx = (long) x - centerX;
        long dz = (long) z - centerZ;
        long absoluteX = Math.abs(dx);
        long absoluteZ = Math.abs(dz);
        long safeRadius = radius;
        if (absoluteX > safeRadius || absoluteZ > safeRadius) return false;
        // Subtract before adding the squared axes. This remains safe even for
        // coordinates at opposite int limits, where dx² + dz² would overflow.
        return dx * dx <= safeRadius * safeRadius - dz * dz;
    }

    static boolean onRing(int innerRadius, int outerRadius, int x, int z) {
        long distance = (long) x * x + (long) z * z;
        return distance >= (long) innerRadius * innerRadius
                && distance <= (long) outerRadius * outerRadius;
    }

    static boolean gateOpening(int radius, int x, int z) {
        int edge = Math.max(Math.abs(x), Math.abs(z));
        return edge >= radius - 3 && (Math.abs(x) <= 4 || Math.abs(z) <= 4);
    }
}
