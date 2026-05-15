package com.mcpirates.airship;

import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.PlateauTable;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable per-airship state, owned by {@link AirshipBrain}. Fields are public so
 * per-kind {@link com.mcpirates.airship.kind.CombatBehavior} strategies can read and
 * mutate cannon bookkeeping; only {@code airship.*} should touch them.
 */
public final class Airship {

    public final ServerLevel parentLevel;
    /** UUID is the only handle that survives chunk unload/reload — Sable rehydrates the
     *  SubLevel into a fresh object under the same UUID, so {@link #subLevel} must be
     *  re-acquired each tick via the container. */
    public final UUID subLevelId;
    public SubLevel subLevel;
    /** True when the SubLevel was last seen loaded by the brain. Flipped on the
     *  transition edges so the brain can emit one log line per unload/reload event
     *  instead of spamming every tick. */
    public boolean wasSubLevelLoaded = true;
    /** Liftoff-lever world-pos; also the RETURN target. */
    public final BlockPos airpadAnchor;
    public final AirshipKind kind;

    // SubLevel-local positions resolved at assembly time.
    public final List<BlockPos> slThrottleLevers;
    /** Paired 1:1 with throttle levers; resolved as "the block the lever is attached to".
     *  Levers without an adjacent burner are absent here. */
    public final List<BlockPos> slBurnerPositions;
    public final BlockPos slLeftClutchLever;
    public final BlockPos slRightClutchLever;
    public final List<BlockPos> slCannonMounts;
    /** Ship-local forward — for broadside kinds, the first-listed forward. Depends on
     *  the jigsaw rotation chosen at placement time. */
    public final Vector3d shipLocalForward;
    /** Pillagers the brain re-anchors when their Sable plot-position is wiped by a
     *  chunk reload (the anchor isn't serialised to NBT). */
    public final List<AnchoredEntity> anchoredEntities;

    /** Cannon-mount → cannoneer UUID. A mount fires only while its bound cannoneer is
     *  alive (see {@link #isMountManned}). Cannons with no nearby seat at spawn are
     *  absent; the map is immutable after construction. */
    public final Map<BlockPos, UUID> cannoneerByMount;

    public AirshipBrain.State state = AirshipBrain.State.LIFTOFF;
    public long stateEnteredTick;
    public long lastDecisionTick = Long.MIN_VALUE / 2;
    public long lastFireTick = Long.MIN_VALUE / 2;
    public long lastTargetSeenTick = Long.MIN_VALUE / 2;

    /** Aeronautics balloon capacity in m³, refreshed each decision tick. -1 until the
     *  balloon attaches. Not block-state derivable. */
    public int balloonCapacity = -1;

    /** (volume, lever) → equilibrium-altitude rows, built once the balloon attaches.
     *  Rebuilt on {@link #balloonCapacity} change. Mass is sampled at build time and
     *  never refreshed — runtime mass drift biases the lookup. */
    public PlateauTable plateauTable;
    public int plateauTableCapacity = -1;

    /** Last-tick orbit-math outputs, cached so the overlay doesn't recompute them. */
    public double lastGoalX = Double.NaN;
    public double lastGoalZ = Double.NaN;
    public double lastHeadingErrDeg = 0;

    public double lastSampledY = Double.NaN;
    public int steadyTicks = 0;
    /** Captured at the first LIFTOFF tick from the ship's actual pose (not
     *  {@link #airpadAnchor}, which is the lever's block pos and may sit below the keel).
     *  Re-captured after chunk reload so the rise check has a stable baseline. */
    public double liftoffStartY = Double.NaN;

    // Rate-limit aim and throttle logs to one per ~2s wall-clock.
    public long lastAimLogBucket = -1;
    public long lastThrottleLogBucket = -1;
    /** Per-ship (not static) so a dead ship's flags GC with it. */
    public boolean hasAimedOnce = false;
    public boolean hasFiredOnce = false;

    /** Free-form per-ship state for the kind's combat strategy (broadside uses it as a
     *  "next cannon to fire" index). -1 = uninitialised. */
    public int combatCursor = -1;

    /** Strategies layer extra cooldowns on top of the brain's per-shot interval (e.g.
     *  broadside silence after a full rotation). fire() returns false in this window. */
    public long combatNextFireTick = Long.MIN_VALUE / 2;

    /** Orbit direction during PURSUE: +1 = CCW (tangent = rot90 of ship→target), -1 = CW.
     *  Picked on PURSUE entry from current heading; flipped mid-pursue if the ship can't
     *  yaw to the chosen tangent — see {@link AirshipBrain#pickOrbitDir}. */
    public int orbitDir = 1;
    /** Consecutive {@code applyMovement} calls with sustained bad heading. Resets on
     *  flip or when heading recovers. Crossing the threshold triggers the flip. */
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

    /** True if at least one anchored crew member is alive in {@link #parentLevel}.
     *  Empty {@link #anchoredEntities} reads false — rehydrated wrecks scan for surviving
     *  pillagers and produce an empty list when none remain, which is exactly the defeat
     *  signal the brain acts on. Live ships always register with a non-empty list. */
    public boolean isAnyCrewAlive() {
        for (AnchoredEntity ae : anchoredEntities) {
            Entity e = parentLevel.getEntity(ae.uuid());
            if (e != null && !e.isRemoved() && e.isAlive()) return true;
        }
        return false;
    }

    /** @return true iff the bound cannoneer is alive and present in {@link #parentLevel}. */
    public boolean isMountManned(BlockPos slMount) {
        UUID uuid = cannoneerByMount.get(slMount);
        if (uuid == null) return false;
        Entity e = parentLevel.getEntity(uuid);
        return e != null && !e.isRemoved() && e.isAlive();
    }
}
