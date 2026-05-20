package com.mcpirates.airship.ships.firecracker;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.common.TankSteerControls;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.CombatBehavior;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.interfaces.ShipControls;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

import java.util.List;

/**
 * Firecracker (NBT {@code firecracker}, size 9×10×19). Two outboard propellers steer
 * tank-style via the two top-deck lever+clutch pairs. A third lever+clutch pair sits low
 * in the cabin next to the powder/shot stores — its semantics live in
 * {@link FirecrackerCombat#SPECIAL_LEVER_LEVER_REL} for the future combat strategy.
 *
 * <p>Primary anchor: the single Create analog lever at NBT (4, 4, 10), face=ceiling
 * facing=south. Ship-forward is NORTH (props face SOUTH with reversed=true, push NORTH),
 * matching ramship convention.
 */
public final class FirecrackerKind implements AirshipKind {

    public static final FirecrackerKind INSTANCE = new FirecrackerKind();

    private final CombatBehavior combat = new FirecrackerCombat();

    private FirecrackerKind() {}

    @Override public String name() { return FirecrackerNbtSpec.INSTANCE.shipId(); }
    @Override public FirecrackerNbtSpec nbtSpec() { return FirecrackerNbtSpec.INSTANCE; }

    private static final BlockPos ANCHOR_TO_LEVER = arr(FirecrackerNbtSpec.INSTANCE.anchorToLever());
    // Single portable engine one block below + ahead of the lever (NBT (4,3,14) vs lever (4,4,10)).
    private static final List<BlockPos> ENGINES   = List.of(new BlockPos(0, -1, +4));
    // Single throttle = the primary analog lever itself.
    private static final List<BlockPos> THROTTLES = List.of(BlockPos.ZERO);
    // Layout's leftClutch/rightClutch slots take the LEVER that powers the clutch
    // (ClutchLevers.setPowered checks for instanceof LeverBlock); pointing at the
    // clutch block itself silently no-ops. Port lever NBT (2,5,16) and stbd lever
    // NBT (6,5,16) — one block inboard of their respective clutches.
    private static final BlockPos LEFT_CLUTCH     = new BlockPos(-2, +1, +6);
    private static final BlockPos RIGHT_CLUTCH    = new BlockPos(+2, +1, +6);
    // Full NBT bbox in lever-relative coords (NBT spans 9x10x19, lever at (4,4,10)).
    // Glue must cover the full NBT — see feedback_glue_must_cover_full_nbt.
    private static final BlockPos GLUE_MIN        = new BlockPos(-4, -4, -10);
    private static final BlockPos GLUE_MAX        = new BlockPos(+4, +5, +8);

    private static BlockPos arr(int[] a) { return new BlockPos(a[0], a[1], a[2]); }
    private static List<BlockPos> rotateOffsets(int[][] deltas, BlockPos base, Rotation r) {
        return java.util.Arrays.stream(deltas).map(d -> base.offset(arr(d).rotate(r))).toList();
    }

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

    @Override
    public ShipControls makeControls(Airship airship,
                                     BlockPos slLeftClutchLever,
                                     BlockPos slRightClutchLever,
                                     BlockPos slPrimaryAnchor,
                                     Rotation rotation) {
        FirecrackerNbtSpec spec = FirecrackerNbtSpec.INSTANCE;
        return new TankSteerControls(
                slLeftClutchLever, slRightClutchLever,
                rotateOffsets(spec.leftPropellersLeverRel(), slPrimaryAnchor, rotation),
                rotateOffsets(spec.rightPropellersLeverRel(), slPrimaryAnchor, rotation),
                spec.nbtReversedL(), spec.nbtReversedR());
    }

    @Override public CombatBehavior combat() { return combat; }

    // 1-thick × 2-tall × 2-long dispenser bank (NBT (4, 1..2, 10..11), one block back of
    // the temporary wool markers at z=9). Held together with Create super glue so the
    // four dispensers survive assembly as a single rigid unit, separate from the honey
    // glue that drives the BFS.
    private static final BlockPos DISPENSER_BANK_MIN = new BlockPos(0, -3, 0);
    private static final BlockPos DISPENSER_BANK_MAX = new BlockPos(0, -2, 1);

    @Override
    public void preassemble(ServerLevel level, BlockPos leverWorld, Rotation rotation) {
        BlockPos a = leverWorld.offset(DISPENSER_BANK_MIN.rotate(rotation));
        BlockPos b = leverWorld.offset(DISPENSER_BANK_MAX.rotate(rotation));
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(a, b)));
    }
}
