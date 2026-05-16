package com.mcpirates.airship.kind;

import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * Ramming attacker (NBT {@code ramship}, hull 9×10×22). Two outboard propellers driven
 * by the standard left/right clutches steer; a third center propeller — aligned on the
 * movement axis — provides constant forward thrust during PURSUE via {@link #forwardClutchLeverDelta}.
 *
 * <p>Primary anchor: the left Create analog lever at NBT (3, 3, 9), face=ceiling,
 * facing=NORTH. Ship-forward is NORTH (props face SOUTH, push NORTH).
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
    @Override public LeverKind throttleLeverKind() { return LeverKind.CREATE_ANALOG; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof AnalogLeverBlockEntity;
    }

    @Override
    public BlockPos anchorToLeverDelta() {
        // anchor at source (3,3,8); primary lever at (3,3,9). Delta = (0,0,+1).
        return new BlockPos(0, 0, +1);
    }

    // NBT-frame deltas from the left analog lever at (3,3,9):
    //   right throttle (5,3,9)            → (+2, 0, 0)
    //   engine          (3,2,13)          → ( 0,-1,+4)   sole portable engine, drives all three props
    //   left  clutch lever (vanilla) (2,3,18) → (-1, 0,+9)
    //   right clutch lever (vanilla) (6,3,18) → (+3, 0,+9)
    //   forward clutch lever (vanilla) (4,2,18) → (+1,-1,+9)
    @Override public List<BlockPos> engineDeltas() {
        return List.of(new BlockPos(0, -1, +4));
    }
    @Override public List<BlockPos> throttleLeverDeltas() {
        return List.of(BlockPos.ZERO, new BlockPos(+2, 0, 0));
    }
    @Override public BlockPos leftClutchLeverDelta() { return new BlockPos(-1, 0, +9); }
    @Override public BlockPos rightClutchLeverDelta() { return new BlockPos(+3, 0, +9); }
    @Override public List<BlockPos> cannonMountDeltas() { return List.of(); }

    /** Kind-specific third clutch lever — controls the center forward-axis propeller.
     *  Resolved through the AirshipKind interface would leak yet another block-pos getter
     *  into a shape every kind would have to implement; kept on RamshipKind only, with
     *  the assembly path checking {@code instanceof RamshipKind}. See docs/tech-debt.md. */
    public BlockPos forwardClutchLeverDelta() { return new BlockPos(+1, -1, +9); }

    // Hull spans NBT (0..8, 0..9, 0..21). Lever-relative (spawnHoneyGlue offsets from
    // lever, not anchor — see AirshipSmallKind comment for the BFS reasoning):
    //   min (0-3, 0-3, 0-9)  = (-3,-3,-9)
    //   max (8-3, 9-3, 21-9) = (+5,+6,+12)
    @Override public BlockPos glueMin() { return new BlockPos(-3, -3, -9); }
    @Override public BlockPos glueMax() { return new BlockPos(+5, +6, +12); }

    @Override public CombatBehavior combat() { return combat; }
    @Override public MovementBehavior movement() { return RamMovement.INSTANCE; }
}
