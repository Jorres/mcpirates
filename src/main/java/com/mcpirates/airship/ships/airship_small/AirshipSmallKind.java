package com.mcpirates.airship.ships.airship_small;

import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.ships.AnchorNbtPositions;
import com.mcpirates.airship.interfaces.CombatBehavior;
import com.mcpirates.pirates.GroundCombatModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
import java.util.Optional;

/**
 * Original pirate ship (NBT {@code airship_small}): one envelope, one cannon at the bow,
 * one Create analog lever burner, two propeller clutches.
 *
 * <p>All deltas are taken relative to the analog lever (face=ceiling, facing=EAST),
 * which is the trigger anchor. Absolute anchor coords in {@link AnchorNbtPositions}.
 */
public final class AirshipSmallKind implements AirshipKind {

    public static final AirshipSmallKind INSTANCE = new AirshipSmallKind();

    private final CombatBehavior combat = new SingleFrontCannonCombat();

    private AirshipSmallKind() {}

    @Override public String name() { return "airship_small"; }
    @Override public Direction nbtForward() { return Direction.NORTH; }

    // NBT-frame deltas — private impl detail. Lever sits one block NBT-south of the anchor.
    private static final BlockPos ANCHOR_TO_LEVER = new BlockPos(0, 0, +1);
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

    @Override public CombatBehavior combat() { return combat; }

    @Override
    public Optional<GroundCombatModule> groundCombat() {
        return Optional.of(GroundCombatModule.SHARED);
    }
}
