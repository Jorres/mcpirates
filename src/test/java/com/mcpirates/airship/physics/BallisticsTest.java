package com.mcpirates.airship.physics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BallisticsTest {

    private static final double V0_2_CHARGES = 2.0;

    @Test
    void zeroHorizontalDistanceReturnsNaN() {
        assertTrue(Double.isNaN(Ballistics.solvePitch(0.0, 0.0, V0_2_CHARGES)));
        assertTrue(Double.isNaN(Ballistics.solvePitch(-5.0, 0.0, V0_2_CHARGES)));
    }

    @Test
    void unreachableTargetReturnsNaN() {
        // 2-charge solid shot has max range ~65 blocks. 500 is well past that.
        assertTrue(Double.isNaN(Ballistics.solvePitch(500.0, 0.0, V0_2_CHARGES)));
    }

    @Test
    void closeLevelTargetGivesNearHorizontalPitch() {
        // 5 blocks away at the same height — a very small upward angle covers it.
        double pitch = Ballistics.solvePitch(5.0, 0.0, V0_2_CHARGES);
        assertFalse(Double.isNaN(pitch), "should be reachable");
        assertTrue(pitch < Math.toRadians(15.0),
                "5-block level shot wants < 15° elevation, got " + Math.toDegrees(pitch));
        assertTrue(pitch > 0, "level target above 0 b/tick gravity must lob slightly upward");
    }

    /** Solver picks the low arc — required pitch grows monotonically with range up to
     *  the limit where v² roughly matches g·dist. */
    @ParameterizedTest(name = "horiz {0}b → low-arc pitch should exceed previous range")
    @CsvSource({
            " 5,  10",
            "10,  20",
            "20,  30",
            "30,  40",
    })
    void requiredPitchGrowsWithDistance(double shorter, double longer) {
        double pitchShort = Ballistics.solvePitch(shorter, 0.0, V0_2_CHARGES);
        double pitchLong = Ballistics.solvePitch(longer, 0.0, V0_2_CHARGES);
        assertFalse(Double.isNaN(pitchShort));
        assertFalse(Double.isNaN(pitchLong));
        assertTrue(pitchLong > pitchShort,
                "pitch at " + longer + " (" + Math.toDegrees(pitchLong)
                        + "°) should exceed pitch at " + shorter + " ("
                        + Math.toDegrees(pitchShort) + "°)");
    }

    @Test
    void higherMuzzleVelocityNeedsLessElevation() {
        double pitchSlow = Ballistics.solvePitch(30.0, 0.0, 2.0);
        double pitchFast = Ballistics.solvePitch(30.0, 0.0, 3.0);
        assertFalse(Double.isNaN(pitchSlow));
        assertFalse(Double.isNaN(pitchFast));
        assertTrue(pitchFast < pitchSlow,
                "v=3 should need flatter pitch than v=2 for the same target");
    }

    @Test
    void targetAboveNeedsMoreElevationThanLevel() {
        double pitchLevel = Ballistics.solvePitch(20.0, 0.0, V0_2_CHARGES);
        double pitchHigh  = Ballistics.solvePitch(20.0, 10.0, V0_2_CHARGES);
        assertFalse(Double.isNaN(pitchLevel));
        assertFalse(Double.isNaN(pitchHigh));
        assertTrue(pitchHigh > pitchLevel,
                "a target 10b higher needs more elevation than a level target at same range");
    }

    /** Sanity: simulating the trajectory at the solved pitch lands within 1 block of the
     *  target. Catches algebra mistakes (sign flips, wrong frame) end-to-end. */
    @Test
    void solvedPitchActuallyHitsTarget() {
        double horiz = 25.0, dy = 3.0;
        double pitch = Ballistics.solvePitch(horiz, dy, V0_2_CHARGES);
        assertFalse(Double.isNaN(pitch));

        // Re-run the same forward simulation Ballistics uses internally.
        double x = 0, y = 0;
        double vx = V0_2_CHARGES * Math.cos(pitch);
        double vy = V0_2_CHARGES * Math.sin(pitch);
        double yAtHoriz = Double.NaN;
        for (int t = 0; t < 200; t++) {
            double prevX = x, prevY = y;
            double v = Math.hypot(vx, vy);
            if (v > 0) {
                double drag = Ballistics.DRAG * v;
                vx -= drag * (vx / v);
                vy -= drag * (vy / v);
            }
            vy -= Ballistics.GRAVITY;
            x += vx;
            y += vy;
            if (x >= horiz) {
                double s = (horiz - prevX) / (x - prevX);
                yAtHoriz = prevY + s * (y - prevY);
                break;
            }
        }
        assertFalse(Double.isNaN(yAtHoriz), "trajectory must reach horiz=25 within 200 ticks");
        assertEquals(dy, yAtHoriz, 1.0,
                "solved trajectory should land within 1 block of target Y");
    }

    @Test
    void physicsConstantsMatchCBCDefaults() {
        // If CBC bumps solid_shot.json gravity/drag, these need to retune. Pinning them
        // here makes that change a deliberate decision in two places, not one.
        assertEquals(0.05, Ballistics.GRAVITY, 0.0);
        assertEquals(0.01, Ballistics.DRAG, 0.0);
    }
}
