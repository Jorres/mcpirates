package com.mcpirates.airship.physics;

/**
 * 3D line-segment vs axis-aligned-bounding-box intersection (slab method).
 *
 * <p>Pure math — no Minecraft / Sable imports, unit-testable in isolation. Used as a
 * cheap pre-filter before the expensive {@code Level.clip} raycast in
 * {@link com.mcpirates.pirates.roles.CrossbowmanRole}: if the segment doesn't even
 * cross the ship's world bbox, no need to transform endpoints and walk blocks.
 */
public final class SegmentAABB {

    private SegmentAABB() {}

    /** True if any portion of segment {@code [a, b]} lies inside (or on) the AABB.
     *  Inclusive on faces: a segment endpoint exactly on a face counts as intersecting. */
    public static boolean intersects(
            double ax, double ay, double az,
            double bx, double by, double bz,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        double tEnter = 0.0, tExit = 1.0;
        // X
        double dx = bx - ax;
        if (Math.abs(dx) < 1e-9) {
            if (ax < minX || ax > maxX) return false;
        } else {
            double t1 = (minX - ax) / dx;
            double t2 = (maxX - ax) / dx;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tEnter) tEnter = t1;
            if (t2 < tExit) tExit = t2;
            if (tEnter > tExit) return false;
        }
        // Y
        double dy = by - ay;
        if (Math.abs(dy) < 1e-9) {
            if (ay < minY || ay > maxY) return false;
        } else {
            double t1 = (minY - ay) / dy;
            double t2 = (maxY - ay) / dy;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tEnter) tEnter = t1;
            if (t2 < tExit) tExit = t2;
            if (tEnter > tExit) return false;
        }
        // Z
        double dz = bz - az;
        if (Math.abs(dz) < 1e-9) {
            if (az < minZ || az > maxZ) return false;
        } else {
            double t1 = (minZ - az) / dz;
            double t2 = (maxZ - az) / dz;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tEnter) tEnter = t1;
            if (t2 < tExit) tExit = t2;
            if (tEnter > tExit) return false;
        }
        return true;
    }
}
