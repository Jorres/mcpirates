package com.mcpirates.airship.ships.ramship;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.interfaces.MovementBehavior;
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
     *  ramship chases. Measured cruise during aligned-charge regime is ~0.17 b/tick; 0.15
     *  biases the solver slightly toward longer t (further lead) so impacts land mid-hull
     *  instead of grazing the victim's tail. */
    private static final double RAMSHIP_SPEED_ESTIMATE = 0.15;
    /** Cap on time-to-intercept (ticks). Sized to allow ~50s of lead — at victim cruise
     *  ~0.12 b/tick that's ~120 blocks of lead, enough to aim well ahead of a parallel-
     *  moving target during tail-chase. The previous 200 cap clipped tail-chase intercepts
     *  to t≈200 → fallback to direct-pursuit-no-lead, which made lookahead a no-op. */
    private static final double MAX_INTERCEPT_TICKS = 1000.0;
    /** Vertical bias above the target's altitude. Currently 0 — aim straight at target.y.
     *  Re-tune up (e.g. 2-6) if hulls pass under the victim. */
    private static final double VERTICAL_BIAS_BLOCKS = 0.0;
    /** Toggle to A/B between full intercept-lead and direct-pursuit (aim at current
     *  target position). Useful when you want to see whether the chase behavior degrades
     *  without lead. */
    private static final boolean LOOKAHEAD_ENABLED = true;

    /** Throttle log emission: once every N ticks per RamMovement (singleton; one ramship
     *  in the air → one line per second). */
    private static long lastDiagTick = Long.MIN_VALUE;
    private static final long DIAG_LOG_INTERVAL_TICKS = 20;

    /** Last observed target position + tick, keyed by ramship SubLevel UUID. Used to
     *  measure target velocity by position-delta: Sable's {@code getVelocity} reads the
     *  RigidBody's linear velocity, which stays at 0 for kinematically-driven SubLevels
     *  (hot-air-balloon buoyancy moves the body through pose updates, not impulses), so
     *  it can't be trusted as a velocity source for the intercept solver. */
    private static final java.util.Map<java.util.UUID, TargetSample> LAST_TARGET_SAMPLE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private record TargetSample(java.util.UUID targetId, double tx, double tz, long tick) {}
    /** Discard sample if older than this — covers state-machine gaps (target lost then
     *  reacquired) where computed velocity would be a giant teleport across the gap. */
    private static final long TARGET_SAMPLE_STALE_TICKS = 20;

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
        double tx, ty, tz, vx, vz, sableVx, sableVz;
        java.util.UUID targetId;
        if (targetShip != null) {
            Vector3d tPos = targetShip.logicalPose().position();
            tx = tPos.x; ty = tPos.y; tz = tPos.z;
            targetId = targetShip.getUniqueId();
            // Keep Sable's reading for diagnostics — it's expected to be 0 for kinematic SubLevels.
            Vector3d tVel = dev.ryanhcode.sable.Sable.HELPER.getVelocity(
                    ship.parentLevel, targetShip, tPos, new Vector3d());
            sableVx = tVel.x / 20.0; sableVz = tVel.z / 20.0;
        } else if (targetPlayer != null) {
            tx = targetPlayer.getX(); ty = targetPlayer.getY(); tz = targetPlayer.getZ();
            targetId = targetPlayer.getUUID();
            Vec3 dm = targetPlayer.getDeltaMovement();
            sableVx = dm.x; sableVz = dm.z;
        } else {
            return null;
        }

        // Position-delta velocity (per-ramship sample state). On first sample, gap >stale,
        // or target identity changed, fall back to 0 — solver will then aim direct.
        java.util.UUID ramshipId = ship.subLevel.getUniqueId();
        TargetSample prev = LAST_TARGET_SAMPLE.get(ramshipId);
        if (prev != null && prev.targetId.equals(targetId)
                && now > prev.tick && now - prev.tick <= TARGET_SAMPLE_STALE_TICKS) {
            double dt = now - prev.tick;
            vx = (tx - prev.tx) / dt;
            vz = (tz - prev.tz) / dt;
        } else {
            vx = 0.0; vz = 0.0;
        }
        LAST_TARGET_SAMPLE.put(ramshipId, new TargetSample(targetId, tx, tz, now));

        // Y gets a fixed upward bias (VERTICAL_BIAS_BLOCKS) so the ramming hull lands
        // mid-victim instead of our prop cluster passing under it. No vertical *lead* —
        // that would let agile targets dodge by climbing/dropping.
        double goalY = ty + VERTICAL_BIAS_BLOCKS;

        double tInt = solveIntercept(tx - shipPos.x, tz - shipPos.z, vx, vz);
        boolean leadValid = !Double.isNaN(tInt) && tInt > 0 && tInt <= MAX_INTERCEPT_TICKS;
        double leadX = leadValid ? tx + vx * tInt : tx;
        double leadZ = leadValid ? tz + vz * tInt : tz;

        if (lastDiagTick == Long.MIN_VALUE || now - lastDiagTick >= DIAG_LOG_INTERVAL_TICKS) {
            lastDiagTick = now;
            String tStr = Double.isNaN(tInt) ? "NaN"
                    : tInt > MAX_INTERCEPT_TICKS ? String.format("capped(%.0f>%.0f)", tInt, MAX_INTERCEPT_TICKS)
                    : tInt <= 0 ? String.format("nonpos(%.1f)", tInt)
                    : String.format("%.0f", tInt);
            double deltaBlocks = Math.hypot(leadX - tx, leadZ - tz);
            com.mcpirates.MCPirates.LOGGER.info(
                    "ramship goal: ship=({}) target=({},{}) Vdelta=({},{}) Vsable=({},{}) | direct=({},{}) lookahead=({},{}) t={} Δ={} → using={}",
                    String.format("%.1f,%.1f", shipPos.x, shipPos.z),
                    String.format("%.1f", tx), String.format("%.1f", tz),
                    String.format("%.3f", vx), String.format("%.3f", vz),
                    String.format("%.3f", sableVx), String.format("%.3f", sableVz),
                    String.format("%.1f", tx), String.format("%.1f", tz),
                    String.format("%.1f", leadX), String.format("%.1f", leadZ),
                    tStr,
                    String.format("%.1f", deltaBlocks),
                    LOOKAHEAD_ENABLED ? "lookahead" : "direct");
        }

        if (!LOOKAHEAD_ENABLED) {
            return new Goal(tx, goalY, tz);
        }
        if (!leadValid) {
            return new Goal(tx, goalY, tz);
        }
        return new Goal(leadX, goalY, leadZ);
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
