package com.mcpirates.airship.kind;

import com.mcpirates.pirates.GroundCombatModule;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Light recon ship (NBT {@code crossbow_board}, size 6×8×16). Symmetric twin-burner
 * platform with no cannon — currently a flying observation post. Two Create analog
 * levers side-by-side at NBT (2,3,9) and (3,3,9), each gating its own portable engine.
 *
 * <p>Primary anchor: the left lever at NBT (2,3,9), face=ceiling, facing=SOUTH. All
 * deltas are taken from that pos.
 *
 * <p>Combat is currently {@link NoCannonCombat} — placeholder until a turret-crossbow
 * block exists.
 */
public final class CrossbowBoardKind implements AirshipKind {

    public static final CrossbowBoardKind INSTANCE = new CrossbowBoardKind();

    private final CombatBehavior combat = new NoCannonCombat();

    private CrossbowBoardKind() {}

    @Override public String name() { return "crossbow_board"; }
    @Override public Direction nbtPrimaryFacing() { return Direction.SOUTH; }
    @Override public Direction nbtForward() { return Direction.NORTH; }
    @Override public LeverKind throttleLeverKind() { return LeverKind.CREATE_ANALOG; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof AnalogLeverBlockEntity;
    }

    @Override
    public BlockPos anchorToLeverDelta() {
        // anchor at source (2, 3, 8) — air cell NBT-north of the left primary lever
        // at source (2, 3, 9). Delta = (0, 0, +1).
        return new BlockPos(0, 0, +1);
    }

    // NBT-frame deltas from the left analog lever at (2,3,9):
    //   engines (1,2,6),(4,2,6) → (-1,-1,-3),(+2,-1,-3)
    //   right throttle (3,3,9)  → (+1,0,0)
    //   left clutch  (1,3,5)    → (-1,0,-4)   — vanilla lever on top of create:clutch
    //   right clutch (4,3,5)    → (+2,0,-4)
    @Override public List<BlockPos> engineDeltas() {
        return List.of(
                new BlockPos(-1, -1, -3),
                new BlockPos(+2, -1, -3));
    }
    @Override public List<BlockPos> throttleLeverDeltas() {
        return List.of(BlockPos.ZERO, new BlockPos(+1, 0, 0));
    }
    @Override public BlockPos leftClutchLeverDelta() { return new BlockPos(-1, 0, -4); }
    @Override public BlockPos rightClutchLeverDelta() { return new BlockPos(+2, 0, -4); }
    @Override public List<BlockPos> cannonMountDeltas() {
        return List.of();
    }

    // Hull spans NBT (0..5, 0..7, 1..15). Anchor at (2,3,8) so deltas:
    //   min (0-2, 0-3, 1-8) = (-2,-3,-7)
    //   max (5-2, 7-3, 15-8) = (+3,+4,+7)
    // The previous z_max of +6 left the z=15 column outside the glue — those cells
    // could remain in the world after assembly and physically block lift-off (see
    // AirshipSmallKind.glueMin doc for the BFS reasoning).
    @Override public BlockPos glueMin() { return new BlockPos(-2, -3, -7); }
    @Override public BlockPos glueMax() { return new BlockPos(+3, +4, +7); }

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
