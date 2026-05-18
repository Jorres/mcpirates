package com.mcpirates.airship.physics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AngleMathTest {

    private static final double EPS = 1e-9;

    @ParameterizedTest(name = "normalizeRadians({0}π) ≈ {1}π")
    @CsvSource({
            "  0.0,  0.0",
            "  0.5,  0.5",
            "  1.0, -1.0",   // exact π lands on -π (half-period boundary)
            " -1.0, -1.0",
            "  1.5, -0.5",
            "  3.0, -1.0",   // 3π → -π
            " -3.0, -1.0",
            " 10.5,  0.5",   // 10.5π = 5×2π + 0.5π
    })
    void normalizeRadiansWrapsToHalfOpenRange(double inputMultiplier, double expectedMultiplier) {
        double got = AngleMath.normalizeRadians(inputMultiplier * Math.PI);
        assertEquals(expectedMultiplier * Math.PI, got, EPS);
    }

    @ParameterizedTest(name = "wrap180({0}) ≈ {1}")
    @CsvSource({
            "    0,    0",
            "   90,   90",
            "  179,  179",
            "  180, -180",   // exact +180 maps to -180
            " -180, -180",
            "  181, -179",
            "  360,    0",
            " -360,    0",
            "  540, -180",   // 540 = 360 + 180 → wraps to -180
            "-1080,    0",
    })
    void wrap180WrapsToHalfOpenRange(double input, double expected) {
        assertEquals(expected, AngleMath.wrap180(input), EPS);
    }

    @Test
    void clampYawNoopWhenWithinRange() {
        // Rest at 30°, clamp ±40° → [-10°, +70°]. 50° is inside.
        assertEquals(50.0f, AngleMath.clampYaw(50f, 30f, 40), EPS);
        assertEquals(30.0f, AngleMath.clampYaw(30f, 30f, 40), EPS);
    }

    @Test
    void clampYawClipsToEdge() {
        // Rest 30°, ±40°. Requesting 100° (delta +70) clamps to 70° (rest+40).
        assertEquals(70.0f, AngleMath.clampYaw(100f, 30f, 40), EPS);
        // Requesting -30° (delta -60) clamps to -10° (rest-40).
        assertEquals(-10.0f, AngleMath.clampYaw(-30f, 30f, 40), EPS);
    }

    @Test
    void clampYawHandlesWraparound() {
        // The trap: rest=170°, requested=-170°. Naively |-170 - 170| = 340 → clamp.
        // Correctly: delta wraps to +20°, which is INSIDE a ±40° clamp → return unchanged.
        assertEquals(-170.0f, AngleMath.clampYaw(-170f, 170f, 40), EPS);
        // Same flavour from the other side: rest=-170, requested=170 → delta -20, no clamp.
        assertEquals(170.0f, AngleMath.clampYaw(170f, -170f, 40), EPS);
        // And the actual clamp case across the wrap: rest=170, requested=80 → delta -90,
        // exceeds -40 clamp → result = 130 (rest - 40).
        assertEquals(130.0f, AngleMath.clampYaw(80f, 170f, 40), EPS);
    }

    @Test
    void clampYawDisabledReturnsInputUnchanged() {
        // <=0 is the documented "no clamp" signal — cannon free-tracks.
        assertEquals(999.0f, AngleMath.clampYaw(999f, 0f, 0), EPS);
        assertEquals(999.0f, AngleMath.clampYaw(999f, 0f, -5), EPS);
    }

    @Test
    void clampYawOutputAlwaysInHalfOpenDegRange() {
        // Property: clamp output is in [-180, 180) because the inner wrap180 calls
        // normalise both delta and final yaw. Sweep across rests and requests.
        for (float rest = -180; rest < 180; rest += 17) {
            for (float yaw = -540; yaw <= 540; yaw += 31) {
                float got = AngleMath.clampYaw(yaw, rest, 40);
                assertTrue(got >= -180 && got < 180,
                        "got " + got + " out of [-180, 180) for rest=" + rest + " yaw=" + yaw);
            }
        }
    }
}
