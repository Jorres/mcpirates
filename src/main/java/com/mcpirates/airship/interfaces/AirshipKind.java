package com.mcpirates.airship.interfaces;

import com.mcpirates.airship.anchor.MCPShipAnchorBlock;
import com.mcpirates.airship.common.HotAirBalloonLift;
import com.mcpirates.airship.common.OrbitMovement;
import com.mcpirates.pirates.GroundCombatModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * Per-design constants for one kind of pirate airship. {@link com.mcpirates.airship.AirshipBrain}
 * runs flight behaviour generically; this interface exposes only resolved positions and
 * strategies — the underlying NBT-frame deltas are each kind's private business.
 *
 * <p>Ships are identified by a hidden {@link MCPShipAnchorBlock} that stores the kind name
 * via its BE. The placement rotation is recovered directly from the anchor's {@code FACING}
 * block-state property — see {@link #detectRotation}. Once rotation is known,
 * {@link #layoutAt} returns every resolved hardware position relative to a primary-lever
 * reference — in either world frame (pre-assembly) or SubLevel frame (post-assembly),
 * depending on what the caller passes as {@code leverRef}.
 */
public interface AirshipKind {

    String name();

    /** NBT-frame layout declaration — source of truth for prop/anchor positions and the
     *  NBT-baked REVERSED defaults. Lives in {@code <Ship>NbtSpec.INSTANCE} inside each
     *  ship folder. nbtcheck tests iterate {@code AirshipKinds.ALL} and pull each kind's
     *  spec, so adding a new ship needs no central registry edit beyond AirshipKinds.ALL. */
    com.mcpirates.airship.ships.ShipNbtSpec nbtSpec();

    // ───────────── identification ─────────────

    /** World-frame position of the primary lever given the metadata anchor block's world
     *  pos and the assembly rotation. */
    BlockPos leverFromAnchor(Rotation r, BlockPos anchorWorld);

    /** Recover the placement rotation from the anchor block's FACING property. Returns empty
     *  if the block at {@code anchorWorld} isn't an anchor (chunk not primed, or it's been
     *  griefed). Independent of the ship's bow direction — see
     *  {@link MCPShipAnchorBlock}'s docs on FACING. */
    default Optional<Rotation> detectRotation(Level level, BlockPos anchorWorld) {
        BlockState s = level.getBlockState(anchorWorld);
        if (!(s.getBlock() instanceof MCPShipAnchorBlock)) return Optional.empty();
        Direction worldFacing = s.getValue(MCPShipAnchorBlock.FACING);
        for (Rotation r : Rotation.values()) {
            if (r.rotate(Direction.NORTH) == worldFacing) return Optional.of(r);
        }
        return Optional.empty();  // unreachable: 4 rotations cover all 4 horizontals
    }

    /** Resolved positions relative to a primary-lever reference. {@code leverRef} can be in
     *  either frame (world pre-assembly, SL post-assembly); output is in the same frame. */
    Layout layoutAt(Rotation r, BlockPos leverRef);

    // ───────────── actuator factories ─────────────

    /** Build steering controls bound to this assembly's resolved hardware positions.
     *  Kinds construct a {@link TankSteerControls} (or kind-specific subtype) with the
     *  outboard prop positions + NBT-default REVERSED for each side; positions are
     *  rotated/offset from {@code slPrimaryAnchor} using the kind's private deltas. */
    ShipControls makeControls(com.mcpirates.airship.Airship airship,
                              BlockPos slLeftClutchLever,
                              BlockPos slRightClutchLever,
                              BlockPos slPrimaryAnchor,
                              Rotation rotation);

    /** Build lift actuator bound to this assembly's burners + throttle levers.
     *  Default: hot-air balloon lift across every throttle/burner pair. */
    default ShipLift makeLift(List<BlockPos> slThrottleLevers,
                              List<BlockPos> slBurnerPositions) {
        return new HotAirBalloonLift(slThrottleLevers, slBurnerPositions);
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

    /** Safety floor above max terrain ahead. Target altitude is at least
     *  {@code maxGroundAhead + this + 2} in non-PURSUE; in PURSUE it lower-bounds the
     *  target-eye + offset. Ramships override low so they can actually connect. */
    default double minAltAboveGround() { return 30.0; }
}
