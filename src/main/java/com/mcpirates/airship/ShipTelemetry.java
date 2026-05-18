package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.physics.PlateauTable;
import com.mcpirates.airship.physics.PlateauTable.LiftSetting;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Per-ship measurements + shared log format. Covers two roles:
 * <ul>
 *   <li>Physics readouts used by control loops ({@link #velocity}, {@link #angularVelocity})
 *       — Sable's SI units converted to the per-tick convention used everywhere else.</li>
 *   <li>The unified {@link #snapshot} log line so every emit site stays consistent and
 *       unit suffixes (kg vs m³) don't get dropped at hand-rolled call sites.</li>
 * </ul>
 */
public final class ShipTelemetry {

    private ShipTelemetry() {}

    // ─── Unit formatters ──────────────────────────────────────────────────

    public static String mass(double massKg) {
        return String.format(Locale.ROOT, "%.1fkg", massKg);
    }

    /** Negative (uninitialised) → "—". */
    public static String balloonVol(int volM3) {
        return volM3 < 0 ? "—" : volM3 + "m³";
    }

    // ─── Per-burner state ─────────────────────────────────────────────────

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

    /** Sable returns per-second; per-tick is our codebase convention. Fixed at 20 even
     *  in gametest (server runs ticks fast, but physics still steps at 20Hz). */
    private static final double SERVER_TPS = 20.0;

    /** Linear velocity in blocks/tick (world frame). */
    public static Vector3d velocity(Airship a) {
        RigidBodyHandle handle = handle(a);
        if (handle == null) return new Vector3d();
        Vector3d v = handle.getLinearVelocity(new Vector3d());
        return v.div(SERVER_TPS);
    }

    /** Angular velocity in rad/tick, matching {@link Airship#yawRadians()}'s CW-positive
     *  convention (Sable is CCW-positive RHR — we negate .y). .x/.z pass through
     *  unscaled except for rad/s→rad/tick. */
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

    /** INFO snapshot of physical state; per-event detail goes in {@code event}. */
    public static void snapshot(Airship a, String event,
                                LiftSetting lift, Double targetY) {
        Vector3d pos = a.subLevel.logicalPose().position();
        Vector3d vel = velocity(a);
        double hSpeed = Math.hypot(vel.x, vel.z);
        double yawDeg = Math.toDegrees(a.yawRadians());
        double yawRateDegPerTick = Math.toDegrees(angularVelocity(a).y);
        double massKg = readMass(a);
        PlateauTable t = a.plateauTable;
        String plateau = t == null
                ? "plateau=—"
                : String.format(Locale.ROOT, "plateauRows=%d yRange=[%.1f..%.1f]",
                        t.size(), t.minY(), t.maxY());
        // gas = burnerCount · volume · lever / 15 — same quantity Aeronautics exposes
        // as HotAirBurnerBlockEntity.getGasOutput(), summed across all burners.
        int burnerCount = Math.max(1, a.lift == null ? 1 : a.lift.burnerCount());
        String liftStr = lift == null ? "—" :
                String.format(Locale.ROOT, "lever=%d vol=%dm³ gas=%.1f",
                        lift.lever(), lift.volume(),
                        burnerCount * (double) lift.volume() * lift.lever() / 15.0);
        // Read brain-stashed pickedY/biasedTargetY rather than recomputing — the brain's
        // floor clamp isn't accessible from here.
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
        // Two lines: identity/pose, then physics/lift/diag — line 1 alone locates the ship.
        MCPirates.LOGGER.info(
                "ship {} ({}) {}: state={} pos=({}) yaw={}° yawRate={}°/tick",
                a.subLevel.getUniqueId(), a.kind.name(), event, a.state,
                fmt3(pos, 1),
                String.format(Locale.ROOT, "%.1f", yawDeg),
                String.format(Locale.ROOT, "%.3f", yawRateDegPerTick));
        MCPirates.LOGGER.info(
                "  └ v=({}) hSpeed={}b/tick mass={} balloonVol={} {} lift={} {} {} {} {}",
                fmt3(vel, 3),
                String.format(Locale.ROOT, "%.3f", hSpeed),
                mass(massKg), balloonVol(a.balloonCapacity), plateau,
                liftStr, targetStr, biasedStr, pickedStr,
                diag);
    }

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
