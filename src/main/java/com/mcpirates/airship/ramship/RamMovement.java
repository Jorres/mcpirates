package com.mcpirates.airship.ramship;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.kind.MovementBehavior;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Ramming movement: aim at the predicted intercept point so the ramship arrives where
 * the target *will be*, not where it is now. Falls back to direct pursuit when no valid
 * future intercept exists (target moving away faster than {@link #RAMSHIP_SPEED_ESTIMATE},
 * stationary, or solution wildly outside a reasonable horizon).
 *
 * <p>Targets the ship first ({@code targetShip}), falls back to the player target —
 * lets the ramship engage a moving SubLevel directly when one is present.
 *
 * <p>Pure positioner. All hardware actuation (forward clutch, counter-rotation,
 * propeller reversal) lives in {@link RamControls}, which the brain calls each
 * decision tick once it has converted this movement's {@link Goal} into a
 * signed heading error.
 */
public final class RamMovement implements MovementBehavior {

    public static final RamMovement INSTANCE = new RamMovement();

    /** Approximate ramship cruise speed (blocks/tick). Lead-time estimate uses this;
     *  too low → aim point too far → ramship trails; too high → aim point too close →
     *  ramship chases. Tuned empirically. */
    private static final double RAMSHIP_SPEED_ESTIMATE = 0.3;
    /** Cap on time-to-intercept (ticks). A huge value means the target is barely moving
     *  away from us — better to chase directly than aim hundreds of blocks ahead. */
    private static final double MAX_INTERCEPT_TICKS = 200.0;

    private RamMovement() {}

    @Override
    public Goal computeGoal(Airship ship, Vector3d shipPos,
                            LivingEntity targetPlayer, SubLevel targetShip, long now) {
        // All velocities consumed below MUST be in blocks/tick, the same unit
        // RAMSHIP_SPEED_ESTIMATE is in. Vanilla {@code Entity.getDeltaMovement}
        // already is. Sable's {@code SubLevelHelper.getVelocity} returns m/s
        // — divide by SERVER_TPS=20 to bring it into the same frame, otherwise
        // the intercept math compares a per-second target velocity to a
        // per-tick estimate and silently returns garbage.
        double tx, tz, vx, vz;
        if (targetShip != null) {
            Vector3d tPos = targetShip.logicalPose().position();
            tx = tPos.x; tz = tPos.z;
            Vector3d tVel = dev.ryanhcode.sable.Sable.HELPER.getVelocity(
                    ship.parentLevel, targetShip, tPos, new Vector3d());
            vx = tVel.x / 20.0; vz = tVel.z / 20.0;
        } else if (targetPlayer != null) {
            tx = targetPlayer.getX(); tz = targetPlayer.getZ();
            Vec3 dm = targetPlayer.getDeltaMovement();
            vx = dm.x; vz = dm.z;
        } else {
            return null;
        }

        double tInt = solveIntercept(tx - shipPos.x, tz - shipPos.z, vx, vz);
        if (Double.isNaN(tInt) || tInt <= 0 || tInt > MAX_INTERCEPT_TICKS) {
            // Direct pursuit fallback: aim at current position.
            return new Goal(tx, tz);
        }
        return new Goal(tx + vx * tInt, tz + vz * tInt);
    }

    /** Solve |D + V * t|² = (S * t)² for the smallest positive {@code t}, where
     *  D = target − self (XZ only), V = target velocity (XZ), S = our speed estimate.
     *  Returns NaN if no real positive solution. */
    static double solveIntercept(double dx, double dz, double vx, double vz) {
        double s = RAMSHIP_SPEED_ESTIMATE;
        double a = vx * vx + vz * vz - s * s;
        double b = 2.0 * (dx * vx + dz * vz);
        double c = dx * dx + dz * dz;
        if (Math.abs(a) < 1e-6) {
            return Math.abs(b) > 1e-6 ? -c / b : Double.NaN;
        }
        double disc = b * b - 4 * a * c;
        if (disc < 0) return Double.NaN;
        double sqrt = Math.sqrt(disc);
        double t1 = (-b - sqrt) / (2 * a);
        double t2 = (-b + sqrt) / (2 * a);
        if (t1 > 0 && t2 > 0) return Math.min(t1, t2);
        return Math.max(t1, t2);
    }

    @Override public String debugOverlay(Airship ship) { return " ram"; }
}
