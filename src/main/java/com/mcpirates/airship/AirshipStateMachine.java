package com.mcpirates.airship;

import com.mcpirates.airship.AirshipBrain.State;
import org.joml.Vector3d;

/**
 * Pure logic for the airship brain's state machine — the gate computations and the
 * {@link State} → next-{@link State} transition. Lifted out of {@link AirshipBrain} so
 * the transition rules can be exercised by JUnit without booting Minecraft: this class
 * imports nothing from {@code net.minecraft.*} or NeoForge, so its classfile loads cleanly
 * on a test classpath that only has joml plus the project's own output.
 *
 * <p>The thresholds (engagement range, LIFTOFF gates, hover radius, target-lost debounce)
 * live here so tests can pin behaviour against the exact production values rather than
 * literal magic numbers. The non-state-machine brain constants (orbit math, debug overlay
 * cadence, etc.) stay on {@link AirshipBrain} — they're not part of this seam.
 */
public final class AirshipStateMachine {

    /** Engage/disengage PURSUE if a target enters/leaves this horizontal radius. Was
     *  12 chunks (192 blocks = exactly vanilla render distance) — ship started pursuing
     *  the instant it rendered, which felt sudden. 8 chunks (128 blocks) gives roughly
     *  64 blocks of "ship is visible, player can appreciate it" before combat engages. */
    public static final double DISENGAGE_RANGE_SQ = (8 * 16) * (8 * 16);
    /** Considered "at airpad" (HOVER) when within this horizontal range. */
    public static final double HOVER_RADIUS_SQ = 16 * 16;
    /** Consecutive steady-altitude ticks before LIFTOFF concludes. ~2 s at 20 tps = 40. */
    static final int LIFTOFF_STEADY_TICKS = 40;
    /** Hard floor on LIFTOFF duration so we don't bail out before the ship's even moved
     *  (at t=0 ship is stationary so the steady-tick count is technically high immediately). */
    static final int LIFTOFF_MIN_TICKS = 60;
    /** Minimum rise (blocks) for the steady-altitude exit path. Without this guard,
     *  a ship parked at its anchor — never gained any altitude — looks "steady" for
     *  40 ticks and exits LIFTOFF, engaging propellers at ground level. */
    static final double LIFTOFF_STEADY_MIN_RISE = 8.0;
    /** Blocks of altitude the ship must gain over its {@link Airship#liftoffStartY} before
     *  LIFTOFF can end. Closes a real bug: pre-rise stillness (Aeronautics buoyancy
     *  ramp-up) looks identical to post-ascent steady-state under the per-tick steady-Y
     *  check, so the steady-tick count reaches the threshold while the ship is still
     *  parked at the anchor. Without this guard LIFTOFF exits, propellers engage, ship
     *  crashes into nearby terrain/trees. 25 chosen to match the MIN_ALT_ABOVE_GROUND
     *  floor used elsewhere in the brain. */
    public static final double LIFTOFF_MIN_RISE = 25.0;
    /** Ticks of "no target seen" before dropping out of PURSUE back to RETURN. */
    static final int LOST_TARGET_DEBOUNCE = 60;

    private AirshipStateMachine() {}

    /**
     * Given a snapshot of the inputs that drive the brain's decision, return the next
     * state. No side effects, no mutation, no dependency on a live {@link Airship} —
     * suitable for branch-coverage JUnit tests in milliseconds. {@link AirshipBrain#tickShip}
     * is the production caller: it extracts the fields from a live {@code Airship} and
     * passes them through; the gates ({@code liftoffDone}, {@code atAirpad},
     * {@code targetLost}, {@code targetTooFar}) are derived locally so each can be flexed
     * independently in tests.
     *
     * <p>The {@code rose} sub-gate is NaN-safe: {@code liftoffStartY = NaN} (the field's
     * default until tickShip captures the first LIFTOFF Y) makes {@code rose} read false,
     * keeping the gate closed until real altitude data exists.
     *
     * @param targetPos {@code null} if there is no target this tick.
     */
    public static State decideNextState(
            State state,
            long stateEnteredTick,
            int steadyTicks,
            double liftoffStartY,
            long lastTargetSeenTick,
            Vector3d shipPos,
            double airpadX, double airpadZ,
            Vector3d targetPos,
            long now) {
        return decideNextState(state, stateEnteredTick, steadyTicks, liftoffStartY,
                lastTargetSeenTick, shipPos, airpadX, airpadZ, targetPos, now,
                LIFTOFF_MIN_RISE);
    }

    /** Per-kind overload — used by {@link AirshipBrain} which knows the ship's kind
     *  and can supply a kind-specific {@code minRise}. The no-{@code minRise}
     *  overload exists for tests that want the default. */
    public static State decideNextState(
            State state,
            long stateEnteredTick,
            int steadyTicks,
            double liftoffStartY,
            long lastTargetSeenTick,
            Vector3d shipPos,
            double airpadX, double airpadZ,
            Vector3d targetPos,
            long now,
            double minRise) {
        long ticksInState = now - stateEnteredTick;
        double rise = Double.isNaN(liftoffStartY) ? 0.0 : (shipPos.y - liftoffStartY);
        boolean rose = !Double.isNaN(liftoffStartY) && rise >= minRise;
        // Stabilized at altitude — for ships whose lift/mass ratio caps them below
        // minRise (their pressure ceiling is the actual exit), the steady gate is
        // the escape. Guarded by LIFTOFF_STEADY_MIN_RISE so a parked ship that
        // never moved doesn't look "steady" and exit at ground level.
        boolean stabilized = !Double.isNaN(liftoffStartY)
                && rise >= LIFTOFF_STEADY_MIN_RISE
                && steadyTicks >= LIFTOFF_STEADY_TICKS;
        boolean liftoffDone = (rose || stabilized) && ticksInState >= LIFTOFF_MIN_TICKS;
        boolean atAirpad = horizDistSq(shipPos, airpadX, airpadZ) < HOVER_RADIUS_SQ;
        boolean targetLost = (now - lastTargetSeenTick) > LOST_TARGET_DEBOUNCE;
        boolean targetTooFar = targetPos != null
                && horizDistSq(shipPos, targetPos.x, targetPos.z) > DISENGAGE_RANGE_SQ;

        return switch (state) {
            case LIFTOFF -> {
                if (!liftoffDone) yield State.LIFTOFF;
                yield (targetPos != null && !targetTooFar) ? State.PURSUE : State.RETURN;
            }
            case PURSUE -> {
                if (targetLost || targetTooFar) yield State.RETURN;
                yield State.PURSUE;
            }
            case RETURN -> {
                if (targetPos != null && !targetTooFar) yield State.PURSUE;
                yield atAirpad ? State.HOVER : State.RETURN;
            }
            case HOVER -> {
                if (targetPos != null && !targetTooFar) yield State.PURSUE;
                yield State.HOVER;
            }
        };
    }

    private static double horizDistSq(Vector3d a, double bx, double bz) {
        double dx = a.x - bx, dz = a.z - bz;
        return dx * dx + dz * dz;
    }
}
