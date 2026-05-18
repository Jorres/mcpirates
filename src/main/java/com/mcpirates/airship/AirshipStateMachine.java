package com.mcpirates.airship;

import com.mcpirates.airship.AirshipBrain.State;
import org.joml.Vector3d;

/**
 * Pure state-machine logic for the airship brain — split out of {@link AirshipBrain} so
 * JUnit can exercise transitions without booting Minecraft. No MC/NeoForge imports.
 */
public final class AirshipStateMachine {

    public static final double DISENGAGE_RANGE_SQ = 100 * 100;
    public static final double HOVER_RADIUS_SQ = 16 * 16;
    /** RETURN past this distance from the airpad so chases don't drag the ship arbitrarily
     *  far from its outpost. */
    public static final double LEASH_FROM_AIRPAD_SQ = 200 * 200;
    static final int LIFTOFF_STEADY_TICKS = 40;
    /** Floor on LIFTOFF duration so the steady gate doesn't fire while still stationary. */
    static final int LIFTOFF_MIN_TICKS = 60;
    /** Rise gate on the steady-altitude exit, so a parked ship can't look "steady". */
    static final double LIFTOFF_STEADY_MIN_RISE = 8.0;
    /** Primary LIFTOFF rise gate; matches default {@code minAltAboveGround()} less buffer.
     *  Pre-rise buoyancy ramp looks like steady-state under per-tick checks, so without
     *  this guard the ship exits LIFTOFF at ground level. */
    public static final double LIFTOFF_MIN_RISE = 25.0;
    static final int LOST_TARGET_DEBOUNCE = 60;

    private AirshipStateMachine() {}

    /** {@code targetPos == null} means no target. NaN {@code liftoffStartY} reads as
     *  no-rise (gate stays closed until tickShip captures the first sample). */
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

    /** Per-kind overload taking an explicit {@code minRise}. */
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
        // Stabilized: escape gate for ships whose pressure ceiling is below minRise.
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
                if (horizDistSq(shipPos, airpadX, airpadZ) > LEASH_FROM_AIRPAD_SQ) yield State.RETURN;
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
            // MOORED + NAVIGATE are externally driven; the auto machine never exits them.
            case MOORED -> State.MOORED;
            case NAVIGATE -> State.NAVIGATE;
        };
    }

    private static double horizDistSq(Vector3d a, double bx, double bz) {
        double dx = a.x - bx, dz = a.z - bz;
        return dx * dx + dz * dz;
    }

    /** +1 CCW / -1 CW, chosen by best dot of tangent with current world-forward.
     *  Degenerate (ship at target) → +1. */
    public static int pickOrbitDir(double shipX, double shipZ,
                                   double targetX, double targetZ,
                                   double yawRad) {
        double ftx = shipX - targetX;
        double ftz = shipZ - targetZ;
        double r2 = ftx * ftx + ftz * ftz;
        if (r2 < 1e-4) return 1;
        double r = Math.sqrt(r2);
        double tanPlusX = -ftz / r;
        double tanPlusZ = ftx / r;
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double dot = tanPlusX * fwdX + tanPlusZ * fwdZ;
        return dot >= 0 ? 1 : -1;
    }
}
