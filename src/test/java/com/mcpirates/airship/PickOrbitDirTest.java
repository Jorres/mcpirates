package com.mcpirates.airship;

import org.junit.jupiter.api.Test;

import static com.mcpirates.airship.AirshipStateMachine.pickOrbitDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link AirshipStateMachine#pickOrbitDir}. Worldspace convention used here
 * matches {@code AirshipBrain.currentYawRadians}: world-forward is {@code (-sin yaw, cos yaw)},
 * so {@code yaw=0} ⇒ ship faces +Z. CCW (+1) tangent is the rot90 of (ship-target).
 */
class PickOrbitDirTest {

    /** Ship at +Z relative to target, facing +X (yaw=-π/2 in our convention: fwd=(1,0)).
     *  CCW tangent at this position is (-1, 0) → dot with fwd is -1, so CW (-1) wins. */
    @Test
    void picksCwWhenForwardOpposesCcwTangent() {
        assertEquals(-1, pickOrbitDir(0, 10, 0, 0, -Math.PI / 2));
    }

    /** Same geometry but ship faces -X (yaw=+π/2 → fwd=(-1,0)). CCW tangent (-1,0) aligns,
     *  so CCW (+1) wins. */
    @Test
    void picksCcwWhenForwardAlignsWithCcwTangent() {
        assertEquals(1, pickOrbitDir(0, 10, 0, 0, Math.PI / 2));
    }

    /** Ship coincident with target → degenerate r=0. Picker returns +1 deterministically. */
    @Test
    void returnsCcwOnDegenerateZeroSeparation() {
        assertEquals(1, pickOrbitDir(5, 5, 5, 5, 0));
    }

    /** Boundary: dot == 0 (tangent perpendicular to forward). {@code >= 0} branch chooses CCW.
     *  Ship at (10, 0), target at origin → ftx=10, ftz=0, CCW tangent = (0, 1). Forward at
     *  yaw=π is (0,-1) (perpendicular to tangent's perpendicular… actually (0,-1)·(0,1) = -1).
     *  Use yaw=π/2 → fwd=(-1,0), dot with (0,1) = 0 — the ties-go-CCW case. */
    @Test
    void zeroDotTieGoesToCcw() {
        assertEquals(1, pickOrbitDir(10, 0, 0, 0, Math.PI / 2));
    }

    /** Sanity: picking from far away (large r) yields the same result as nearby (sign-only). */
    @Test
    void scaleInvariant() {
        int near = pickOrbitDir(0, 10, 0, 0, 0);
        int far = pickOrbitDir(0, 10_000, 0, 0, 0);
        assertEquals(near, far);
    }
}
