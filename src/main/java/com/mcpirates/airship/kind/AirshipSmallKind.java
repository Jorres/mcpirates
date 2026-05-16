package com.mcpirates.airship.kind;

import com.mcpirates.pirates.GroundCombatModule;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

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

    // Honey-glue body bounds (inclusive, LEVER-relative — spawnHoneyGlue adds these to
    // the lever pos, NOT the anchor pos).
    //
    // CRITICAL: must enclose every occupied cell of airship_small.nbt. The BFS in
    // SimAssemblyContraption.moveBlock only crosses block-to-block if either
    // canStick is true OR both cells lie inside the SAME honey-glue entity. A
    // block left outside glue and not canStick-connected (e.g. an oak_slab
    // decoration sitting on top of an envelope) stays in the WORLD after
    // assembly, and the SubLevel's rigid body then collides with that leftover
    // world block when it tries to rise — visible to the player as "ship is
    // buoyant but won't lift off". The fix on the assembly side is to make sure
    // glueMin/glueMax cover the whole NBT footprint.
    //
    // NBT occupied cells: x=0..6, y=0..8, z=0..11 (lever at (3,3,6) in NBT).
    // Lever-relative: min=(0-3,0-3,0-6)=(-3,-3,-6); max=(6-3,8-3,11-6)=(+3,+5,+5).
    @Override public BlockPos glueMin() { return new BlockPos(-3, -3, -6); }
    @Override public BlockPos glueMax() { return new BlockPos(+3, +5, +5); }

    @Override public CombatBehavior combat() { return combat; }

    @Override
    public Optional<GroundCombatModule> groundCombat() {
        return Optional.of(GroundCombatModule.SHARED);
    }
}
