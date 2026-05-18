package com.mcpirates.airship.ramship;

import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.AnchorNbtPositions;
import com.mcpirates.airship.kind.CombatBehavior;
import com.mcpirates.airship.kind.ThrottleLevers;
import com.mcpirates.airship.kind.MovementBehavior;
import com.mcpirates.airship.kind.NoCannonCombat;
import com.mcpirates.pirates.GroundCombatModule;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Ramming attacker (NBT {@code ramship}, hull 9×10×22). Two outboard propellers driven
 * by the standard left/right clutches steer; a third center propeller — aligned on the
 * movement axis — provides constant forward thrust during PURSUE via {@link #forwardClutchLeverDelta}.
 *
 * <p>Primary anchor: the left Create analog lever (face=ceiling, facing=NORTH).
 * Absolute coords in {@link AnchorNbtPositions}. Ship-forward is NORTH
 * (props face SOUTH, push NORTH).
 *
 * <p>Combat: none — the ramship's payload is its hull, not cannons.
 * Movement: {@link RamMovement} (constant-bearing intercept).
 */
public final class RamshipKind implements AirshipKind {

    public static final RamshipKind INSTANCE = new RamshipKind();

    private final CombatBehavior combat = new NoCannonCombat();

    private RamshipKind() {}

    @Override public String name() { return "ramship"; }
    @Override public Direction nbtPrimaryFacing() { return Direction.NORTH; }
    @Override public Direction nbtForward() { return Direction.NORTH; }
    @Override public ThrottleLevers.Kind throttleLeverKind() { return ThrottleLevers.Kind.CREATE_ANALOG; }

    /** Ramming low-altitude targets requires giving up most of the ground-clearance
     *  safety margin — a 30-block floor (the default) would prevent the ramship from
     *  descending toward a victim flying anywhere near treetop level. 5 is enough to
     *  not catch hills mid-chase while still letting the ram land. */
    @Override public double minAltAboveGround() { return 5.0; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof AnalogLeverBlockEntity;
    }

    @Override
    public BlockPos anchorToLeverDelta() {
        // Lever sits one block NBT-south (+Z) of the anchor. Anchor coords in AnchorNbtPositions.
        return new BlockPos(0, 0, +1);
    }

    // NBT-frame deltas from the left analog lever (right throttle, sole portable engine
    // driving all three props, three vanilla clutch levers — port/starboard/forward).
    @Override public List<BlockPos> engineDeltas() {
        return List.of(new BlockPos(0, -1, +4));
    }
    @Override public List<BlockPos> throttleLeverDeltas() {
        return List.of(BlockPos.ZERO, new BlockPos(+2, 0, 0));
    }
    @Override public BlockPos leftClutchLeverDelta() { return new BlockPos(-1, 0, +9); }
    @Override public BlockPos rightClutchLeverDelta() { return new BlockPos(+3, 0, +9); }
    @Override public List<BlockPos> cannonMountDeltas() { return List.of(); }

    // Hardware deltas the ramship needs *beyond* the two outboard clutch levers
    // every kind has. Kept private (this is implementation detail) — they're
    // only consumed by {@link #makeControls} below, never exposed to the brain.
    // All deltas are anchor-relative (see anchorToLeverDelta + AnchorNbtPositions).
    // FORWARD_PROPELLER_DELTA is diagnostic only — RamControls doesn't reverse it;
    // the forward clutch lever above gates its drive.
    private static final BlockPos FORWARD_CLUTCH_LEVER_DELTA = new BlockPos(+1, -1, +9);
    private static final BlockPos LEFT_PROPELLER_DELTA       = new BlockPos(-1, -1, +10);
    private static final BlockPos RIGHT_PROPELLER_DELTA      = new BlockPos(+3, -1, +10);
    private static final BlockPos FORWARD_PROPELLER_DELTA    = new BlockPos(+1, -2, +12);

    @Override
    public com.mcpirates.airship.kind.ShipControls makeControls(
            com.mcpirates.airship.Airship airship,
            net.minecraft.core.BlockPos slLeftClutchLever,
            net.minecraft.core.BlockPos slRightClutchLever,
            net.minecraft.core.BlockPos slPrimaryAnchor,
            net.minecraft.world.level.block.Rotation rotation) {
        return new RamControls(
                airship.subLevel.getLevel(),
                slLeftClutchLever,
                slRightClutchLever,
                slPrimaryAnchor.offset(FORWARD_CLUTCH_LEVER_DELTA.rotate(rotation)),
                slPrimaryAnchor.offset(LEFT_PROPELLER_DELTA.rotate(rotation)),
                slPrimaryAnchor.offset(RIGHT_PROPELLER_DELTA.rotate(rotation)),
                slPrimaryAnchor.offset(FORWARD_PROPELLER_DELTA.rotate(rotation)));
    }

    // Glue bbox covering the full hull, expressed as lever-relative min/max
    // (spawnHoneyGlue offsets from lever, not anchor — see AirshipSmallKind for the
    // BFS reasoning).
    @Override public BlockPos glueMin() { return new BlockPos(-3, -3, -9); }
    @Override public BlockPos glueMax() { return new BlockPos(+5, +6, +12); }

    @Override public CombatBehavior combat() { return combat; }
    @Override public MovementBehavior movement() { return RamMovement.INSTANCE; }

    @Override
    public Optional<GroundCombatModule> groundCombat() {
        return Optional.of(GroundCombatModule.SHARED);
    }
}
