package com.mcpirates.airship.ships.galleon;

import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.ships.AnchorNbtPositions;
import com.mcpirates.airship.interfaces.CombatBehavior;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * Galleon (NBT {@code galleon}, size 12×15×28). Four cannons per side broadside layout,
 * two andesite propellers per side, two simulated:throttle_levers driving two black
 * portable engines.
 *
 * <p>Important difference from airship_small/crossbow-board: throttle uses
 * {@code simulated:throttle_lever}, not {@code create:analog_lever}. The two block kinds
 * have identical 0..15 semantics but different BE classes; {@code ThrottleLevers}
 * dispatches by BE type at write time.
 *
 * <p>Primary anchor: the leftmost throttle lever (face=floor, facing=NORTH). Absolute
 * anchor coords in {@link AnchorNbtPositions}; the right throttle is at
 * {@link #throttleLeverDeltas()} offset from the primary, written in lock-step.
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
    @Override public Direction nbtForward() { return Direction.NORTH; }

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

    // NBT-frame deltas from the left throttle lever at NBT (4, 8, 13). Private impl
    // detail — kinds expose resolved positions via layoutAt.
    //   engines (4,5,8),(7,5,8); right throttle (7,8,13); clutches at (4,6,9),(7,6,9);
    //   port cannons x=3, starboard x=8, z=8/11/16/19 at y=1.
    // Left throttle is +X, -Y of the anchor — unambiguously the LEFT of the pair.
    private static final BlockPos ANCHOR_TO_LEVER = new BlockPos(+1, -1, 0);
    private static final List<BlockPos> ENGINES   = List.of(
            new BlockPos(0, -3, -5),
            new BlockPos(+3, -3, -5));
    private static final List<BlockPos> THROTTLES = List.of(BlockPos.ZERO, new BlockPos(+3, 0, 0));
    private static final BlockPos LEFT_CLUTCH     = new BlockPos(0, -2, -4);
    private static final BlockPos RIGHT_CLUTCH    = new BlockPos(+3, -2, -4);
    private static final List<BlockPos> CANNONS   = List.of(
            new BlockPos(-1, -7, -5),
            new BlockPos(-1, -7, -2),
            new BlockPos(-1, -7, +3),
            new BlockPos(-1, -7, +6),
            new BlockPos(+4, -7, -5),
            new BlockPos(+4, -7, -2),
            new BlockPos(+4, -7, +3),
            new BlockPos(+4, -7, +6));
    private static final BlockPos GLUE_MIN = new BlockPos(-4, -8, -13);
    private static final BlockPos GLUE_MAX = new BlockPos(+7, +6, +14);

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
                CANNONS.stream().map(d -> leverRef.offset(d.rotate(r))).toList(),
                leverRef.offset(GLUE_MIN.rotate(r)),
                leverRef.offset(GLUE_MAX.rotate(r)));
    }

    @Override public CombatBehavior combat() { return combat; }
}
