package com.mcpirates.airship.ships.crossbow_board;

import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.ships.AnchorNbtPositions;
import com.mcpirates.airship.interfaces.CombatBehavior;
import com.mcpirates.airship.common.NoCannonCombat;
import com.mcpirates.pirates.GroundCombatModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
import java.util.Optional;

/**
 * Light recon ship (NBT {@code crossbow_board}, size 6×8×16). Symmetric twin-burner
 * platform with no cannon — currently a flying observation post. Two Create analog
 * levers side-by-side, each gating its own portable engine.
 *
 * <p>Primary anchor: the left lever (face=ceiling, facing=SOUTH). Absolute coords in
 * {@link AnchorNbtPositions}; all in-class deltas are anchor-relative.
 *
 * <p>Combat is currently {@link NoCannonCombat} — placeholder until a turret-crossbow
 * block exists.
 */
public final class CrossbowBoardKind implements AirshipKind {

    public static final CrossbowBoardKind INSTANCE = new CrossbowBoardKind();

    private final CombatBehavior combat = new NoCannonCombat();

    private CrossbowBoardKind() {}

    @Override public String name() { return "crossbow_board"; }
    @Override public Direction nbtForward() { return Direction.NORTH; }

    // NBT-frame deltas — private impl detail. Lever sits one block NBT-south of the anchor.
    private static final BlockPos ANCHOR_TO_LEVER = new BlockPos(0, 0, +1);
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

    @Override public CombatBehavior combat() { return combat; }

    /** 30-block stand-off. Crew crossbows reach 40 blocks, so orbiting at 30 keeps the
     *  ship well inside firing range without flying *into* the target. Brain reads this
     *  for the orbit goal during PURSUE. */
    @Override public double orbitRadius() { return 30.0; }

    @Override
    public Optional<GroundCombatModule> groundCombat() {
        return Optional.of(GroundCombatModule.SHARED);
    }
}
