package com.mcpirates.airship.ships.ramship;

import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.interfaces.CombatBehavior;
import com.mcpirates.airship.interfaces.MovementBehavior;
import com.mcpirates.airship.common.NoCannonCombat;
import com.mcpirates.pirates.GroundCombatModule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
import java.util.Optional;

/**
 * Ramming attacker (NBT {@code ramship}, hull 9×10×22). Two outboard propellers driven
 * by the standard left/right clutches steer; a third center propeller — aligned on the
 * movement axis — provides constant forward thrust during PURSUE via a dedicated forward
 * clutch lever (see {@code FORWARD_CLUTCH} below).
 *
 * <p>Primary anchor: the left Create analog lever (face=ceiling, facing=NORTH).
 * Layout data (anchor pos, prop deltas, NBT defaults) in {@link RamshipNbtSpec}.
 * Ship-forward is NORTH (props face SOUTH, push NORTH).
 *
 * <p>Combat: none — the ramship's payload is its hull, not cannons.
 * Movement: {@link RamMovement} (constant-bearing intercept).
 */
public final class RamshipKind implements AirshipKind {

    public static final RamshipKind INSTANCE = new RamshipKind();

    private final CombatBehavior combat = new NoCannonCombat();

    private RamshipKind() {}

    @Override public String name() { return RamshipNbtSpec.INSTANCE.shipId(); }
    @Override public RamshipNbtSpec nbtSpec() { return RamshipNbtSpec.INSTANCE; }

    /** Ramming low-altitude targets requires giving up most of the ground-clearance
     *  safety margin — a 30-block floor (the default) would prevent the ramship from
     *  descending toward a victim flying anywhere near treetop level. 5 is enough to
     *  not catch hills mid-chase while still letting the ram land. */
    @Override public double minAltAboveGround() { return 5.0; }

    // NBT-frame deltas from the left analog lever — private impl detail. Lever sits one
    // block NBT-south of the anchor. Ramship has one engine (driving all three props), two
    // throttles (left + right), three vanilla clutch levers (port/starboard/forward), three
    // propellers. RamControls flips FORWARD_PROPELLER's REVERSED during the retreat phase
    // (push backward); the forward clutch always gates its drive.
    // Anchor / prop / NBT-default data lives in RamshipNbtSpec (source of truth for
    // nbtcheck JUnit tests). Other deltas (engines, throttles, clutches, glue) are
    // private impl detail and stay here.
    private static final BlockPos ANCHOR_TO_LEVER     = arr(RamshipNbtSpec.INSTANCE.anchorToLever());
    private static final List<BlockPos> ENGINES      = List.of(new BlockPos(0, -1, +4));
    private static final List<BlockPos> THROTTLES    = List.of(BlockPos.ZERO, new BlockPos(+2, 0, 0));
    private static final BlockPos LEFT_CLUTCH        = new BlockPos(-1, 0, +9);
    private static final BlockPos RIGHT_CLUTCH       = new BlockPos(+3, 0, +9);
    private static final BlockPos FORWARD_CLUTCH     = new BlockPos(+1, -1, +9);
    private static final BlockPos GLUE_MIN           = new BlockPos(-3, -3, -9);
    private static final BlockPos GLUE_MAX           = new BlockPos(+5, +6, +12);

    private static BlockPos arr(int[] a) { return new BlockPos(a[0], a[1], a[2]); }

    @Override
    public com.mcpirates.airship.interfaces.ShipControls makeControls(
            com.mcpirates.airship.Airship airship,
            net.minecraft.core.BlockPos slLeftClutchLever,
            net.minecraft.core.BlockPos slRightClutchLever,
            net.minecraft.core.BlockPos slPrimaryAnchor,
            net.minecraft.world.level.block.Rotation rotation) {
        RamshipNbtSpec spec = RamshipNbtSpec.INSTANCE;
        return new RamControls(
                slLeftClutchLever,
                slRightClutchLever,
                slPrimaryAnchor.offset(FORWARD_CLUTCH.rotate(rotation)),
                slPrimaryAnchor.offset(arr(spec.leftPropellersLeverRel()[0]).rotate(rotation)),
                slPrimaryAnchor.offset(arr(spec.rightPropellersLeverRel()[0]).rotate(rotation)),
                slPrimaryAnchor.offset(arr(spec.forwardPropellerLeverRel()).rotate(rotation)),
                spec.nbtReversedL(),
                spec.nbtReversedR(),
                spec.nbtReversedF());
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
