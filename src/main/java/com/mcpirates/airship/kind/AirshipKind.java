package com.mcpirates.airship.kind;

import com.mcpirates.pirates.GroundCombatModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Per-design constants for one kind of pirate airship. {@link com.mcpirates.airship.AirshipBrain}
 * runs flight behaviour generically; lever positions, cannon layout, and other per-design data
 * live here.
 *
 * <p>Positional methods return <strong>NBT-frame deltas from the primary-anchor lever</strong>.
 * The trigger detects worldgen rotation by comparing the anchor's world FACING to
 * {@link #nbtPrimaryFacing()} and rotates every delta accordingly. Ships are identified by
 * a hidden {@link com.mcpirates.airship.anchor.MCPShipAnchorBlock} that stores the kind name;
 * the trigger walks from the anchor to the primary lever via {@link #anchorToLeverDelta()}.
 */
public interface AirshipKind {

    String name();

    // ───────────── orientation / identification ─────────────

    /** NBT-frame FACING of the primary-anchor lever; used to derive worldgen rotation. */
    Direction nbtPrimaryFacing();

    /** NBT-frame "ship forward" — the bow direction. Rotated into world frame at trigger. */
    Direction nbtForward();

    ThrottleLevers.Kind throttleLeverKind();

    /** Cheap pre-filter the trigger uses before reading the anchor's pointed-at lever. */
    boolean isPrimaryAnchorBE(BlockEntity be);

    /** NBT-frame delta from the metadata anchor block to the primary lever. */
    BlockPos anchorToLeverDelta();

    // ───────────── layout (NBT-frame deltas from the primary anchor) ─────────────

    /** Portable engines to fuel at lift-off. */
    List<BlockPos> engineDeltas();

    /** All throttle-equivalent levers (including the primary at {@code (0,0,0)}); the brain
     *  writes the same state to each so multi-burner kinds stay in lock-step. */
    List<BlockPos> throttleLeverDeltas();

    /** Vanilla lever driving the port propeller clutch. */
    BlockPos leftClutchLeverDelta();

    /** Vanilla lever driving the starboard propeller clutch. */
    BlockPos rightClutchLeverDelta();

    /** Build steering controls bound to this assembly's already-resolved hardware positions.
     *  Default: tank-steer on the two clutch levers. Kinds with extra hardware override. */
    default ShipControls makeControls(com.mcpirates.airship.Airship airship,
                                      BlockPos slPrimaryAnchor,
                                      net.minecraft.world.level.block.Rotation rotation) {
        return new TankSteerControls(airship.slLeftClutchLever, airship.slRightClutchLever);
    }

    /** Build lift actuator bound to this assembly's burners + throttle levers.
     *  Default: hot-air balloon lift across every throttle/burner pair. Kinds with a
     *  different lift scheme would override. */
    default ShipLift makeLift(com.mcpirates.airship.Airship airship) {
        return new HotAirBalloonLift(airship.slThrottleLevers, airship.slBurnerPositions);
    }

    /** CBC cannon mounts to assemble; may be empty. */
    List<BlockPos> cannonMountDeltas();

    /** Inclusive min corner of the honey-glue body box. Trigger inflates max sides by +1
     *  so AABB.contains covers inclusive block coords. */
    BlockPos glueMin();

    BlockPos glueMax();

    /** Where assembly BFS seeds from. Default: the block the lever is mounted on. */
    default BlockPos seedDelta(Direction leverConnectedDir) {
        Direction into = leverConnectedDir.getOpposite();
        return new BlockPos(into.getStepX(), into.getStepY(), into.getStepZ());
    }

    // ───────────── combat & movement ─────────────

    /** Combat strategy; the brain calls aim/fire during PURSUE. */
    CombatBehavior combat();

    /** PURSUE-time steering. Default: orbit at {@link #orbitRadius()}. */
    default MovementBehavior movement() { return OrbitMovement.INSTANCE; }

    /** Stand-off distance during PURSUE. */
    default double orbitRadius() { return 25.0; }

    /** Ground defenders that spawn when an on-foot player approaches a dormant ship.
     *  Default empty — the ship is invincible from the ground. */
    default Optional<GroundCombatModule> groundCombat() { return Optional.empty(); }

    /** Rise above spawn altitude required to exit LIFTOFF. High-lift kinds override up. */
    default double liftoffMinRise() {
        return com.mcpirates.airship.AirshipStateMachine.LIFTOFF_MIN_RISE;
    }

    /** Target altitude above {@code airpadAnchor.y} for LIFTOFF / HOVER / RETURN. */
    default double cruiseRise() { return 60.0; }

    /** Vertical offset above target during PURSUE. Ramships set 0; ranged ships keep clearance. */
    default double pursueAltOffset() { return 12.0; }

    /** Safety floor above max terrain ahead. Target altitude is at least
     *  {@code maxGroundAhead + this + 2} in non-PURSUE; in PURSUE it lower-bounds the
     *  target-eye + offset. Ramships override low so they can actually connect. */
    default double minAltAboveGround() { return 30.0; }
}
