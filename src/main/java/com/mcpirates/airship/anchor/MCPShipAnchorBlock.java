package com.mcpirates.airship.anchor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

/**
 * Invisible metadata block placed inside every mcpirates airship's structure NBT to
 * identify which {@link com.mcpirates.airship.interfaces.AirshipKind} the ship is, without
 * the lift-off trigger having to geometrically guess. See
 * {@link MCPShipAnchorBlockEntity} for the kind-name payload.
 *
 * <h2>FACING</h2>
 *
 * Carries the standard {@link HorizontalDirectionalBlock#FACING} property. Its purpose is
 * to encode the structure's worldgen rotation: structure-template placement rotates this
 * property automatically via {@link #rotate(BlockState, Rotation)}, and
 * {@code AirshipKind.detectRotation} reads it back at activation to recover the placed
 * rotation. The NBT-frame default {@link #NBT_FACING} also doubles as each ship's bow
 * direction in NBT — by convention every ship is authored with both the anchor and the
 * bow pointing this way, so {@code Airship}'s constructor uses the same constant to
 * derive {@code shipLocalForward}.
 *
 * <h2>Visual + physical properties</h2>
 *
 * <ul>
 *   <li>{@link RenderShape#INVISIBLE} — no model rendered.</li>
 *   <li>No collision shape — players walk through.</li>
 *   <li>Extreme hardness/explosion resistance — players can't break it without
 *       creative-mode pick-block; the only way it should enter the world is via
 *       structure-template placement from one of our ship NBTs.</li>
 * </ul>
 */
public final class MCPShipAnchorBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** Mod-wide convention: every ship NBT authors the anchor — and the ship's bow — at
     *  this direction. {@link com.mcpirates.airship.Airship}'s constructor uses this same
     *  constant to derive {@code shipLocalForward}. Don't fork them without also splitting
     *  the bow direction back out as a per-kind concept. */
    public static final Direction NBT_FACING = Direction.NORTH;

    public MCPShipAnchorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, NBT_FACING));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MCPShipAnchorBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
