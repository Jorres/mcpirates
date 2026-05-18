package com.mcpirates.airship;

import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.PlateauTable;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable per-airship state owned by {@link AirshipBrain}. Public fields so per-kind
 * combat/movement strategies can read & mutate bookkeeping; nothing else should touch them.
 */
public final class Airship {

    public final ServerLevel parentLevel;
    /** Only handle that survives chunk reload; Sable swaps the instance under the same UUID. */
    public final UUID subLevelId;
    public SubLevel subLevel;
    /** Flipped on load/unload edges so the brain logs once per transition. */
    public boolean wasSubLevelLoaded = true;
    /** Liftoff-lever world-pos; also the RETURN target. */
    public final BlockPos airpadAnchor;
    public final AirshipKind kind;

    public final List<BlockPos> slThrottleLevers;
    /** Paired 1:1 with throttle levers (block the lever attaches to); absent if no burner. */
    public final List<BlockPos> slBurnerPositions;
    public final BlockPos slLeftClutchLever;
    public final BlockPos slRightClutchLever;
    public final List<BlockPos> slCannonMounts;
    /** Depends on jigsaw rotation chosen at placement. */
    public final Vector3d shipLocalForward;
    /** Crew the brain re-anchors after chunk reload. Replaced by {@link #installCrew} on
     *  MOORED→LIFTOFF promotion. */
    public List<AnchoredEntity> anchoredEntities;

    /** Cannon → cannoneer UUID; mount fires only while bound cannoneer is alive. */
    public Map<BlockPos, UUID> cannoneerByMount;

    public AirshipBrain.State state = AirshipBrain.State.LIFTOFF;
    public long stateEnteredTick;

    /** NAVIGATE destination; Y is unused. Null in other states. */
    public Vector3d navDestination;
    public long lastSteeringTick = Long.MIN_VALUE / 2;
    public long lastLiftTick = Long.MIN_VALUE / 2;
    public long lastFireTick = Long.MIN_VALUE / 2;
    public long lastTargetSeenTick = Long.MIN_VALUE / 2;

    /** Balloon capacity in m³; -1 until the balloon attaches. */
    public int balloonCapacity = -1;

    /** (volume, lever) → equilibrium-altitude rows. Mass sampled at build time only. */
    public PlateauTable plateauTable;
    public int plateauTableCapacity = -1;

    /** Steering actuator built by the kind's makeControls factory. Brain calls only
     *  {@code applySteering}/{@code release}. */
    public com.mcpirates.airship.kind.ShipControls controls;

    /** Lift actuator built by the kind's makeLift factory. Brain calls only
     *  {@code apply}/{@code queryBalloonCapacity}. */
    public com.mcpirates.airship.kind.ShipLift lift;

    public double lastGoalX = Double.NaN;
    public double lastGoalZ = Double.NaN;
    public double lastHeadingErrDeg = 0;

    /** Equilibrium Y of the plateau row last committed; read by ShipTelemetry so logs reflect
     *  what the brain actually wrote (including floor clamp). */
    public double lastPickedEquilibriumY = Double.NaN;
    /** Velocity-damped target Y last handed to the plateau picker. */
    public double lastBiasedTargetY = Double.NaN;

    public double lastSampledY = Double.NaN;
    public int steadyTicks = 0;
    /** Captured at first LIFTOFF tick from the ship's pose (not airpadAnchor — that's the
     *  lever pos and may sit below the keel). Re-captured after chunk reload. */
    public double liftoffStartY = Double.NaN;

    public long lastAimLogBucket = -1;
    public long lastThrottleLogBucket = -1;
    public boolean hasAimedOnce = false;
    public boolean hasFiredOnce = false;

    /** Free-form per-ship state for combat strategy (broadside: next-cannon index). */
    public int combatCursor = -1;

    /** Strategy-layered cooldown on top of the brain's per-shot interval. */
    public long combatNextFireTick = Long.MIN_VALUE / 2;

    /** +1 = CCW, -1 = CW. Picked on PURSUE entry; flipped if stuck. */
    public int orbitDir = 1;
    /** Consecutive bad-heading decisions; threshold triggers an orbit flip. */
    public int orbitStuckDecisions = 0;

    public Airship(ServerLevel parentLevel, SubLevel subLevel, BlockPos airpadAnchor,
                   AirshipKind kind,
                   List<BlockPos> slThrottleLevers,
                   List<BlockPos> slBurnerPositions,
                   BlockPos slLeftClutchLever, BlockPos slRightClutchLever,
                   List<BlockPos> slCannonMounts,
                   Vector3d shipLocalForward,
                   List<AnchoredEntity> anchoredEntities,
                   Map<BlockPos, UUID> cannoneerByMount) {
        this.parentLevel = parentLevel;
        this.subLevel = subLevel;
        this.subLevelId = subLevel.getUniqueId();
        this.airpadAnchor = airpadAnchor;
        this.kind = kind;
        this.slThrottleLevers = slThrottleLevers;
        this.slBurnerPositions = slBurnerPositions;
        this.slLeftClutchLever = slLeftClutchLever;
        this.slRightClutchLever = slRightClutchLever;
        this.slCannonMounts = slCannonMounts;
        this.shipLocalForward = shipLocalForward;
        this.anchoredEntities = anchoredEntities;
        this.cannoneerByMount = cannoneerByMount;
    }

    /** Empty {@link #anchoredEntities} reads false — that's the defeat signal. */
    public boolean isAnyCrewAlive() {
        for (AnchoredEntity ae : anchoredEntities) {
            Entity e = parentLevel.getEntity(ae.uuid());
            if (e != null && !e.isRemoved() && e.isAlive()) return true;
        }
        return false;
    }

    public boolean isMountManned(BlockPos slMount) {
        UUID uuid = cannoneerByMount.get(slMount);
        if (uuid == null) return false;
        Entity e = parentLevel.getEntity(uuid);
        return e != null && !e.isRemoved() && e.isAlive();
    }

    /** Replace deck-crew refs after a deferred spawn (MOORED→LIFTOFF promotion). */
    public void installCrew(List<AnchoredEntity> anchors, Map<BlockPos, UUID> cannoneerByMount) {
        this.anchoredEntities = anchors;
        this.cannoneerByMount = cannoneerByMount;
    }

    /** Yaw in {@code [-π, π)}: zero = +Z, positive = CW from above. Opposite of Sable's
     *  right-hand-rule angular velocity; {@link ShipTelemetry#angularVelocity} negates .y to match. */
    public double yawRadians() {
        Quaterniond orient = subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(shipLocalForward), new Vector3d());
        return Math.atan2(-worldFwd.x, worldFwd.z);
    }
}
