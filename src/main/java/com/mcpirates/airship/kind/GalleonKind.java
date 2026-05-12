package com.mcpirates.airship.kind;

import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * Galleon (NBT {@code galleon}, size 12×15×28). Four cannons per side broadside layout,
 * two andesite propellers per side, two simulated:throttle_levers driving two black
 * portable engines.
 *
 * <p>Important difference from airship_small/crossbow-board: throttle uses
 * {@code simulated:throttle_lever}, not {@code create:analog_lever}. The two block kinds
 * have identical 0..15 semantics but different BE classes; {@link ThrottleLevers}
 * dispatches by BE type at write time.
 *
 * <p>Primary anchor: the leftmost throttle lever at NBT (3,9,14), face=floor,
 * facing=NORTH. The other throttle is at (8,9,14); both are written in lock-step.
 */
public final class GalleonKind implements AirshipKind {

    public static final GalleonKind INSTANCE = new GalleonKind();

    private final CombatBehavior combat;

    private GalleonKind() {
        // Sides parallel to cannonMountDeltas(): first 4 are port (LEFT), last 4 starboard.
        this.combat = new BroadsideCombat(List.of(
                new BroadsideCombat.MountSide(BroadsideCombat.Side.LEFT),
                new BroadsideCombat.MountSide(BroadsideCombat.Side.LEFT),
                new BroadsideCombat.MountSide(BroadsideCombat.Side.LEFT),
                new BroadsideCombat.MountSide(BroadsideCombat.Side.LEFT),
                new BroadsideCombat.MountSide(BroadsideCombat.Side.RIGHT),
                new BroadsideCombat.MountSide(BroadsideCombat.Side.RIGHT),
                new BroadsideCombat.MountSide(BroadsideCombat.Side.RIGHT),
                new BroadsideCombat.MountSide(BroadsideCombat.Side.RIGHT)));
    }

    @Override public String name() { return "galleon"; }
    @Override public Direction nbtPrimaryFacing() { return Direction.NORTH; }
    @Override public Direction nbtForward() { return Direction.NORTH; }
    @Override public LeverKind throttleLeverKind() { return LeverKind.SIMULATED_THROTTLE; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof ThrottleLeverBlockEntity;
    }

    @Override
    public int readAnchorState(BlockEntity be) {
        return be instanceof ThrottleLeverBlockEntity tl ? tl.getState() : -1;
    }

    @Override
    public BlockPos anchorToLeverDelta() {
        // anchor at source (3, 9, 13) — air cell NBT-north of the left throttle at
        // source (3, 9, 14). Delta = (0, 0, +1). This unambiguously points at the
        // LEFT throttle of the pair — the right throttle is at (3, 9, 14)+(+5,0,0).
        return new BlockPos(0, 0, +1);
    }

    // NBT-frame deltas from the left throttle lever at (3,9,14):
    //   engines (4,5,8),(7,5,8) → (+1,-4,-6),(+4,-4,-6)
    //   right throttle (8,9,14) → (+5,0,0)
    //   left clutch lever (4,6,9) → (+1,-3,-5) — vanilla floor lever on top of create:clutch
    //   right clutch lever (7,6,9) → (+4,-3,-5)
    //   port cannons (x=3, z=8/11/16/19) at y=1 → (0,-8,-6),(0,-8,-3),(0,-8,+2),(0,-8,+5)
    //   starboard (x=8, z=8/11/16/19) at y=1 → (+5,-8,-6),(+5,-8,-3),(+5,-8,+2),(+5,-8,+5)
    @Override public List<BlockPos> engineDeltas() {
        return List.of(
                new BlockPos(+1, -4, -6),
                new BlockPos(+4, -4, -6));
    }
    @Override public List<BlockPos> throttleLeverDeltas() {
        return List.of(BlockPos.ZERO, new BlockPos(+5, 0, 0));
    }
    @Override public BlockPos leftClutchLeverDelta() { return new BlockPos(+1, -3, -5); }
    @Override public BlockPos rightClutchLeverDelta() { return new BlockPos(+4, -3, -5); }
    @Override public List<BlockPos> cannonMountDeltas() {
        return List.of(
                new BlockPos(0, -8, -6),
                new BlockPos(0, -8, -3),
                new BlockPos(0, -8, +2),
                new BlockPos(0, -8, +5),
                new BlockPos(+5, -8, -6),
                new BlockPos(+5, -8, -3),
                new BlockPos(+5, -8, +2),
                new BlockPos(+5, -8, +5));
    }

    // Hull spans NBT (0..11, 0..14, 0..27). Lever at (3,9,14), so deltas:
    //   min (-3,-9,-14)  max (+8,+5,+13)
    @Override public BlockPos glueMin() { return new BlockPos(-3, -9, -14); }
    @Override public BlockPos glueMax() { return new BlockPos(+8, +5, +13); }

    @Override public CombatBehavior combat() { return combat; }
}
