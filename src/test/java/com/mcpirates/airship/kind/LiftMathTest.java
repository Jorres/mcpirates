package com.mcpirates.airship.kind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiftMathTest {

    @Test
    void snapVolumeRoundsDownToStep() {
        assertEquals(60, LiftMath.snapVolume(64));
        assertEquals(60, LiftMath.snapVolume(60));
        assertEquals(500, LiftMath.snapVolume(500));
        assertEquals(495, LiftMath.snapVolume(499));
    }

    @Test
    void snapVolumeNeverGoesBelowMinFloor() {
        assertEquals(LiftMath.BURNER_MIN_VOLUME, LiftMath.snapVolume(0));
        assertEquals(LiftMath.BURNER_MIN_VOLUME, LiftMath.snapVolume(3));
        assertEquals(LiftMath.BURNER_MIN_VOLUME, LiftMath.snapVolume(-10));
    }
}
