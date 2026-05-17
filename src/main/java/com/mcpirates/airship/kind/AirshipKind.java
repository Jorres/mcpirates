package com.mcpirates.airship.kind;

import com.mcpirates.pirates.GroundCombatModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Per-design constants for one kind of pirate airship. The {@link com.mcpirates.airship.AirshipBrain}
 * handles flight envelope behaviour (LIFTOFF / PURSUE / RETURN / HOVER, orbit math, plateau-based altitude control,
 * tank-steer logic) generically; everything that depends on <em>where the levers are</em> or
 * <em>which cannons exist</em> lives here.
 *
 * <h2>Coordinate convention</h2>
 *
 * All positional methods return <strong>NBT-frame deltas</strong> from this kind's "primary
 * anchor" — the throttle lever the brain treats as the ship's identity. Worldgen rotates the
 * structure 0/90/180/270°; the trigger detects the rotation by comparing the anchor's world
 * FACING to {@link #nbtPrimaryFacing()} and then rotates every delta by the same amount
 * before adding world / SubLevel offsets.
 *
 * <h2>Identification — metadata block, not geometric guessing</h2>
 *
 * Each ship NBT carries one {@link com.mcpirates.airship.anchor.MCPShipAnchorBlock} placed at
 * a hidden position inside the hull, whose BE stores the kind name. The lift-off trigger
 * scans chunks for those anchor BEs and looks up the kind via
 * {@link AirshipKinds#byName(String)} — no heuristic {@code matches()} predicate to maintain,
 * no risk of one ship's lever masquerading as another ship's primary anchor. From the
 * anchor's world position the trigger derives the primary lever via
 * {@link #anchorToLeverDelta()}; the rest of the deltas below are still lever-relative.
 */
public interface AirshipKind {

    String name();

    // ───────────── orientation / identification ─────────────

    /** NBT-frame FACING of the primary-anchor lever, used to derive rotation at trigger time. */
    Direction nbtPrimaryFacing();

    /** NBT-frame "ship forward" direction. For airship_small/galleon this is NORTH (the
     *  bow side where the cannon arc / main propulsion points). After {@code rotation},
     *  this becomes the world-frame forward we hand to the brain. */
    Direction nbtForward();

    /** Which 0..15 lever block the throttle uses. */
    LeverKind throttleLeverKind();

    /** Is this BE possibly a primary anchor for this kind? Cheap pre-filter the trigger
     *  uses when reading the actual lever pointed at by the anchor block. */
    boolean isPrimaryAnchorBE(BlockEntity be);

    /** NBT-frame delta from the ship's metadata anchor block (placed by build_ships.py)
     *  to the primary lever (this kind's identity for layout purposes). Must match the
     *  {@code anchor_nbt_pos} vs primary-lever-pos defined in the build_ships.py SHIPS
     *  config for this ship. */
    BlockPos anchorToLeverDelta();

    // ───────────── layout (NBT-frame deltas from the primary anchor) ─────────────

    /** Portable-engine positions to fuel at lift-off. May be 1 or 2. */
    List<BlockPos> engineDeltas();

    /** All throttle-equivalent levers (including the primary anchor at {@code (0,0,0)}).
     *  Two-burner kinds list both; the brain writes the same state to each so burners
     *  stay in lock-step. */
    List<BlockPos> throttleLeverDeltas();

    /** Vanilla {@code minecraft:lever} the port-side propeller clutch reads.
     *  Driven by the kind's {@link ShipControls}. */
    BlockPos leftClutchLeverDelta();

    /** Vanilla {@code minecraft:lever} the starboard-side propeller clutch reads.
     *  Driven by the kind's {@link ShipControls}. */
    BlockPos rightClutchLeverDelta();

    /** Construct steering controls for this assembly. The kind owns the NBT-frame
     *  deltas of every hardware block it interacts with (clutch levers, propellers,
     *  …); it resolves those deltas through {@code rotation} and the SubLevel
     *  offset, and hands the resulting {@link ShipControls} a set of fixed
     *  world-frame positions to drive. Nothing outside the kind needs to know
     *  what hardware is there — the brain only ever talks to the returned
     *  {@code ShipControls}.
     *
     *  <p>Default: {@link TankSteerControls} bound to the (already-resolved)
     *  left/right clutch levers carried on {@code airship}. Kinds with extra
     *  hardware (forward propeller, counter-rotatable side props) override and
     *  build a richer controls instance — see {@link RamshipKind}.
     *
     *  @param airship                the Airship just registered with the brain;
     *                                its {@code slLeftClutchLever}/{@code slRightClutchLever}
     *                                are already resolved.
     *  @param slPrimaryAnchor        SubLevel-frame position of the primary
     *                                anchor lever — the basis the kind's other
     *                                NBT deltas are measured from.
     *  @param rotation               rotation applied to the structure at placement.
     */
    default ShipControls makeControls(com.mcpirates.airship.Airship airship,
                                      BlockPos slPrimaryAnchor,
                                      net.minecraft.world.level.block.Rotation rotation) {
        return new TankSteerControls(airship.slLeftClutchLever, airship.slRightClutchLever);
    }

    /** CBC cannon-mount positions to assemble + register for the combat module. May be
     *  empty for cannonless ships. */
    List<BlockPos> cannonMountDeltas();

    /** Inclusive min corner of the honey-glue body bounding box (covers hull only, no
     *  surrounding air). The trigger inflates by +1 on max sides so AABB.contains
     *  covers the inclusive block coords. */
    BlockPos glueMin();

    /** Inclusive max corner of the honey-glue body bounding box. */
    BlockPos glueMax();

    /** Where the assembly BFS seeds from. Defaults to "one block in the
     *  {@link ThrottleLevers#leverConnectedDirection direction the lever attaches}"
     *  (i.e., the block the lever is mounted on) — that's part of the hull on every
     *  current design. Kinds with weird seeding can override. */
    default BlockPos seedDelta(Direction leverConnectedDir) {
        Direction into = leverConnectedDir.getOpposite();
        return new BlockPos(into.getStepX(), into.getStepY(), into.getStepZ());
    }

    // ───────────── combat & movement ─────────────

    /** Combat strategy. {@link com.mcpirates.airship.AirshipBrain} calls
     *  {@link CombatBehavior#aim} every aim tick and
     *  {@link CombatBehavior#fire} every fire tick during PURSUE. */
    CombatBehavior combat();

    /** PURSUE-time steering strategy. Default = orbit the player at {@link #orbitRadius()}. */
    default MovementBehavior movement() { return OrbitMovement.INSTANCE; }

    /** Horizontal distance at which the brain strafes the target during PURSUE. Brain-level
     *  knob — different ship roles want different stand-off distances (cannon ship close
     *  to the action, crossbow board outside crew firing arc + a buffer). Default suits
     *  cannon-armed ships; override per-kind when crew weapons or stand-off behaviour
     *  changes the optimal radius. */
    default double orbitRadius() { return 25.0; }

    /** Ground-side defenders that spawn next to the dormant ship when an on-foot player
     *  approaches. Default empty: the ship is invincible from the ground (player must
     *  arrive in the air). Early-game kinds override this to give the player a "prize
     *  fight" path. See {@link GroundCombatModule}. */
    default Optional<GroundCombatModule> groundCombat() { return Optional.empty(); }

    /** Blocks the ship must climb above its spawn altitude to exit LIFTOFF (alongside
     *  the steady-altitude and min-time gates). Default matches
     *  {@link com.mcpirates.airship.AirshipStateMachine#LIFTOFF_MIN_RISE}; high-lift
     *  kinds like the galleon override to a larger value so LIFTOFF doesn't exit
     *  while the ship is still ascending fast. */
    default double liftoffMinRise() {
        return com.mcpirates.airship.AirshipStateMachine.LIFTOFF_MIN_RISE;
    }

    /** Target altitude above {@code airpadAnchor.y} the brain aims for during LIFTOFF,
     *  HOVER, and RETURN. The plateau lookup converges to it; the steady-altitude exit
     *  path then concludes LIFTOFF without the ship climbing to its physical ceiling. */
    default double cruiseRise() { return 60.0; }

    /** Vertical offset above the target player the brain aims for during PURSUE.
     *  Default 12 blocks of clearance keeps the cannon arc / line-of-sight unobstructed
     *  for ranged ships. Ramships override to 0 — the ram is the whole interaction,
     *  and any vertical gap means it flies over the victim. */
    default double pursueAltOffset() { return 12.0; }

    /** Safety floor the altitude controller enforces above the highest terrain ahead
     *  (max of heightmap samples along the ship's forward axis). Applied uniformly to
     *  all states: in LIFTOFF / HOVER / RETURN / NAVIGATE the target altitude is at
     *  least {@code maxGroundAhead + minAltAboveGround() + 2}; in PURSUE the target is
     *  the larger of (target.eyeY + pursueAltOffset()) and that same ground floor.
     *
     *  <p>Default 30 — keeps the cannon arc clear of terrain for ranged ships, and
     *  prevents the ship from chasing a low-altitude target straight into a hill.
     *  Ramships override to a small value: their goal is to physically hit a
     *  same-altitude target, so any positive ground bias prevents the actual ram. */
    default double minAltAboveGround() { return 30.0; }
}
