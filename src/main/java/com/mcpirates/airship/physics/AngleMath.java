package com.mcpirates.airship.physics;

/**
 * Pure-numeric angle helpers — normalisation and periodic clamping. Lives in this
 * subpackage (next to {@link PlateauTable}) so the math can be unit-tested without dragging
 * in Minecraft / Sable.
 *
 * <p>Two conventions used throughout:
 * <ul>
 *   <li>Radians wrap to <b>[-π, π)</b> via {@link #normalizeRadians}.</li>
 *   <li>Degrees wrap to <b>[-180, 180)</b> via {@link #wrap180}.</li>
 * </ul>
 *
 * <p>Both use the standard "shift by half-period, mod, shift back" form so the result is
 * single-pass — no while-loops, no surprises with large inputs (Long.MAX_VALUE-scale yaws
 * still terminate in O(1)). Note the exact-positive-boundary case: an input of π maps to
 * -π (and 180° to -180°), which is fine for downstream math since they're the same angle.
 */
public final class AngleMath {

    private AngleMath() {}

    /** Wrap {@code rad} into [-π, π). Equivalent to a while-loop "subtract 2π until in
     *  range" form but constant-time regardless of input magnitude. */
    public static double normalizeRadians(double rad) {
        double twoPi = 2 * Math.PI;
        return ((rad + Math.PI) % twoPi + twoPi) % twoPi - Math.PI;
    }

    /** Wrap {@code deg} into [-180, 180). Constant-time. */
    public static double wrap180(double deg) {
        return ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    /**
     * Clamp a yaw angle to {@code restYaw ± clampDegrees}, treating yaws as periodic
     * (so a yaw of -179° is one degree from a rest of +179°, not 358°). Returns
     * {@code yaw} unchanged when {@code clampDegrees <= 0} — caller's signal for
     * "no clamp; cannon free-tracks".
     */
    public static float clampYaw(float yaw, float restYaw, double clampDegrees) {
        if (clampDegrees <= 0) return yaw;
        double delta = wrap180((double) yaw - restYaw);
        if (delta >  clampDegrees) delta =  clampDegrees;
        if (delta < -clampDegrees) delta = -clampDegrees;
        return (float) wrap180(restYaw + delta);
    }
}
