package com.mcpirates.airship;

import com.mcpirates.airship.anchor.MCPShipAnchorBlock;
import com.mcpirates.airship.hardware.HotAirBurners;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.physics.PlateauTable;
import com.mcpirates.airship.ships.AirshipKinds;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Rotation;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable per-airship state owned by {@link AirshipBrain}. Public fields so per-kind
 * combat/movement strategies can read & mutate bookkeeping; nothing else should touch them.
 *
 * <p>Persistence: {@link #persist()} flushes the entire state to Sable's
 * {@code userDataTag.mcpirates}. Hardware addresses live behind {@code ShipLift} /
 * {@code ShipControls}; those actuators self-persist via their own {@code writeNbt}.
 */
public final class Airship {

    public static final String NBT_ROOT_KEY = "mcpirates";

    public final ServerLevel parentLevel;
    /** Only handle that survives chunk reload; Sable swaps the instance under the same UUID. */
    public final UUID subLevelId;
    public SubLevel subLevel;
    /** Flipped on load/unload edges so the brain logs once per transition. */
    public boolean wasSubLevelLoaded = true;
    /** Liftoff-lever world-pos; also the RETURN target. */
    public final BlockPos airpadAnchor;
    public final AirshipKind kind;
    /** Worldgen rotation chosen at placement; defines how NBT-frame deltas map. Read by
     *  MOORED→LIFTOFF promotion to spawn deferred crew with the right glue bbox. */
    public final Rotation rotation;
    /** SL-local position of the primary anchor lever. Phase-C deferred: lives here today
     *  so the MOORED→LIFTOFF crew-spawn can compute its glue-bbox seat scan. */
    public final BlockPos slPrimaryAnchor;

    public final List<BlockPos> slCannonMounts;
    /** Derived from {@code rotation.rotate(MCPShipAnchorBlock.NBT_FACING)}. */
    public final Vector3d shipLocalForward;
    /** Crew the brain re-anchors after chunk reload. Replaced by {@link #installCrew} on
     *  MOORED→LIFTOFF promotion. */
    public List<AnchoredEntity> anchoredEntities;

    /** Cannon → cannoneer UUID; mount fires only while bound cannoneer is alive. */
    public Map<BlockPos, UUID> cannoneerByMount;

    /** Last Aim computed per cannon mount, populated by {@link com.mcpirates.airship.interfaces.CombatBehavior}
     *  implementations during {@code aim()} and read during {@code fire()} so the ballistic
     *  solver only runs once per cannon per tick. Soft-restart tolerant — empty on reload,
     *  next aim tick re-populates. */
    public final Map<BlockPos, com.mcpirates.airship.hardware.CannonOps.Aim> lastAimByMount = new HashMap<>();

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

    /** (volume, lever) → equilibrium-altitude rows. Not persisted — rebuilt from current
     *  mass + capacity on demand. */
    public PlateauTable plateauTable;
    public int plateauTableCapacity = -1;

    /** Steering actuator built by the kind's makeControls factory. Brain calls only
     *  {@code applySteering}/{@code release}. */
    public com.mcpirates.airship.interfaces.ShipControls controls;

    /** Lift actuator built by the kind's makeLift factory. Brain calls only
     *  {@code apply}/{@code queryBalloonCapacity}. */
    public com.mcpirates.airship.interfaces.ShipLift lift;

    public double lastGoalX = Double.NaN;
    public double lastGoalY = Double.NaN;
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
                   AirshipKind kind, Rotation rotation, BlockPos slPrimaryAnchor,
                   List<BlockPos> slCannonMounts,
                   List<AnchoredEntity> anchoredEntities,
                   Map<BlockPos, UUID> cannoneerByMount) {
        this.parentLevel = parentLevel;
        this.subLevel = subLevel;
        this.subLevelId = subLevel.getUniqueId();
        this.airpadAnchor = airpadAnchor;
        this.kind = kind;
        this.rotation = rotation;
        this.slPrimaryAnchor = slPrimaryAnchor;
        this.slCannonMounts = slCannonMounts;
        this.anchoredEntities = anchoredEntities;
        this.cannoneerByMount = cannoneerByMount;
        Direction fwd = rotation.rotate(MCPShipAnchorBlock.NBT_FACING);
        this.shipLocalForward = new Vector3d(fwd.getStepX(), fwd.getStepY(), fwd.getStepZ());
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
        persist();
    }

    /** Yaw in {@code [-π, π)}: zero = +Z, positive = CW from above. Opposite of Sable's
     *  right-hand-rule angular velocity; {@link ShipTelemetry#angularVelocity} negates .y to match. */
    public double yawRadians() {
        Quaterniond orient = subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(shipLocalForward), new Vector3d());
        return Math.atan2(-worldFwd.x, worldFwd.z);
    }

    /** Flush full state to {@code userDataTag.mcpirates}. Called on register, every state
     *  transition, {@link #installCrew}, and ServerStopping. */
    public void persist() {
        if (!(subLevel instanceof ServerSubLevel ssl)) return;
        CompoundTag userTag = ssl.getUserDataTag();
        if (userTag == null) userTag = new CompoundTag();
        userTag.put(NBT_ROOT_KEY, writeNbt());
        ssl.setUserDataTag(userTag);
    }

    /**
     * Persisted set is the minimum needed to reconstruct identity + state machine + crew:
     * <ul>
     *   <li>Identity / actuator rebuild: {@code kind, rotation, airpad, slPrimaryAnchor,
     *       slCannonMounts}.</li>
     *   <li>State machine: {@code state, stateEnteredTick, navDestination}.</li>
     *   <li>Crew: {@code anchors, cannoneers}.</li>
     * </ul>
     * Everything else on {@code Airship} (timers, telemetry, log dedup, combat cursor,
     * orbit state, balloon cache, LIFTOFF rise samples) is soft-restart-tolerant —
     * defaults regenerate on the next brain decision and no behaviour-correctness
     * property depends on the prior value. See {@code docs/tech-debt.md} ("Log-dedup
     * state on Airship may not be earning its keep") for the audit.
     */
    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind.name());
        tag.putInt("rotation", rotation.ordinal());
        tag.putLong("airpad", airpadAnchor.asLong());
        tag.putLong("slPrimaryAnchor", slPrimaryAnchor.asLong());
        tag.putLongArray("slCannonMounts", toLongArray(slCannonMounts));

        tag.putString("state", state.name());
        tag.putLong("stateEnteredTick", stateEnteredTick);
        if (navDestination != null) {
            tag.putDouble("navDestX", navDestination.x);
            tag.putDouble("navDestZ", navDestination.z);
        }

        ListTag anchors = new ListTag();
        for (AnchoredEntity ae : anchoredEntities) anchors.add(ae.writeNbt());
        tag.put("anchors", anchors);

        ListTag cannoneers = new ListTag();
        for (Map.Entry<BlockPos, UUID> e : cannoneerByMount.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("mount", e.getKey().asLong());
            entry.putUUID("uuid", e.getValue());
            cannoneers.add(entry);
        }
        tag.put("cannoneers", cannoneers);
        return tag;
    }

    /** Returns null if the tag is unparseable (unknown kind, etc.); caller logs.
     *  The returned Airship has its {@code controls}/{@code lift} actuators already rebuilt;
     *  soft-restart-tolerant fields (timers / telemetry / log dedup / combat / orbit /
     *  balloon cache) take their default values. */
    public static Airship readNbt(ServerLevel parentLevel, ServerSubLevel subLevel, CompoundTag tag) {
        String kindName = tag.getString("kind");
        AirshipKind kind = AirshipKinds.byName(kindName);
        if (kind == null) return null;
        Rotation rotation = Rotation.values()[tag.getInt("rotation")];
        BlockPos airpad = BlockPos.of(tag.getLong("airpad"));
        BlockPos slPrimary = BlockPos.of(tag.getLong("slPrimaryAnchor"));
        List<BlockPos> slCannons = fromLongArray(tag.getLongArray("slCannonMounts"));

        List<AnchoredEntity> anchors = new ArrayList<>();
        ListTag anchorList = tag.getList("anchors", Tag.TAG_COMPOUND);
        for (int i = 0; i < anchorList.size(); i++) {
            anchors.add(AnchoredEntity.readNbt(anchorList.getCompound(i)));
        }
        Map<BlockPos, UUID> cannoneers = new HashMap<>();
        ListTag cannoneerList = tag.getList("cannoneers", Tag.TAG_COMPOUND);
        for (int i = 0; i < cannoneerList.size(); i++) {
            CompoundTag entry = cannoneerList.getCompound(i);
            cannoneers.put(BlockPos.of(entry.getLong("mount")), entry.getUUID("uuid"));
        }

        Airship a = new Airship(parentLevel, subLevel, airpad, kind, rotation, slPrimary,
                slCannons, anchors, cannoneers);

        a.state = AirshipBrain.State.valueOf(tag.getString("state"));
        a.stateEnteredTick = tag.getLong("stateEnteredTick");
        if (tag.contains("navDestX")) {
            a.navDestination = new Vector3d(tag.getDouble("navDestX"), 0.0, tag.getDouble("navDestZ"));
        }

        rebuildActuators(a);
        return a;
    }

    /** Resolve SL-frame hardware positions from {@code slPrimaryAnchor + rotation + kind}
     *  deltas, rescan the plot bbox for burners, then call the kind's
     *  {@link AirshipKind#makeControls} / {@link AirshipKind#makeLift} factories. Called by
     *  both fresh assembly ({@code AirshipLiftoffTrigger.activateShip}) and rehydrate
     *  ({@link #readNbt}). */
    static void rebuildActuators(Airship a) {
        Layout layout = a.kind.layoutAt(a.rotation, a.slPrimaryAnchor);
        var plotBox = ((ServerSubLevel) a.subLevel).getPlot().getBoundingBox();
        List<BlockPos> slBurnerPositions = HotAirBurners.findAllInBox(
                a.subLevel.getLevel(),
                plotBox.minX(), plotBox.minY(), plotBox.minZ(),
                plotBox.maxX(), plotBox.maxY(), plotBox.maxZ());
        if (slBurnerPositions.isEmpty()) {
            com.mcpirates.MCPirates.LOGGER.warn(
                    "({}) no Hot Air Burners found in SubLevel plot; lift control disabled",
                    a.kind.name());
        }
        a.controls = a.kind.makeControls(a, layout.leftClutch(), layout.rightClutch(),
                a.slPrimaryAnchor, a.rotation);
        a.lift = a.kind.makeLift(layout.throttleLevers(), slBurnerPositions);
    }

    private static long[] toLongArray(List<BlockPos> positions) {
        long[] out = new long[positions.size()];
        for (int i = 0; i < positions.size(); i++) out[i] = positions.get(i).asLong();
        return out;
    }

    private static List<BlockPos> fromLongArray(long[] packed) {
        List<BlockPos> out = new ArrayList<>(packed.length);
        for (long p : packed) out.add(BlockPos.of(p));
        return out;
    }
}
