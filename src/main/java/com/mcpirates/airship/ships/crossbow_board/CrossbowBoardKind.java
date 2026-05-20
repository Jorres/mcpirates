package com.mcpirates.airship.ships.crossbow_board;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.common.TankSteerControls;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.interfaces.ShipControls;
import com.mcpirates.airship.interfaces.CombatBehavior;
import com.mcpirates.airship.common.NoCannonCombat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.List;

/**
 * Light recon ship (NBT {@code crossbow_board}, size 6×8×16). Symmetric twin-burner
 * platform with no cannon — currently a flying observation post. Two Create analog
 * levers side-by-side, each gating its own portable engine.
 *
 * <p>Primary anchor: the left lever (face=ceiling, facing=SOUTH). Layout data in
 * {@link CrossbowBoardNbtSpec}; all in-class deltas are lever-relative.
 *
 * <p>Combat is currently {@link NoCannonCombat} — placeholder until a turret-crossbow
 * block exists.
 */
public final class CrossbowBoardKind implements AirshipKind {

    public static final CrossbowBoardKind INSTANCE = new CrossbowBoardKind();

    private final CombatBehavior combat = new NoCannonCombat();

    private CrossbowBoardKind() {}

    @Override public String name() { return CrossbowBoardNbtSpec.INSTANCE.shipId(); }
    @Override public CrossbowBoardNbtSpec nbtSpec() { return CrossbowBoardNbtSpec.INSTANCE; }

    // NBT-frame deltas. Propeller/anchor data lives in CrossbowBoardNbtSpec.
    private static final BlockPos ANCHOR_TO_LEVER = arr(CrossbowBoardNbtSpec.INSTANCE.anchorToLever());
    private static final List<BlockPos> ENGINES = List.of(
            new BlockPos(-1, -1, -3),
            new BlockPos(+2, -1, -3));
    private static final List<BlockPos> THROTTLES = List.of(BlockPos.ZERO, new BlockPos(+1, 0, 0));
    private static final BlockPos LEFT_CLUTCH  = new BlockPos(-1, 0, -4);
    private static final BlockPos RIGHT_CLUTCH = new BlockPos(+2, 0, -4);

    // Hull spans NBT (0..5, 0..7, 1..15). Deltas are LEVER-relative; an earlier
    // anchor-relative version shifted the glue bbox +1 in Z and left the keel layer
    // un-stuck — visible as the assembled ship "missing" a row at the bow and grabbing
    // an air column at the stern.
    private static final BlockPos GLUE_MIN = new BlockPos(-2, -3, -8);
    private static final BlockPos GLUE_MAX = new BlockPos(+3, +4, +6);

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

    @Override
    public ShipControls makeControls(Airship airship,
                                     BlockPos slLeftClutchLever,
                                     BlockPos slRightClutchLever,
                                     BlockPos slPrimaryAnchor,
                                     Rotation rotation) {
        CrossbowBoardNbtSpec spec = CrossbowBoardNbtSpec.INSTANCE;
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

    /** 30-block stand-off. Crew crossbows reach 40 blocks, so orbiting at 30 keeps the
     *  ship well inside firing range without flying *into* the target. Brain reads this
     *  for the orbit goal during PURSUE. */
    @Override public double orbitRadius() { return 30.0; }
}
