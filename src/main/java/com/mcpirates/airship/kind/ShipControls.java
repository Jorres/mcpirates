package com.mcpirates.airship.kind;

import com.mcpirates.airship.Airship;

/**
 * Per-kind steering actuator. The brain decides <em>what</em> it wants the ship
 * to do (drive toward this heading; idle); the implementation decides
 * <em>how</em> to do it on the specific hardware the ship owns — clutches,
 * propeller-reverse states, kind-specific forward thrusters.
 *
 * <p>This is the one seam where ship-hardware knowledge lives. The brain
 * deliberately does not touch {@code ClutchLevers}, {@code Propellers}, or any
 * block in the SubLevel directly — every change goes through this interface,
 * so adding a new ship layout (different prop count, different actuation
 * scheme) is a new {@link ShipControls} implementation rather than a fan-out
 * of {@code instanceof} checks across the brain.
 *
 * <p>(Lift control — throttle levers + burner volumes — still lives in
 * {@code AirshipBrain.applyMovement} for now; the actuation pattern is
 * uniform across every kind so the per-kind seam isn't earning its keep
 * there. Same migration when that changes.)
 */
public interface ShipControls {

    /**
     * Drive the ship this decision tick.
     * @param a              the airship; SubLevel-frame hardware positions
     *                       are read off it.
     * @param headingErrRad  signed angular error from current heading to
     *                       desired heading, normalised to [-π, π]. Positive
     *                       = turn left (CCW), negative = turn right (CW).
     *                       Magnitude is the implementation's signal for
     *                       gentle-turn vs pivot-in-place.
     */
    void applySteering(Airship a, double headingErrRad);

    /**
     * Stop all propulsion this decision tick. Used on arrival (within the
     * brain's arrival radius), on state transitions where the brain wants a
     * clean off→on edge, and on derelict shutdown.
     */
    void release(Airship a);

    /**
     * Optional debug snapshot of the actuator state — propeller thrust,
     * clutch power, reverse flags. Default empty. {@link com.mcpirates.airship.ShipLog#snapshot}
     * appends this to every log line so we can diagnose hardware-side
     * problems without the brain knowing what hardware exists.
     */
    default String diagnostics(Airship a) { return ""; }
}
