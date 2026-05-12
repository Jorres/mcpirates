package com.mcpirates.airship.kind;

import com.mcpirates.airship.Airship;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Galleon-style broadside combat. The ship has cannons mounted on both sides of the hull;
 * at any moment only the side <em>facing the target</em> fires.
 *
 * <h2>Side selection</h2>
 *
 * Each registered mount carries a "side" flag (LEFT or RIGHT). We compute the target's
 * bearing in ship-local frame:
 * <ul>
 *   <li>local-X &gt; 0 → target is to ship's STARBOARD (RIGHT)</li>
 *   <li>local-X &lt; 0 → target is to ship's PORT (LEFT)</li>
 * </ul>
 * The "ship's right" depends on the {@code shipLocalForward} convention: forward is the
 * cannon-bow direction; right is 90° CW from forward in the XZ plane.
 *
 * <h2>Firing arc clamp</h2>
 *
 * Each cannon physically points out one beam (port or starboard) when at rest. Letting
 * them swing freely would have them rotating through the hull to track an overhead /
 * astern target — visually broken and (with CBC's ray-collision firing) actually
 * shooting into the ship's own decking. So we clamp each cannon's yaw to ±{@link
 * #yawClampDegrees} of its rest direction:
 *
 * <ul>
 *   <li>port cannon rest direction = ship-LEFT in ship-local frame</li>
 *   <li>starboard cannon rest direction = ship-RIGHT</li>
 * </ul>
 *
 * If the computed aim falls outside the cone, we clamp to the nearest edge so the
 * cannon visually swings to the extreme of its arc and stops. The dead-zone in
 * {@link #activeSide} already prevents firing on nose/tail targets; the clamp is
 * about the visual + interior-collision concern.
 *
 * <p><strong>Cost.</strong> One per-cannon yaw clamp = a few floating-point ops + a
 * BE write. Cheap enough to do every aim tick without batching. If this ever becomes
 * a hotspot the obvious lever is to drop the aim cadence (raise {@code AIM_INTERVAL})
 * rather than complicate this path.
 *
 * <h2>Firing pattern — rolling broadside with end-of-salvo pause</h2>
 *
 * <p>All cannons on the active side aim every aim tick. Firing is staggered: each fire
 * opportunity (gated by the brain at {@link #fireIntervalTicks} = {@value
 * #FIRE_INTERVAL_TICKS} ticks) discharges exactly ONE cannon. The next call advances to
 * the next cannon on the active side.
 *
 * <p>After the LAST cannon on the side fires (cursor reaches {@code size-1}), the
 * strategy imposes an extra {@value #SALVO_COOLDOWN_TICKS}-tick silence on top of the
 * brain's per-shot interval. Result for a 4-cannon side:
 *
 * <pre>
 *   BOOM   BOOM   BOOM   BOOM    . . . pause . . .    BOOM   BOOM   BOOM   BOOM
 *  t=0    +25   +50    +75                          +85*    ...
 *  (* = +85 instead of +25 because of the salvo cooldown)
 * </pre>
 *
 * <p>Total cycle ≈ 4×25 + 60 = 160 ticks = 8 s per full broadside cycle. Tunable via
 * the two constants below.
 *
 * <p>State (cursor + next-allowed-tick) lives on {@link Airship} — the strategy itself
 * is shared across all ships of this kind and must stay stateless.
 *
 * <p><strong>Side-switching mid-roll.</strong> If the target moves across the bow/stern
 * arc, the active side flips. We use {@code cursor mod activeCount} so a starboard
 * cursor naturally indexes into the port list after the flip — the player doesn't see
 * either side reset to cannon zero, which feels fine. The end-of-salvo pause does NOT
 * reset on side flip — a side flip mid-roll is uncommon and worth letting the player
 * hear the broadside finish on the original side before the pause kicks in.
 *
 * <p><strong>Future tweaks.</strong> Per-cannon line-of-sight checks (skip a cannon
 * whose arc is blocked by friendlies or the hull) is a candidate. Pitch clamps
 * (currently free-rotating) might be needed if extreme dive/climb angles let the cannon
 * clip the deck.
 */
public final class BroadsideCombat implements CombatBehavior {

    public enum Side { LEFT, RIGHT }

    /** Mount with its hull-side flag, NBT-frame so it survives jigsaw rotation. The
     *  brain stores resolved SubLevel positions in {@link Airship#slCannonMounts} in the
     *  SAME order as the {@code cannonMountDeltas()} list, so we can index by parallel
     *  position. */
    public record MountSide(Side side) {}

    /** Ticks between consecutive cannon shots in a rolling broadside. 25 ticks = 1.25 s
     *  per cannon → ~5 s for a full 4-cannon side. */
    private static final int FIRE_INTERVAL_TICKS = 25;

    /** Extra silence after the last cannon of a salvo fires, on top of the per-shot
     *  interval. 60 ticks = 3 s of quiet, giving the player a beat of "incoming!"
     *  before the next BOOM-BOOM-BOOM-BOOM rolls. */
    private static final int SALVO_COOLDOWN_TICKS = 60;

    /** Default ± yaw range each cannon can swing from its rest direction. 40° lets the
     *  cannon track a target moving along the beam without ever pointing inboard. */
    public static final double DEFAULT_YAW_CLAMP_DEGREES = 40.0;

    private final List<MountSide> sides;
    private final double yawClampDegrees;

    /** @param sides parallel to {@code cannonMountDeltas()} — sides[i] is the side of
     *               cannon i. Uses {@link #DEFAULT_YAW_CLAMP_DEGREES}. */
    public BroadsideCombat(List<MountSide> sides) {
        this(sides, DEFAULT_YAW_CLAMP_DEGREES);
    }

    /** @param yawClampDegrees ± degrees the cannon may swing from its rest direction.
     *                         {@code <= 0} disables clamping (cannon free-tracks). */
    public BroadsideCombat(List<MountSide> sides, double yawClampDegrees) {
        this.sides = sides;
        this.yawClampDegrees = yawClampDegrees;
    }

    @Override
    public int fireIntervalTicks() {
        return FIRE_INTERVAL_TICKS;
    }

    @Override
    public void aim(Airship ship, ServerPlayer target) {
        Side active = activeSide(ship, target);
        if (active == null) return;
        float portRestYaw = restYawForSide(Side.LEFT, ship);
        float starboardRestYaw = restYawForSide(Side.RIGHT, ship);
        int n = Math.min(sides.size(), ship.slCannonMounts.size());
        for (int i = 0; i < n; i++) {
            MountSide ms = sides.get(i);
            if (ms.side() != active) continue;
            BlockPos slMount = ship.slCannonMounts.get(i);
            // Skip cannons whose gunner is dead — barrel freezes at last aim. Per-cannon
            // check so a single dead gunner doesn't take the whole broadside down.
            if (!ship.isMountManned(slMount)) continue;
            CannonOps.Aim raw = CannonOps.computeAim(ship, slMount, target);
            float restYaw = (ms.side() == Side.LEFT) ? portRestYaw : starboardRestYaw;
            float clampedYaw = clampYaw(raw.yaw(), restYaw);
            CannonOps.applyAim(ship, slMount, clampedYaw, raw.pitch());
        }
    }

    @Override
    public boolean fire(Airship ship, ServerPlayer target) {
        // Strategy-owned end-of-salvo gate. The brain has already cleared its own
        // per-shot cooldown by the time it called us; this layers an additional pause
        // after the LAST cannon of a side fires. Brain's tick rate (1/tick during the
        // gate) is cheap — the false-return is just a comparison.
        long now = ship.parentLevel.getGameTime();
        if (now < ship.combatNextFireTick) return false;

        Side active = activeSide(ship, target);
        if (active == null) return false;
        List<BlockPos> activeMounts = collectSideMounts(ship, active);
        if (activeMounts.isEmpty()) return false;
        // Filter to mounts whose cannoneer is still alive. The rolling cursor is then
        // taken modulo the *manned* count, so dying gunners naturally shrink the rotation
        // without skip cycles. If every gunner on the active side is dead, we return
        // false without consuming the fire tick — the brain just keeps trying next tick.
        List<BlockPos> manned = new ArrayList<>(activeMounts.size());
        for (BlockPos m : activeMounts) {
            if (ship.isMountManned(m)) manned.add(m);
        }
        if (manned.isEmpty()) return false;
        // Pick the next cannon in the rolling sequence. cursor starts at -1 so the first
        // shot lands on index 0. (cursor + 1) % size handles wrap and side-flip both —
        // see class doc.
        int next = Math.floorMod(ship.combatCursor + 1, manned.size());
        ship.combatCursor = next;
        boolean fired = CannonOps.fireOnce(ship, manned.get(next));
        if (fired && next == manned.size() - 1) {
            // We just fired the last cannon on the active side — apply the salvo
            // cooldown. Next fire() returns false until now + SALVO_COOLDOWN_TICKS, at
            // which point the cursor wraps to 0 for the next roll.
            ship.combatNextFireTick = now + SALVO_COOLDOWN_TICKS;
        }
        return fired;
    }

    /** Rest yaw (degrees) for a cannon on {@code side}, in ship-local frame. Derived
     *  from {@code shipLocalForward}: port cannons point at ship-LEFT, starboard at
     *  ship-RIGHT. */
    private static float restYawForSide(Side side, Airship ship) {
        // ship-RIGHT = (-fz, 0, fx); ship-LEFT = (fz, 0, -fx).  See activeSide for why.
        double fx = ship.shipLocalForward.x;
        double fz = ship.shipLocalForward.z;
        double rx, rz;
        if (side == Side.RIGHT) { rx = -fz; rz = fx; }
        else                    { rx = fz;  rz = -fx; }
        return (float) Math.toDegrees(Math.atan2(-rx, rz));
    }

    /** Clamp {@code yaw} to {@code restYaw ± yawClampDegrees}, both in degrees. Handles
     *  the periodic wrap-around at ±180°. Returns {@code yaw} unchanged when
     *  {@code yawClampDegrees <= 0} (clamp disabled). */
    private float clampYaw(float yaw, float restYaw) {
        if (yawClampDegrees <= 0) return yaw;
        double delta = wrap180(yaw - restYaw);
        if (delta >  yawClampDegrees) delta =  yawClampDegrees;
        if (delta < -yawClampDegrees) delta = -yawClampDegrees;
        return (float) wrap180(restYaw + delta);
    }

    /** Normalise a degree value into (-180, 180]. */
    private static double wrap180(double deg) {
        deg = ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return deg;
    }

    /** Which broadside (if any) faces the target. Null when target is dead-ahead or
     *  dead-astern within a small dead-zone (no clean shot). */
    private Side activeSide(Airship ship, ServerPlayer target) {
        // World-frame ship position via SubLevel pose.
        Vector3d shipPos = ship.subLevel.logicalPose().position();
        // Target's bearing in WORLD frame.
        double wdx = target.getX() - shipPos.x;
        double wdz = target.getZ() - shipPos.z;
        // Rotate INTO ship-local frame via the SubLevel orientation inverse.
        Vector3d worldVec = new Vector3d(wdx, 0, wdz);
        Vector3d localVec = ship.subLevel.logicalPose().orientation()
                .transformInverse(worldVec, new Vector3d());

        // shipLocalForward defines what "forward" means. We need ship-RIGHT (starboard).
        // In MC world (X east, Y up, Z south), right = forward × up. Component-wise that
        // simplifies to: right.x = -forward.z,  right.z = +forward.x.
        //
        // Quick sanity check: forward=NORTH=(0,0,-1) → right = (-(-1), 0, 0) = (1,0,0) = EAST ✓
        // The dot of the target's local position with "right" tells us signed lateral
        // offset — positive = target is starboard, negative = port.
        double fx = ship.shipLocalForward.x;
        double fz = ship.shipLocalForward.z;
        double rightX = -fz;
        double rightZ = fx;
        double lateral = localVec.x * rightX + localVec.z * rightZ;
        // Dead-zone: target within ~15° of the nose/tail axis has no clear broadside arc.
        double dist = Math.sqrt(localVec.x * localVec.x + localVec.z * localVec.z);
        if (dist < 0.01) return null;
        if (Math.abs(lateral / dist) < 0.26) return null;  // sin(15°) ≈ 0.26
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
