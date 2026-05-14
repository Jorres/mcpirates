package com.mcpirates.airship.kind;

import com.mcpirates.airship.kind.LiftMath.LiftSetting;
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

    @Test
    void maxLiftIsLever15AtSnappedCeiling() {
        assertEquals(new LiftSetting(15, 60), LiftMath.maxLift(64));
        assertEquals(new LiftSetting(15, 500), LiftMath.maxLift(500));
        assertEquals(new LiftSetting(15, 5), LiftMath.maxLift(3));
    }
}
