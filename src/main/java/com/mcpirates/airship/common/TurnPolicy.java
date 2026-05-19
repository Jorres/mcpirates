package com.mcpirates.airship.common;

/**
 * Shared heading-error → steering-regime decision. Used by every {@link
 * com.mcpirates.airship.interfaces.ShipControls} implementation so all ships pick the same
 * regime for the same error; each controller then maps the regime to its own hardware
 * (clutch counts, prop reversal availability).
 *
 * <p>Three regimes form a continuum:
 * <ul>
 *   <li>{@link Regime#ALIGNED}: error inside the deadzone — straight thrust, no yaw.</li>
 *   <li>{@link Regime#TANK_STEER}: moderate error — engage the outboard on the *outside*
 *       of the turn, drop the inside one. Continuous forward motion while yawing.</li>
 *   <li>{@link Regime#COUNTER_ROTATE}: severe error — pure pivot. Required when forward
 *       thrust would push us further from the target (err past ~90° of dead reckon).
 *       Controllers without prop-reversal plumbing fall back to TANK_STEER here.</li>
 * </ul>
 */
public final class TurnPolicy {

    private TurnPolicy() {}

    public enum Regime { ALIGNED, TANK_STEER, COUNTER_ROTATE }

    /** {@code yawDir} = +1 (CW) / −1 (CCW) / 0 (ALIGNED, no yaw needed). */
    public record Decision(Regime regime, int yawDir) {}

    /** Below this error: ALIGNED. Sized tight enough that impacts on a ramming hull
     *  land on-center, not the edges. */
    public static final double ALIGNED_DEADZONE_DEG = 10.0;
    /** Above this error: COUNTER_ROTATE — forward thrust would be wasted (or worse,
     *  push away from target). Below it: TANK_STEER. */
    public static final double COUNTER_ROTATE_THRESHOLD_DEG = 40.0;
    /** PD lookahead on yaw rate so the controller starts braking the spin before the
     *  hull crosses the deadzone. {@code effectiveErr = rawErr − K·yawRate}. Sized to
     *  the ~45° coast a fast-yawing ship makes at steady state. */
    public static final double YAW_LOOKAHEAD_TICKS = 70.0;

    /** {@code headingErrRad} positive = target is CW of current heading.
     *  {@code yawRateRadPerTick} matches {@code ShipTelemetry.angularVelocity().y}. */
    public static Decision decide(double headingErrRad, double yawRateRadPerTick) {
        double absRawErrDeg = Math.abs(Math.toDegrees(headingErrRad));
        if (absRawErrDeg < ALIGNED_DEADZONE_DEG) {
            return new Decision(Regime.ALIGNED, 0);
        }
        double effectiveErrRad = headingErrRad - YAW_LOOKAHEAD_TICKS * yawRateRadPerTick;
        int yawDir = effectiveErrRad > 0 ? +1 : -1;
        Regime regime = absRawErrDeg < COUNTER_ROTATE_THRESHOLD_DEG
                ? Regime.TANK_STEER : Regime.COUNTER_ROTATE;
        return new Decision(regime, yawDir);
    }
}
