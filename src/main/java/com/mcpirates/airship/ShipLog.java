package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.LiftMath.LiftSetting;
import com.mcpirates.airship.kind.PlateauTable;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Single source of truth for ship-state log lines. Two reasons it exists:
 *
 * <ol>
 *   <li><b>Unit traps.</b> Ship mass is in kg, balloon capacity is in m³, but
 *       their numeric magnitudes overlap (low hundreds for our ships), so
 *       hand-rolled formatters at each call site keep dropping units and
 *       inviting bad mass-vs-cap comparisons. The unit suffixes live here.
 *       See {@code memory/feedback_mass_vs_balloon_volume.md}.
 *   <li><b>Duplication.</b> Every place we log a ship was emitting a slightly
 *       different subset of the same physical state — plateau-built log,
 *       throttle log, debug command, gametest sample — each with its own
 *       format string. {@link #snapshot} centralises that snapshot so the
 *       fields stay consistent and adding a new universal field (e.g.
 *       horizontal speed) lands in one place.
 * </ol>
 */
public final class ShipLog {

    private ShipLog() {}

    // ─── Unit formatters ──────────────────────────────────────────────────

    /** Format ship mass with its unit suffix. */
    public static String mass(double massKg) {
        return String.format(Locale.ROOT, "%.1fkg", massKg);
    }

    /** Format balloon capacity (m³). Negative caps (uninitialised balloon)
     *  fall through to {@code "—"} so they don't look like real values. */
    public static String balloonVol(int volM3) {
        return volM3 < 0 ? "—" : volM3 + "m³";
    }

    // ─── Per-burner state ─────────────────────────────────────────────────

    /** One-line dump of a hot-air burner and its attached balloon (if any).
     *  Used by debug commands, physics gametests, and anywhere we want the
     *  whole burner/balloon snapshot. */
    public static String describe(HotAirBurnerBlockEntity burner) {
        var balloon = burner.getBalloon();
        if (balloon == null) {
            return String.format(Locale.ROOT, "balloon=null signal=%d gasOutput=%.2f",
                    burner.getSignalStrength(), burner.getGasOutput());
        }
        if (balloon instanceof ServerBalloon sb) {
            return String.format(Locale.ROOT,
                    "balloonVol=%s filled=%.1fm³ target=%.1fm³ totalLift=%.2f signal=%d gasOutput=%.2f",
                    balloonVol(sb.getCapacity()),
                    sb.getTotalFilledVolume(), sb.getTotalTargetVolume(),
                    sb.getTotalLift(), burner.getSignalStrength(), burner.getGasOutput());
        }
        return String.format(Locale.ROOT, "balloon class=%s signal=%d",
                balloon.getClass().getSimpleName(), burner.getSignalStrength());
    }

    // ─── Velocity from the physics system ────────────────────────────────

    /** Server ticks per second — Sable's physics returns velocities in
     *  per-second units; everywhere else in this codebase we reason in
     *  per-tick units (decision intervals, lookahead horizons), so the
     *  conversion factor lives here. Fixed at 20: the gametest server
     *  runs ticks as fast as possible, but Minecraft physics still
     *  steps at the canonical 20 Hz rate inside each tick. */
    private static final double SERVER_TPS = 20.0;

    /** Linear velocity of the ship's SubLevel rigid body in <em>blocks per tick</em>
     *  (world frame). Sable returns m/s; we convert at this seam so callers
     *  reasoning in tick units don't have to remember the factor. */
    public static Vector3d velocity(Airship a) {
        RigidBodyHandle handle = handle(a);
        if (handle == null) return new Vector3d();
        Vector3d v = handle.getLinearVelocity(new Vector3d());
        return v.div(SERVER_TPS);
    }

    /** Angular velocity of the SubLevel rigid body in <em>radians per tick</em>,
     *  in the same sign convention as {@link Airship#yawRadians()} — i.e. {@code .y}
     *  is {@code dyaw/dt}. Two conversions live at this seam:
     *
     *  <ol>
     *    <li>Sable returns rad/s; we divide by {@link #SERVER_TPS} to get rad/tick,
     *        matching the per-tick decision intervals + PD horizons used everywhere
     *        else in the codebase.</li>
     *    <li>Sable's rigid-body angular velocity uses the standard right-hand rule
     *        around +Y (positive = CCW from above). {@link Airship#yawRadians()},
     *        defined as {@code atan2(-fwd.x, fwd.z)}, has positive = <em>CW</em> from
     *        above (south→west→north→east is the direction yaw increases). So we
     *        negate {@code .y} here — without it, the PD term in
     *        {@code RamControls.applySteering} pulls the effective error in the
     *        wrong direction and the controller picks the opposite rotation it
     *        intended (visible 2026-05-17 as the ramship swinging 180° past target
     *        instead of 30°).</li>
     *  </ol>
     *
     *  <p>The .x and .z components are passed through with only the rad/s → rad/tick
     *  scaling; no caller currently consumes them, but if one starts it should
     *  document its own sign convention. */
    public static Vector3d angularVelocity(Airship a) {
        RigidBodyHandle handle = handle(a);
        if (handle == null) return new Vector3d();
        Vector3d v = handle.getAngularVelocity(new Vector3d());
        v.div(SERVER_TPS);
        v.y = -v.y;
        return v;
    }

    private static RigidBodyHandle handle(Airship a) {
        if (!(a.subLevel instanceof ServerSubLevel ssl)) return null;
        ServerSubLevelContainer container =
                (ServerSubLevelContainer) SubLevelContainer.getContainer(a.parentLevel);
        if (container == null || container.physicsSystem() == null) return null;
        return container.physicsSystem().getPhysicsHandle(ssl);
    }

    // ─── Unified snapshot ─────────────────────────────────────────────────

    /**
     * Emit a single INFO-level snapshot of the ship's physical state. Every
     * site that wants to log a ship should call this; per-event detail goes
     * in the {@code event} tag, not in the field set.
     *
     * <p>Fields (always present, in this order):
     * <ul>
     *   <li>{@code state} — brain state machine value (LIFTOFF/PURSUE/...)
     *   <li>{@code pos} — world position from the SubLevel logical pose
     *   <li>{@code v} — linear velocity (blocks/tick)
     *   <li>{@code hSpeed} — horizontal magnitude of {@code v}
     *   <li>{@code mass} — kg, from the Aeronautics mass tracker
     *   <li>{@code balloonVol} — m³, from the attached balloon (or "—" if none)
     *   <li>{@code plateauRows} — plateau-table row count
     *   <li>{@code yRange} — plateau-table equilibrium altitude range
     *   <li>{@code lift} — actuator output: {@code lever/volume T=N.N} (or "—" if not chosen this call)
     *   <li>{@code targetY} / {@code dy} — current altitude goal and signed error
     *       ({@code shipY - targetY}); {@code "—"} when the caller doesn't pass them
     * </ul>
     */
    public static void snapshot(Airship a, String event,
                                LiftSetting lift, Double targetY) {
        Vector3d pos = a.subLevel.logicalPose().position();
        Vector3d vel = velocity(a);
        double hSpeed = Math.hypot(vel.x, vel.z);
        // Yaw + yaw rate so we can see whether a turning ship is actually
        // rotating. Yaw is extracted from the SubLevel's logical orientation
        // quaternion; yaw rate is the .y component of angular velocity
        // (radians/tick).
        double yawDeg = Math.toDegrees(a.yawRadians());
        double yawRateDegPerTick = Math.toDegrees(angularVelocity(a).y);
        double massKg = readMass(a);
        PlateauTable t = a.plateauTable;
        String plateau = t == null
                ? "plateau=—"
                : String.format(Locale.ROOT, "plateauRows=%d yRange=[%.1f..%.1f]",
                        t.size(), t.minY(), t.maxY());
        int burnerCount = Math.max(1, a.slBurnerPositions.size());
        String liftStr = lift == null ? "—" :
                String.format(Locale.ROOT, "%d/%d T=%.1f",
                        lift.lever(), lift.volume(),
                        burnerCount * (double) lift.volume() * lift.lever() / 15.0);
        // pickedY is the equilibrium altitude of the plateau row the brain
        // picked. Computed from the same velocity-damped target the brain uses
        // ({@code targetY − K·v.y}, see VELOCITY_LOOKAHEAD_TICKS), so the log
        // shows the row actually selected, not a naive lookup ignoring v.y.
        //
        // Comparing pickedY ↔ targetY ↔ pos.y separates three cases:
        //   • brain aimed correctly, ship is overshooting from momentum
        //   • velocity damping is shifting pickedY away from targetY on purpose
        //   • picker mismatch — no plateau row exists near the biased target.
        // Read brain-stashed values rather than recomputing — the brain's bias
        // math includes a {@code maxGroundAhead}-derived floor clamp that's not
        // accessible from here, so any local recomputation would silently
        // diverge from what the brain actually committed to (we hit that
        // exact bug — log showed pickedY=-64 while the brain was picking -28).
        String pickedStr = Double.isNaN(a.lastPickedEquilibriumY)
                ? "pickedY=—"
                : String.format(Locale.ROOT, "pickedY=%.1f", a.lastPickedEquilibriumY);
        String biasedStr = Double.isNaN(a.lastBiasedTargetY)
                ? "biasedTargetY=—"
                : String.format(Locale.ROOT, "biasedTargetY=%.1f", a.lastBiasedTargetY);
        String targetStr = (targetY == null)
                ? "targetY=— dy=—"
                : String.format(Locale.ROOT, "targetY=%.1f dy=%.1f",
                        targetY, pos.y - targetY);

        String diag = a.controls == null ? "" : a.controls.diagnostics(a);
        MCPirates.LOGGER.info(
                "ship {} ({}) {}: state={} pos=({}) yaw={}° yawRate={}°/tick v=({}) hSpeed={}b/tick mass={} balloonVol={} {} lift={} {} {} {} {}",
                a.subLevel.getUniqueId(), a.kind.name(), event, a.state,
                fmt3(pos, 1),
                String.format(Locale.ROOT, "%.1f", yawDeg),
                String.format(Locale.ROOT, "%.3f", yawRateDegPerTick),
                fmt3(vel, 3),
                String.format(Locale.ROOT, "%.3f", hSpeed),
                mass(massKg), balloonVol(a.balloonCapacity), plateau,
                liftStr, targetStr, biasedStr, pickedStr,
                diag);
    }

    /** Convenience overload for sites that don't have a {@code lift}/{@code targetY}
     *  in scope (plateau-table-built, debug commands, etc.). */
    public static void snapshot(Airship a, String event) {
        snapshot(a, event, null, null);
    }

    private static double readMass(Airship a) {
        if (!(a.subLevel instanceof ServerSubLevel ssl)) return Double.NaN;
        var md = ssl.getMassTracker();
        return (md == null || md.isInvalid()) ? Double.NaN : md.getMass();
    }

    private static String fmt3(Vector3d v, int decimals) {
        String fmt = "%." + decimals + "f, %." + decimals + "f, %." + decimals + "f";
        return String.format(Locale.ROOT, fmt, v.x, v.y, v.z);
    }
}
