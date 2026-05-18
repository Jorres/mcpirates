package com.mcpirates.airship.ships.galleon;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.physics.AngleMath;
import com.mcpirates.airship.hardware.CannonOps;
import com.mcpirates.airship.interfaces.CombatBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Galleon broadside: only the side facing the target fires.
 *
 * <p>Active side is picked by signed lateral offset of the target in ship-local frame.
 * Each cannon's yaw is clamped to ±{@link #yawClampDegrees} of its rest direction (port
 * cannons rest pointing ship-LEFT, starboard ship-RIGHT) so they never swing through the hull.
 *
 * <p>Firing is a rolling broadside: every fire tick (every {@value #FIRE_INTERVAL_TICKS}t)
 * one cannon discharges; after the last cannon on the side fires we add an extra
 * {@value #SALVO_COOLDOWN_TICKS}t silence. Cycle for a 4-cannon side ≈ 8s.
 *
 * <p>Per-ship state (cursor, next-fire tick) lives on {@link Airship}; the strategy itself
 * is shared and stateless.
 */
public final class BroadsideCombat implements CombatBehavior {

    public enum Side { LEFT, RIGHT }

    /** Parallel to {@code cannonMountDeltas()}: sides[i] tags cannon i. */
    public record MountSide(Side side) {}

    private static final int FIRE_INTERVAL_TICKS = 25;
    private static final int SALVO_COOLDOWN_TICKS = 60;

    public static final double DEFAULT_YAW_CLAMP_DEGREES = 40.0;

    private final List<MountSide> sides;
    private final double yawClampDegrees;

    public BroadsideCombat(List<MountSide> sides) {
        this(sides, DEFAULT_YAW_CLAMP_DEGREES);
    }

    /** {@code yawClampDegrees <= 0} disables clamping (free-tracking). */
    public BroadsideCombat(List<MountSide> sides, double yawClampDegrees) {
        this.sides = sides;
        this.yawClampDegrees = yawClampDegrees;
    }

    @Override
    public int fireIntervalTicks() {
        return FIRE_INTERVAL_TICKS;
    }

    @Override
    public void aim(Airship ship, LivingEntity target) {
        Side active = activeSide(ship, target);
        if (active == null) return;
        float portRestYaw = restYawForSide(Side.LEFT, ship);
        float starboardRestYaw = restYawForSide(Side.RIGHT, ship);
        int n = Math.min(sides.size(), ship.slCannonMounts.size());
        for (int i = 0; i < n; i++) {
            MountSide ms = sides.get(i);
            if (ms.side() != active) continue;
            BlockPos slMount = ship.slCannonMounts.get(i);
            // Dead-gunner cannons freeze at last aim; per-cannon check keeps the rest firing.
            if (!ship.isMountManned(slMount)) continue;
            CannonOps.Aim raw = CannonOps.computeAim(ship, slMount, target);
            float restYaw = (ms.side() == Side.LEFT) ? portRestYaw : starboardRestYaw;
            float clampedYaw = AngleMath.clampYaw(raw.yaw(), restYaw, yawClampDegrees);
            CannonOps.applyAim(ship, slMount, clampedYaw, raw.pitch());
        }
    }

    @Override
    public boolean fire(Airship ship, LivingEntity target) {
        long now = ship.parentLevel.getGameTime();
        if (now < ship.combatNextFireTick) return false;

        Side active = activeSide(ship, target);
        if (active == null) return false;
        List<BlockPos> activeMounts = collectSideMounts(ship, active);
        if (activeMounts.isEmpty()) return false;
        // Cursor is modulo manned-count so dying gunners shrink the rotation cleanly.
        List<BlockPos> manned = new ArrayList<>(activeMounts.size());
        for (BlockPos m : activeMounts) {
            if (ship.isMountManned(m)) manned.add(m);
        }
        if (manned.isEmpty()) return false;
        int next = Math.floorMod(ship.combatCursor + 1, manned.size());
        ship.combatCursor = next;
        boolean fired = CannonOps.fireOnce(ship, manned.get(next));
        if (fired && next == manned.size() - 1) {
            ship.combatNextFireTick = now + SALVO_COOLDOWN_TICKS;
        }
        return fired;
    }

    /** Rest yaw (degrees) for a cannon on {@code side}, derived from {@code shipLocalForward}. */
    private static float restYawForSide(Side side, Airship ship) {
        // ship-RIGHT = (-fz, 0, fx); ship-LEFT is its negation.
        double fx = ship.shipLocalForward.x;
        double fz = ship.shipLocalForward.z;
        double rx, rz;
        if (side == Side.RIGHT) { rx = -fz; rz = fx; }
        else                    { rx = fz;  rz = -fx; }
        return (float) Math.toDegrees(Math.atan2(-rx, rz));
    }

    /** Which side faces the target, or null inside a ±15° nose/tail dead-zone. */
    private Side activeSide(Airship ship, LivingEntity target) {
        Vector3d shipPos = ship.subLevel.logicalPose().position();
        Vector3d worldVec = new Vector3d(target.getX() - shipPos.x, 0, target.getZ() - shipPos.z);
        Vector3d localVec = ship.subLevel.logicalPose().orientation()
                .transformInverse(worldVec, new Vector3d());

        // right = forward × up → rightX = -forward.z, rightZ = +forward.x.
        double fx = ship.shipLocalForward.x;
        double fz = ship.shipLocalForward.z;
        double lateral = localVec.x * (-fz) + localVec.z * fx;
        double dist = Math.sqrt(localVec.x * localVec.x + localVec.z * localVec.z);
        if (dist < 0.01) return null;
        if (Math.abs(lateral / dist) < 0.26) return null;  // sin(15°)
        return lateral > 0 ? Side.RIGHT : Side.LEFT;
    }

    private List<BlockPos> collectSideMounts(Airship ship, Side side) {
        int n = Math.min(sides.size(), ship.slCannonMounts.size());
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (sides.get(i).side() == side) out.add(ship.slCannonMounts.get(i));
        }
        return out;
    }
}
