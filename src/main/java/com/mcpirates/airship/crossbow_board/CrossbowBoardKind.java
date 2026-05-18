package com.mcpirates.airship.crossbow_board;

import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.AnchorNbtPositions;
import com.mcpirates.airship.kind.CombatBehavior;
import com.mcpirates.airship.kind.ThrottleLevers;
import com.mcpirates.airship.kind.NoCannonCombat;
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
    @Override public Direction nbtPrimaryFacing() { return Direction.SOUTH; }
    @Override public Direction nbtForward() { return Direction.NORTH; }
    @Override public ThrottleLevers.Kind throttleLeverKind() { return ThrottleLevers.Kind.CREATE_ANALOG; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof AnalogLeverBlockEntity;
    }

    @Override
    public BlockPos anchorToLeverDelta() {
        // Lever sits one block NBT-south (+Z) of the anchor. Anchor coords in AnchorNbtPositions.
        return new BlockPos(0, 0, +1);
    }

    // NBT-frame deltas from the left analog lever (two engines, right throttle, two
    // vanilla clutch levers on top of create:clutch blocks).
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

    // Hull spans NBT (0..5, 0..7, 1..15). Deltas are LEVER-relative — spawnHoneyGlue adds
    // these to the lever pos (NOT the anchor pos; see anchorToLeverDelta). Earlier
    // values were anchor-relative, which shifted the glue bbox +1 in Z and left the
    // keel layer un-stuck — visible as the assembled ship "missing" a row at the bow
    // and grabbing an air column at the stern.
    @Override public BlockPos glueMin() { return new BlockPos(-2, -3, -8); }
    @Override public BlockPos glueMax() { return new BlockPos(+3, +4, +6); }

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
