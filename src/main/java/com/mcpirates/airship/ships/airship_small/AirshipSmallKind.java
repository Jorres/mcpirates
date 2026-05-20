package com.mcpirates.airship.ships.airship_small;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.common.TankSteerControls;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.interfaces.ShipControls;
import com.mcpirates.airship.interfaces.CombatBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.List;

/**
 * Original pirate ship (NBT {@code airship_small}): one envelope, one cannon at the bow,
 * one Create analog lever burner, two propeller clutches.
 *
 * <p>All deltas are taken relative to the analog lever (face=ceiling, facing=EAST),
 * which is the trigger anchor. Layout data in {@link AirshipSmallNbtSpec}.
 */
public final class AirshipSmallKind implements AirshipKind {

    public static final AirshipSmallKind INSTANCE = new AirshipSmallKind();

    private final CombatBehavior combat = new SingleFrontCannonCombat();

    private AirshipSmallKind() {}

    @Override public String name() { return AirshipSmallNbtSpec.INSTANCE.shipId(); }
    @Override public AirshipSmallNbtSpec nbtSpec() { return AirshipSmallNbtSpec.INSTANCE; }

    // NBT-frame deltas. Propeller/anchor data lives in AirshipSmallNbtSpec (source of truth
    // for the nbtcheck JUnit tests). Other deltas stay private here.
    private static final BlockPos ANCHOR_TO_LEVER = arr(AirshipSmallNbtSpec.INSTANCE.anchorToLever());
    private static final BlockPos ENGINE          = new BlockPos(0, -1, 0);
    private static final BlockPos THROTTLE        = BlockPos.ZERO;
    private static final BlockPos LEFT_CLUTCH     = new BlockPos(-2, -1, 2);
    private static final BlockPos RIGHT_CLUTCH    = new BlockPos(+2, -1, 2);
    private static final BlockPos CANNON_MOUNT    = new BlockPos(0, -3, -4);

    // Honey-glue body bounds (LEVER-relative, inclusive). MUST enclose every occupied cell
    // of airship_small.nbt — the SimAssemblyContraption BFS only crosses block-to-block if
    // either canStick is true OR both cells lie inside the SAME honey-glue entity. A block
    // left outside glue and not canStick-connected (e.g. an oak_slab decoration on top of
    // an envelope) stays in the WORLD after assembly; the SubLevel's rigid body then
    // collides with that leftover world block when it tries to rise — visible to the
    // player as "ship is buoyant but won't lift off".
    //
    // NBT occupied cells: x=0..6, y=0..8, z=0..11 (lever at (3,3,6) in NBT).
    // Lever-relative: min=(-3,-3,-6); max=(+3,+5,+5).
    private static final BlockPos GLUE_MIN        = new BlockPos(-3, -3, -6);
    private static final BlockPos GLUE_MAX        = new BlockPos(+3, +5, +5);

    @Override
    public BlockPos leverFromAnchor(Rotation r, BlockPos anchorWorld) {
        return anchorWorld.offset(ANCHOR_TO_LEVER.rotate(r));
    }

    @Override
    public Layout layoutAt(Rotation r, BlockPos leverRef) {
        return new Layout(
                List.of(leverRef.offset(ENGINE.rotate(r))),
                List.of(leverRef.offset(THROTTLE.rotate(r))),
                leverRef.offset(LEFT_CLUTCH.rotate(r)),
                leverRef.offset(RIGHT_CLUTCH.rotate(r)),
                List.of(leverRef.offset(CANNON_MOUNT.rotate(r))),
                leverRef.offset(GLUE_MIN.rotate(r)),
                leverRef.offset(GLUE_MAX.rotate(r)));
    }

    @Override
    public ShipControls makeControls(Airship airship,
                                     BlockPos slLeftClutchLever,
                                     BlockPos slRightClutchLever,
                                     BlockPos slPrimaryAnchor,
                                     Rotation rotation) {
        // slPrimaryAnchor is the LEVER's SL position (see AirshipLiftoffTrigger:
        // slPrimaryAnchorPos = pos.offset(offset), where pos is the world lever).
        AirshipSmallNbtSpec spec = AirshipSmallNbtSpec.INSTANCE;
        return new TankSteerControls(
                slLeftClutchLever, slRightClutchLever,
                rotateOffsets(spec.leftPropellersLeverRel(), slPrimaryAnchor, rotation),
                rotateOffsets(spec.rightPropellersLeverRel(), slPrimaryAnchor, rotation),
                spec.nbtReversedL(), spec.nbtReversedR());
    }

    private static BlockPos arr(int[] a) { return new BlockPos(a[0], a[1], a[2]); }
    private static List<BlockPos> rotateOffsets(int[][] deltas, BlockPos base, Rotation r) {
        return java.util.Arrays.stream(deltas).map(d -> base.offset(arr(d).rotate(r))).toList();
    }

    @Override public CombatBehavior combat() { return combat; }
}
