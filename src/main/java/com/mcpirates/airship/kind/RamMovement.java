package com.mcpirates.airship.kind;

import com.mcpirates.airship.Airship;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
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
        // Forward propeller: engaged whenever we're trying to ram. The clutch is on the
        // ship's movement axis, owned by RamshipKind. Resolved lazily from the SubLevel
        // userDataTag so rehydration after restart needs zero extra wiring.
        setForwardClutchEngaged(ship, true);

        double tx, tz, vx, vz;
        if (targetShip != null) {
            Vector3d tPos = targetShip.logicalPose().position();
            tx = tPos.x; tz = tPos.z;
            Vector3d tVel = dev.ryanhcode.sable.Sable.HELPER.getVelocity(
                    ship.parentLevel, targetShip, tPos, new Vector3d());
            vx = tVel.x; vz = tVel.z;
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

    @Override
    public void onEnterPursue(Airship ship, Vector3d shipPos,
                              LivingEntity targetPlayer, SubLevel targetShip) {
        // Off→on edge on entry: Aeronautics ignores props that were already powered
        // through a chunk reload, so a fresh disengage forces a refresh before the next
        // computeGoal re-engages.
        setForwardClutchEngaged(ship, false);
    }

    @Override
    public void onExitPursue(Airship ship) {
        setForwardClutchEngaged(ship, false);
    }

    @Override
    public String debugOverlay(Airship ship) {
        return " ram";
    }

    private static void setForwardClutchEngaged(Airship ship, boolean engaged) {
        if (!(ship.kind instanceof RamshipKind ramship)) return;
        BlockPos slClutch = resolveSlForwardClutch(ship, ramship);
        if (slClutch == null) return;
        Level subLevelLevel = ship.subLevel.getLevel();
        if (subLevelLevel == null) return;
        // ClutchLevers semantics: powered=true disengages, powered=false engages.
        ClutchLevers.setPowered(subLevelLevel, slClutch, !engaged);
    }

    /** Look up the SubLevel-frame forward-clutch position from the assembly stamp.
     *  Returns null if the SubLevel hasn't been stamped (shouldn't happen at runtime —
     *  every assembly writes the tag). */
    private static BlockPos resolveSlForwardClutch(Airship ship, RamshipKind ramship) {
        if (!(ship.subLevel instanceof ServerSubLevel ssl)) return null;
        CompoundTag userTag = ssl.getUserDataTag();
        if (userTag == null || !userTag.contains("mcpirates")) return null;
        CompoundTag mcp = userTag.getCompound("mcpirates");
        if (!mcp.contains("slPrimaryAnchor") || !mcp.contains("rotation")) return null;
        Rotation rot = Rotation.values()[mcp.getInt("rotation")];
        BlockPos slPrimaryAnchor = BlockPos.of(mcp.getLong("slPrimaryAnchor"));
        return slPrimaryAnchor.offset(ramship.forwardClutchLeverDelta().rotate(rot));
    }
}
