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

    /** Galleon climbs ~100 blocks above its spawn altitude before exiting LIFTOFF —
     *  it has much more lift than airship_small, so the 25-block default exits
     *  while the ship is still ascending fast. */
    @Override public double liftoffMinRise() { return 100.0; }

    /** Galleon cruises lower than lighter kinds — heavy broadside design fights
     *  near the player rather than as a high observation platform. */
    @Override public double cruiseRise() { return 40.0; }

    @Override
    public boolean isPrimaryAnchorBE(BlockEntity be) {
        return be instanceof ThrottleLeverBlockEntity;
    }

    @Override
    public BlockPos anchorToLeverDelta() {
        // ship_anchor at NBT (3, 9, 13), left throttle at NBT (4, 8, 13).
        // Delta = (+1, -1, 0). Unambiguously points at the LEFT throttle of the pair
        // — the right throttle is at (4, 8, 13)+(+3,0,0).
        return new BlockPos(+1, -1, 0);
    }

    // NBT-frame deltas from the left throttle lever at (4, 8, 13):
    //   engines (4,5,8),(7,5,8) → (0,-3,-5),(+3,-3,-5)
    //   right throttle (7,8,13) → (+3,0,0)
    //   left clutch lever (4,6,9) → (0,-2,-4)
    //   right clutch lever (7,6,9) → (+3,-2,-4)
    //   port cannons (x=3, z=8/11/16/19) at y=1 → (-1,-7,-5),(-1,-7,-2),(-1,-7,+3),(-1,-7,+6)
    //   starboard (x=8, z=8/11/16/19) at y=1 → (+4,-7,-5),(+4,-7,-2),(+4,-7,+3),(+4,-7,+6)
    @Override public List<BlockPos> engineDeltas() {
        return List.of(
                new BlockPos(0, -3, -5),
                new BlockPos(+3, -3, -5));
    }
    @Override public List<BlockPos> throttleLeverDeltas() {
        return List.of(BlockPos.ZERO, new BlockPos(+3, 0, 0));
    }
    @Override public BlockPos leftClutchLeverDelta() { return new BlockPos(0, -2, -4); }
    @Override public BlockPos rightClutchLeverDelta() { return new BlockPos(+3, -2, -4); }
    @Override public List<BlockPos> cannonMountDeltas() {
        return List.of(
                new BlockPos(-1, -7, -5),
                new BlockPos(-1, -7, -2),
                new BlockPos(-1, -7, +3),
                new BlockPos(-1, -7, +6),
                new BlockPos(+4, -7, -5),
                new BlockPos(+4, -7, -2),
                new BlockPos(+4, -7, +3),
                new BlockPos(+4, -7, +6));
    }

    // Hull spans NBT (0..11, 0..14, 0..27). Left throttle at (4, 8, 13), so deltas
    // (preserving the same absolute hull region as before):
    //   min (-4,-8,-13)  max (+7,+6,+14)
    @Override public BlockPos glueMin() { return new BlockPos(-4, -8, -13); }
    @Override public BlockPos glueMax() { return new BlockPos(+7, +6, +14); }

    @Override public CombatBehavior combat() { return combat; }
}
