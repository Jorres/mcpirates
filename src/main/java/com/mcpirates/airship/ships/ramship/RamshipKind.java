package com.mcpirates.airship.ships.ramship;

import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.ships.AnchorNbtPositions;
import com.mcpirates.airship.interfaces.CombatBehavior;
import com.mcpirates.airship.interfaces.MovementBehavior;
import com.mcpirates.airship.common.NoCannonCombat;
import com.mcpirates.pirates.GroundCombatModule;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
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
    @Override public Direction nbtForward() { return Direction.NORTH; }

    /** Ramming low-altitude targets requires giving up most of the ground-clearance
     *  safety margin — a 30-block floor (the default) would prevent the ramship from
     *  descending toward a victim flying anywhere near treetop level. 5 is enough to
     *  not catch hills mid-chase while still letting the ram land. */
    @Override public double minAltAboveGround() { return 5.0; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof AnalogLeverBlockEntity;
    }

    // NBT-frame deltas from the left analog lever — private impl detail. Lever sits one
    // block NBT-south of the anchor. Ramship has one engine (driving all three props), two
    // throttles (left + right), three vanilla clutch levers (port/starboard/forward), three
    // propellers. FORWARD_PROPELLER is diagnostic only — RamControls doesn't reverse it; the
    // forward clutch lever gates its drive.
    private static final BlockPos ANCHOR_TO_LEVER     = new BlockPos(0, 0, +1);
    private static final List<BlockPos> ENGINES      = List.of(new BlockPos(0, -1, +4));
    private static final List<BlockPos> THROTTLES    = List.of(BlockPos.ZERO, new BlockPos(+2, 0, 0));
    private static final BlockPos LEFT_CLUTCH        = new BlockPos(-1, 0, +9);
    private static final BlockPos RIGHT_CLUTCH       = new BlockPos(+3, 0, +9);
    private static final BlockPos FORWARD_CLUTCH     = new BlockPos(+1, -1, +9);
    private static final BlockPos LEFT_PROPELLER     = new BlockPos(-1, -1, +10);
    private static final BlockPos RIGHT_PROPELLER    = new BlockPos(+3, -1, +10);
    private static final BlockPos FORWARD_PROPELLER  = new BlockPos(+1, -2, +12);
    private static final BlockPos GLUE_MIN           = new BlockPos(-3, -3, -9);
    private static final BlockPos GLUE_MAX           = new BlockPos(+5, +6, +12);

    /** ramship.nbt has a single propeller palette entry: {@code reversed=true} on all
     *  three. Hardcoded so rehydrate after a PURSUE-time mutation still has the truth. */
    private static final boolean NBT_REVERSED_L = true;
    private static final boolean NBT_REVERSED_R = true;

    @Override
    public com.mcpirates.airship.interfaces.ShipControls makeControls(
            com.mcpirates.airship.Airship airship,
            net.minecraft.core.BlockPos slLeftClutchLever,
            net.minecraft.core.BlockPos slRightClutchLever,
            net.minecraft.core.BlockPos slPrimaryAnchor,
            net.minecraft.world.level.block.Rotation rotation) {
        return new RamControls(
                slLeftClutchLever,
                slRightClutchLever,
                slPrimaryAnchor.offset(FORWARD_CLUTCH.rotate(rotation)),
                slPrimaryAnchor.offset(LEFT_PROPELLER.rotate(rotation)),
                slPrimaryAnchor.offset(RIGHT_PROPELLER.rotate(rotation)),
                slPrimaryAnchor.offset(FORWARD_PROPELLER.rotate(rotation)),
                NBT_REVERSED_L,
                NBT_REVERSED_R);
    }

    @Override
    public BlockPos leverFromAnchor(Rotation r, BlockPos anchorWorld) {
        return anchorWorld.offset(ANCHOR_TO_LEVER.rotate(r));
    }

    @Override
    public Layout layoutAt(Rotation r, BlockPos leverRef) {
        return new Layout(
                ENGINES.stream().map(d -> leverRef.offset(d.rotate(r))).toList(),
                THROTTLES.stream().map(d -> leverRef.offset(d.rotate(r))).toList(),
                leverRef.offset(LEFT_CLUTCH.rotate(r)),
                leverRef.offset(RIGHT_CLUTCH.rotate(r)),
                List.of(),
                leverRef.offset(GLUE_MIN.rotate(r)),
                leverRef.offset(GLUE_MAX.rotate(r)));
    }

    @Override public CombatBehavior combat() { return combat; }
    @Override public MovementBehavior movement() { return RamMovement.INSTANCE; }

    @Override
    public Optional<GroundCombatModule> groundCombat() {
        return Optional.of(GroundCombatModule.SHARED);
    }
}
