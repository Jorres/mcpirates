package com.mcpirates.airship;

import com.mcpirates.airship.AirshipBrain.State;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static com.mcpirates.airship.AirshipStateMachine.LIFTOFF_MIN_RISE;
import static com.mcpirates.airship.AirshipStateMachine.LIFTOFF_MIN_TICKS;
import static com.mcpirates.airship.AirshipStateMachine.LIFTOFF_STEADY_TICKS;
import static com.mcpirates.airship.AirshipStateMachine.LOST_TARGET_DEBOUNCE;
import static com.mcpirates.airship.AirshipStateMachine.decideNextState;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Branch coverage for {@link AirshipStateMachine#decideNextState}. The function is pure,
 * so every transition can be exercised by varying one input — no Airship instance, no
 * SubLevel, no GameTest arena needed. Integration concerns (the brain *acting* on a state
 * — clutch lever writes, cannon aim, SubLevel pose reads) live in AirshipGameTests.
 *
 * <p>Boundary tests (e.g. {@code steadyTicks == LIFTOFF_STEADY_TICKS}) pin the {@code >=}
 * vs {@code >} discipline so a future refactor that swapped them would fail loudly.
 */
class AirshipBrainStateMachineTest {

    // Ship sits directly above the airpad at altitude 200; airpad at (0.5, 0.5). Horizontal
    // distance² ≈ 0.5 < HOVER_RADIUS_SQ (256), so atAirpad=true unless a test moves the ship.
    private static final Vector3d SHIP_AT_AIRPAD = new Vector3d(0, 200, 0);
    private static final Vector3d SHIP_FAR_FROM_AIRPAD = new Vector3d(0, 200, 1000);
    private static final double AIRPAD_X = 0.5;
    private static final double AIRPAD_Z = 0.5;
    /** liftoffStartY chosen so SHIP_AT_AIRPAD.y - this == 100 ≥ LIFTOFF_MIN_RISE (25). */
    private static final double LIFTOFF_START_Y_RISEN = 100;
    private static final long NOW = 10_000;
    /** stateEnteredTick well before NOW so ticksInState ≥ LIFTOFF_MIN_TICKS by a wide margin. */
    private static final long ENTERED_LONG_AGO = NOW - LIFTOFF_MIN_TICKS - 100;

    // ────────────────────────────── LIFTOFF ──────────────────────────────

    @Test
    void liftoff_staysWhenShipHasNotRisen() {
        // Even with steadyTicks and ticksInState satisfied, the rise gate alone holds LIFTOFF.
        // This is the regression that used to ship as "ship lifts off, propellers engage at
        // ground level, plows into trees" — the very bug this gate was added to close.
        assertEquals(State.LIFTOFF, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO,
                /*steadyTicks=*/ LIFTOFF_STEADY_TICKS + 100,
                /*liftoffStartY=*/ SHIP_AT_AIRPAD.y,  // shipY - this = 0 < MIN_RISE
                NOW, SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_staysWhenLiftoffStartYIsNaN() {
        // Before tickShip captures the first LIFTOFF Y, liftoffStartY = NaN. The rise gate
        // must short-circuit false (not NaN > MIN_RISE = false anyway, but explicit).
        assertEquals(State.LIFTOFF, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO, LIFTOFF_STEADY_TICKS + 100,
                /*liftoffStartY=*/ Double.NaN,
                NOW, SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_exitsViaRiseEvenWithoutSteady() {
        // The rise path is independent of steadyTicks: a ship that climbs past
        // LIFTOFF_MIN_RISE exits immediately (once min-ticks elapsed) regardless of
        // whether its altitude has settled. Other path (stabilized) is for ships
        // whose pressure ceiling caps them below minRise.
        assertEquals(State.RETURN, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO,
                /*steadyTicks=*/ 0,
                LIFTOFF_START_Y_RISEN, NOW, SHIP_AT_AIRPAD,
                AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_exitsViaStabilizedBelowMinRise() {
        // Low-lift ship: rises only a few blocks (above LIFTOFF_STEADY_MIN_RISE) then
        // hits its pressure ceiling. After STEADY_TICKS of unchanged altitude it
        // exits LIFTOFF even though it never reached LIFTOFF_MIN_RISE.
        Vector3d shipJustAboveSteadyFloor = new Vector3d(0, 100 + 10, 0);  // rise=10
        assertEquals(State.RETURN, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO,
                /*steadyTicks=*/ LIFTOFF_STEADY_TICKS,
                /*liftoffStartY=*/ 100, NOW, shipJustAboveSteadyFloor,
                AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_staysWhenStableButBelowSteadyFloor() {
        // Parked at anchor — never moved. Rise < LIFTOFF_STEADY_MIN_RISE so the
        // "stabilized" path is gated off, preventing the propellers-at-ground bug.
        Vector3d shipBarelyMoved = new Vector3d(0, 100 + 1, 0);  // rise=1, below floor
        assertEquals(State.LIFTOFF, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO,
                /*steadyTicks=*/ LIFTOFF_STEADY_TICKS + 100,
                /*liftoffStartY=*/ 100, NOW, shipBarelyMoved,
                AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_staysWhenTicksInStateBelowFloor() {
        assertEquals(State.LIFTOFF, decideNextState(
                State.LIFTOFF,
                /*stateEnteredTick=*/ NOW - LIFTOFF_MIN_TICKS + 1,  // ticksInState = MIN-1
                LIFTOFF_STEADY_TICKS + 100, LIFTOFF_START_Y_RISEN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_atExactSteadyTicksThresholdCounts() {
        // Boundary: steadyTicks == LIFTOFF_STEADY_TICKS satisfies the >= check.
        assertEquals(State.RETURN, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO,
                /*steadyTicks=*/ LIFTOFF_STEADY_TICKS,
                LIFTOFF_START_Y_RISEN, NOW, SHIP_AT_AIRPAD,
                AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_atExactRiseThresholdCounts() {
        // Boundary: shipY - liftoffStartY == LIFTOFF_MIN_RISE satisfies >=.
        Vector3d shipAtExactRise = new Vector3d(0, 100 + LIFTOFF_MIN_RISE, 0);
        assertEquals(State.RETURN, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO, LIFTOFF_STEADY_TICKS,
                /*liftoffStartY=*/ 100, NOW, shipAtExactRise,
                AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_transitionsToReturnWhenDoneAndNoTarget() {
        assertEquals(State.RETURN, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO, LIFTOFF_STEADY_TICKS,
                LIFTOFF_START_Y_RISEN, NOW, SHIP_AT_AIRPAD,
                AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void liftoff_transitionsToPursueWhenDoneAndTargetInRange() {
        assertEquals(State.PURSUE, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO, LIFTOFF_STEADY_TICKS,
                LIFTOFF_START_Y_RISEN, NOW, SHIP_AT_AIRPAD,
                AIRPAD_X, AIRPAD_Z,
                /*targetPos=*/ new Vector3d(SHIP_AT_AIRPAD.x + 12, 0, SHIP_AT_AIRPAD.z),
                NOW));
    }

    @Test
    void liftoff_transitionsToReturnWhenTargetTooFar() {
        // Target 200 blocks away > DISENGAGE_RANGE (128).
        assertEquals(State.RETURN, decideNextState(
                State.LIFTOFF, ENTERED_LONG_AGO, LIFTOFF_STEADY_TICKS,
                LIFTOFF_START_Y_RISEN, NOW, SHIP_AT_AIRPAD,
                AIRPAD_X, AIRPAD_Z,
                new Vector3d(SHIP_AT_AIRPAD.x + 200, 0, SHIP_AT_AIRPAD.z),
                NOW));
    }

    // ────────────────────────────── PURSUE ──────────────────────────────

    @Test
    void pursue_disengagesWhenTargetLost() {
        assertEquals(State.RETURN, decideNextState(
                State.PURSUE, ENTERED_LONG_AGO, 0, Double.NaN,
                /*lastTargetSeenTick=*/ NOW - LOST_TARGET_DEBOUNCE - 1,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void pursue_disengagesWhenTargetTooFar() {
        assertEquals(State.RETURN, decideNextState(
                State.PURSUE, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z,
                new Vector3d(SHIP_AT_AIRPAD.x + 200, 0, SHIP_AT_AIRPAD.z), NOW));
    }

    @Test
    void pursue_staysWhenTargetPresentAndInRange() {
        assertEquals(State.PURSUE, decideNextState(
                State.PURSUE, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z,
                new Vector3d(SHIP_AT_AIRPAD.x + 12, 0, SHIP_AT_AIRPAD.z), NOW));
    }

    @Test
    void pursue_disengagesWhenShipStraysTooFarFromAirpad() {
        // 250-block ship displacement from airpad > LEASH_FROM_AIRPAD_SQ (200²) — even
        // though target is still in close range relative to ship, the brain breaks off
        // and heads home rather than letting the chase drag the ship across the map.
        Vector3d shipFarFromAirpad = new Vector3d(0, 200, 250);
        Vector3d targetCloseToShip = new Vector3d(shipFarFromAirpad.x + 12, 0, shipFarFromAirpad.z);
        assertEquals(State.RETURN, decideNextState(
                State.PURSUE, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                shipFarFromAirpad, AIRPAD_X, AIRPAD_Z, targetCloseToShip, NOW));
    }

    @Test
    void pursue_staysWhenWithinLeashEvenIfShipMovedSome() {
        // 100-block displacement is well inside the 200-block leash — keep chasing.
        Vector3d shipNearAirpad = new Vector3d(0, 200, 100);
        Vector3d targetCloseToShip = new Vector3d(shipNearAirpad.x + 12, 0, shipNearAirpad.z);
        assertEquals(State.PURSUE, decideNextState(
                State.PURSUE, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                shipNearAirpad, AIRPAD_X, AIRPAD_Z, targetCloseToShip, NOW));
    }

    @Test
    void pursue_targetLostTakesPriorityOverNullTarget() {
        // targetLost is computed from lastTargetSeenTick alone — a null target with a stale
        // lastTargetSeenTick still triggers RETURN (which is what happens after the brain
        // stops seeing anyone within range for LOST_TARGET_DEBOUNCE ticks).
        assertEquals(State.RETURN, decideNextState(
                State.PURSUE, ENTERED_LONG_AGO, 0, Double.NaN,
                NOW - LOST_TARGET_DEBOUNCE - 1,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z,
                /*targetPos=*/ null, NOW));
    }

    // ────────────────────────────── RETURN ──────────────────────────────

    @Test
    void return_reengagesWhenTargetEntersRange() {
        // The HOVER→PURSUE / RETURN→PURSUE re-engage path — production sees this every time
        // a player wanders back into range.
        assertEquals(State.PURSUE, decideNextState(
                State.RETURN, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z,
                new Vector3d(SHIP_AT_AIRPAD.x + 12, 0, SHIP_AT_AIRPAD.z), NOW));
    }

    @Test
    void return_transitionsToHoverAtAirpad() {
        assertEquals(State.HOVER, decideNextState(
                State.RETURN, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void return_staysWhenAwayFromAirpadAndNoTarget() {
        assertEquals(State.RETURN, decideNextState(
                State.RETURN, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_FAR_FROM_AIRPAD, AIRPAD_X, AIRPAD_Z, null, NOW));
    }

    @Test
    void return_ignoresFarTarget() {
        // Far target + away from airpad → keep returning home; don't divert.
        assertEquals(State.RETURN, decideNextState(
                State.RETURN, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_FAR_FROM_AIRPAD, AIRPAD_X, AIRPAD_Z,
                new Vector3d(SHIP_FAR_FROM_AIRPAD.x + 200, 0, SHIP_FAR_FROM_AIRPAD.z),
                NOW));
    }

    // ────────────────────────────── HOVER ──────────────────────────────

    @Test
    void hover_reengagesWhenTargetEntersRange() {
        assertEquals(State.PURSUE, decideNextState(
                State.HOVER, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z,
                new Vector3d(SHIP_AT_AIRPAD.x + 12, 0, SHIP_AT_AIRPAD.z), NOW));
    }

    @Test
    void hover_ignoresFarTarget() {
        assertEquals(State.HOVER, decideNextState(
                State.HOVER, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z,
                new Vector3d(SHIP_AT_AIRPAD.x + 200, 0, SHIP_AT_AIRPAD.z), NOW));
    }

    @Test
    void hover_staysWhenNoTarget() {
        assertEquals(State.HOVER, decideNextState(
                State.HOVER, ENTERED_LONG_AGO, 0, Double.NaN, NOW,
                SHIP_AT_AIRPAD, AIRPAD_X, AIRPAD_Z, null, NOW));
    }
}
