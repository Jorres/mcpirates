package com.mcpirates.airship.kind;

import com.mcpirates.pirates.GroundCombatModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Per-design constants for one kind of pirate airship. The {@link com.mcpirates.airship.AirshipBrain}
 * handles flight envelope behaviour (LIFTOFF / PURSUE / RETURN / HOVER, orbit math, throttle PD,
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

    /** Current 0..15 state of this anchor BE. Trigger uses this to skip already-activated ships. */
    int readAnchorState(BlockEntity be);

    /** State to set on liftoff (caller compares {@code readAnchorState(be) >= activatedAt()}
     *  to detect "already triggered"). 10 is the canonical burner-on level — high enough
     *  to lift, low enough that the brain can dial it down further during HOVER. */
    default int activatedAt() { return 10; }

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

    /** Vanilla {@code minecraft:lever} that the brain flips to engage/disengage the
     *  port-side propeller clutch. */
    BlockPos leftClutchLeverDelta();

    /** Vanilla {@code minecraft:lever} that the brain flips to engage/disengage the
     *  starboard-side propeller clutch. */
    BlockPos rightClutchLeverDelta();

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

    // ───────────── combat ─────────────

    /** Combat strategy. {@link com.mcpirates.airship.AirshipBrain} calls
     *  {@link CombatBehavior#aim} every aim tick and
     *  {@link CombatBehavior#fire} every fire tick during PURSUE. */
    CombatBehavior combat();

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
}
