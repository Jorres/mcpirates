package com.mcpirates.airship.kind;

import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * Original pirate ship (NBT {@code airship_small}): one envelope, one cannon at the bow,
 * one Create analog lever burner, two propeller clutches.
 *
 * <p>All deltas are taken relative to the analog lever, which sits at NBT (8,5,6) with
 * face=ceiling, facing=EAST. The lever is the trigger anchor.
 */
public final class AirshipSmallKind implements AirshipKind {

    public static final AirshipSmallKind INSTANCE = new AirshipSmallKind();

    private final CombatBehavior combat = new SingleFrontCannonCombat();

    private AirshipSmallKind() {}

    @Override public String name() { return "airship_small"; }
    @Override public Direction nbtPrimaryFacing() { return Direction.EAST; }
    @Override public Direction nbtForward() { return Direction.NORTH; }
    @Override public LeverKind throttleLeverKind() { return LeverKind.CREATE_ANALOG; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof AnalogLeverBlockEntity;
    }

    @Override
    public int readAnchorState(BlockEntity be) {
        return be instanceof AnalogLeverBlockEntity al ? al.getState() : -1;
    }

    @Override
    public BlockPos anchorToLeverDelta() {
        // anchor sits at source (3, 3, 5) — air cell immediately NBT-north of the lever
        // at source (3, 3, 6). Delta from anchor to lever = (0, 0, +1).
        return new BlockPos(0, 0, +1);
    }

    // NBT-frame deltas from the analog lever at (8,5,6):
    //   engine (8,4,6) → (0,-1,0)
    //   cannon mount (8,2,2) → (0,-3,-4)
    //   left clutch lever (6,4,8) → (-2,-1,2)
    //   right clutch lever (10,4,8) → (+2,-1,2)
    @Override public List<BlockPos> engineDeltas() {
        return List.of(new BlockPos(0, -1, 0));
    }
    @Override public List<BlockPos> throttleLeverDeltas() {
        return List.of(BlockPos.ZERO);
    }
    @Override public BlockPos leftClutchLeverDelta() { return new BlockPos(-2, -1, 2); }
    @Override public BlockPos rightClutchLeverDelta() { return new BlockPos(+2, -1, 2); }
    @Override public List<BlockPos> cannonMountDeltas() {
        return List.of(new BlockPos(0, -3, -4));
    }

    // Honey-glue body bounds (inclusive). Matches the constants the pre-refactor
    // AirshipLiftoffTrigger used for the single ship.
    @Override public BlockPos glueMin() { return new BlockPos(-2, -3, -5); }
    @Override public BlockPos glueMax() { return new BlockPos(+2, +4, +4); }

    @Override public CombatBehavior combat() { return combat; }
}
