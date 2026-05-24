package com.mcpirates.airship.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for the slab-method intersection used as a pre-filter before the
 * ship-hull raycast in {@link com.mcpirates.pirates.roles.CrossbowmanRole}.
 *
 * <p>Fixed 10×10×10 box at (0..10, 0..10, 0..10) unless a test names otherwise. Semantics
 * checked: inclusivity on faces, parallel-to-axis special case, t∈[0,1] confinement
 * (extended-line hits beyond the segment must NOT count), and degenerate (point) segments.
 */
class SegmentAABBTest {

    private static final double LO = 0, HI = 10;

    private static boolean hits(double ax, double ay, double az,
                                double bx, double by, double bz) {
        return SegmentAABB.intersects(ax, ay, az, bx, by, bz, LO, LO, LO, HI, HI, HI);
    }

    @Test
    void segmentFullyInsideHits() {
        assertTrue(hits(2, 2, 2, 5, 5, 5));
    }

    @Test
    void segmentFullyOutsideMisses() {
        assertFalse(hits(20, 20, 20, 25, 25, 25));
    }

    @Test
    void segmentEntersAndExitsHits() {
        // Diagonal that pierces the box from below-left to above-right.
        assertTrue(hits(-5, -5, -5, 15, 15, 15));
    }

    @Test
    void segmentWithOneEndpointInsideHits() {
        assertTrue(hits(5, 5, 5, 20, 5, 5));
    }

    @Test
    void segmentExtendedLineHitsButSegmentTooShortMisses() {
        // Line from (-5,5,5) to (-1,5,5) — if extended, would enter the box at x=0.
        // But the segment stops at x=-1, so it never enters.
        assertFalse(hits(-5, 5, 5, -1, 5, 5));
    }

    @Test
    void segmentParallelToAxisInsideSlabHits() {
        // Constant y=5, z=5, varies x from -5 to 15 — pierces the box on X.
        assertTrue(hits(-5, 5, 5, 15, 5, 5));
    }

    @Test
    void segmentParallelToAxisOutsideSlabMisses() {
        // Constant y=20 (outside [0,10]), so the X-pierce doesn't matter.
        assertFalse(hits(-5, 20, 5, 15, 20, 5));
    }

    @Test
    void segmentTouchingFaceHits() {
        // Endpoint sits exactly on the +X face. Inclusive semantics.
        assertTrue(hits(10, 5, 5, 15, 5, 5));
    }

    @Test
    void segmentGrazingCornerHits() {
        // Both endpoints outside, line passes through the (10,10,10) corner.
        assertTrue(hits(11, 9, 10, 9, 11, 10));
    }

    @Test
    void segmentPassingNearMissesCleanly() {
        // Parallel to X axis at y=11 — entire segment lies above the box.
        assertFalse(hits(-5, 11, 5, 15, 11, 5));
    }

    @Test
    void degenerateSegmentInsideHits() {
        assertTrue(hits(5, 5, 5, 5, 5, 5));
    }

    @Test
    void degenerateSegmentOutsideMisses() {
        assertFalse(hits(20, 20, 20, 20, 20, 20));
    }

    @Test
    void degenerateSegmentOnFaceHits() {
        // Single point exactly on +X face.
        assertTrue(hits(10, 5, 5, 10, 5, 5));
    }

    @Test
    void reversedEndpointsStillHits() {
        // Direction-agnostic: swapping endpoints must yield the same result.
        boolean forward = hits(-5, 5, 5, 15, 5, 5);
        boolean reverse = hits(15, 5, 5, -5, 5, 5);
        assertTrue(forward);
        assertTrue(reverse);
    }

    @Test
    void shipHullGrazeRealistic() {
        // Realistic-ish: pirate ship bbox 7×13×10, crossbowman muzzle at (3.5, 6.5, 5.5),
        // shoots toward a target at (25, 21, 6). Line clearly exits the bbox upward and
        // forward — pre-filter must say "yes, raycast it".
        boolean h = SegmentAABB.intersects(
                3.5, 6.5, 5.5, 25.0, 21.0, 6.0,
                0, 0, 0, 7, 13, 10);
        assertTrue(h, "muzzle is inside the ship, ANY exiting line must intersect");
    }

    @Test
    void shipPortVsStarboardLineMustHit() {
        // Bbox 10 wide on X. Shooter on -X side at x=-2 firing across the ship to x=12.
        // Must intersect — line passes through the whole hull.
        assertTrue(SegmentAABB.intersects(
                -2, 5, 5, 12, 5, 5,
                0, 0, 0, 10, 10, 10));
    }

    @Test
    void shipOutsideAndAboveMisses() {
        // Shooter above the ship at y=20, target at y=18, both well above bbox top (y=10).
        assertFalse(SegmentAABB.intersects(
                -5, 20, 5, 15, 18, 5,
                0, 0, 0, 10, 10, 10));
    }
}
