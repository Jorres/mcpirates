package com.mcpirates.airship;

import com.mcpirates.airship.kind.AirshipKind;
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
 * Mutable per-airship state, owned by {@link AirshipBrain}. One {@code Airship} = one
 * registered pirate ship in the world.
 *
 * <p>This was previously a private inner record-style class on AirshipBrain. It was lifted
 * out so per-kind {@link com.mcpirates.airship.kind.CombatBehavior} strategies (cannon
 * aim/fire) can read/mutate the per-ship cannon bookkeeping without going through the
 * brain. Fields are deliberately public so the kind subpackage can poke them; the
 * "interface" is informal but contained — only {@link AirshipBrain} constructs these and
 * only {@code airship.*} touches them.
 */
public final class Airship {

    public final ServerLevel parentLevel;
    /** Stable identity of the airship's SubLevel across chunk unload/reload. The
     *  {@link #subLevel} object reference is replaced when Sable rehydrates the
     *  SubLevel into a fresh object on reload — the UUID is the only thing that
     *  persists. Look up the live SubLevel through {@code parentLevel}'s
     *  SubLevelContainer each tick using this id. */
    public final UUID subLevelId;
    public SubLevel subLevel;
    /** Lever world-pos at lift-off time. Also the "airpad anchor" the ship returns to. */
    public final BlockPos airpadAnchor;
    public final AirshipKind kind;

    // SubLevel-local positions resolved at assembly time.
    /** All throttle-equivalent levers (analog or simulated:throttle) that gate the
     *  burner(s). Two-burner kinds keep these in lock-step. */
    public final List<BlockPos> slThrottleLevers;
    public final BlockPos slLeftClutchLever;
    public final BlockPos slRightClutchLever;
    /** All cannon mounts the kind installed at lift-off. Empty for cannonless ships. */
    public final List<BlockPos> slCannonMounts;
    /** Ship "forward" (cannon-points-this-way, or first-listed forward for ships with
     *  broadsides) in SubLevel-local coords. Depends on the structure rotation chosen by
     *  jigsaw at placement time. */
    public final Vector3d shipLocalForward;
    /** Entities (captain, crewmate) the brain re-anchors each tick when their Sable
     *  plot-position is missing (i.e., after chunk unload/reload wiped the in-memory
     *  anchor). */
    public final List<AnchoredEntity> anchoredEntities;

    /** Cannon-mount → cannoneer UUID. A mount fires only while its bound cannoneer is
     *  alive ({@link #isMountManned}). Populated at spawn from seat→cannon proximity; if
     *  a cannon had no nearby seat in the NBT it is simply absent from the map (which
     *  reads as "not manned" — that cannon never fires). The map itself is unmodifiable
     *  after construction; we don't add cannoneers mid-flight. */
    public final Map<BlockPos, UUID> cannoneerByMount;

    public AirshipBrain.State state = AirshipBrain.State.LIFTOFF;
    public long stateEnteredTick;
    public long lastDecisionTick = Long.MIN_VALUE / 2;
    public long lastFireTick = Long.MIN_VALUE / 2;
    public long lastTargetSeenTick = Long.MIN_VALUE / 2;

    // Last actuator commands — debug bookkeeping only.
    public boolean lastLeftEngaged = false;
    public boolean lastRightEngaged = false;
    public int lastThrottle = 0;
    public double lastGoalX = Double.NaN;
    public double lastGoalZ = Double.NaN;
    public double lastHeadingErrDeg = 0;

    // Altitude steady-state tracking for LIFTOFF.
    public double lastSampledY = Double.NaN;
    public int steadyTicks = 0;

    // Previous-tick altitude sample feeding the throttle PD's vertical-velocity estimate.
    public double lastYSample = Double.NaN;
    public long lastYSampleTick = Long.MIN_VALUE;

    // Rate-limit aim and throttle logs to one per ~2s wall-clock.
    public long lastAimLogBucket = -1;
    public long lastThrottleLogBucket = -1;
    /** Gate "first aim/fire" log lines so each ship emits them exactly once over its
     *  lifetime. Per-Airship (not static) so dead ships' bookkeeping is GC'd with the ship. */
    public boolean hasAimedOnce = false;
    public boolean hasFiredOnce = false;

    /** Free-form per-ship state for the kind's combat strategy. Currently used by
     *  {@link com.mcpirates.airship.kind.BroadsideCombat} as a "next cannon to fire"
     *  index for rolling broadsides — the strategy itself is shared across ships of the
     *  same kind and must stay stateless, so per-ship cursors live here. -1 = uninitialised. */
    public int combatCursor = -1;

    /** Earliest game-tick at which the combat strategy is willing to fire again. The
     *  brain owns the per-shot interval (one cannon every {@code FIRE_INTERVAL_TICKS}),
     *  but strategies can layer extra cooldowns on top — e.g. broadside imposes a longer
     *  silence after a full N-cannon rotation. fire() returns false when in this window. */
    public long combatNextFireTick = Long.MIN_VALUE / 2;

    public Airship(ServerLevel parentLevel, SubLevel subLevel, BlockPos airpadAnchor,
                   AirshipKind kind,
                   List<BlockPos> slThrottleLevers,
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
        this.slLeftClutchLever = slLeftClutchLever;
        this.slRightClutchLever = slRightClutchLever;
        this.slCannonMounts = slCannonMounts;
        this.shipLocalForward = shipLocalForward;
        this.anchoredEntities = anchoredEntities;
        this.cannoneerByMount = cannoneerByMount;
    }

    /**
     * @return true iff this cannon mount has a bound cannoneer and that cannoneer is
     *         alive and present in the parent level. False for cannons that never had a
     *         bound seat (NBT designer omitted), cannons whose cannoneer died, or cannons
     *         whose cannoneer entity was somehow removed (chunk evict + failed reload).
     *         Combat behaviours call this before aiming/firing each mount.
     */
    public boolean isMountManned(BlockPos slMount) {
        UUID uuid = cannoneerByMount.get(slMount);
        if (uuid == null) return false;
        Entity e = parentLevel.getEntity(uuid);
        return e != null && !e.isRemoved() && e.isAlive();
    }
}
