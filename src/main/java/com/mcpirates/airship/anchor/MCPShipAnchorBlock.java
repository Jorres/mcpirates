package com.mcpirates.airship.anchor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

/**
 * Invisible metadata block placed inside every mcpirates airship's structure NBT to
 * identify which {@link com.mcpirates.airship.kind.AirshipKind} the ship is, without
 * the lift-off trigger having to geometrically guess. See
 * {@link MCPShipAnchorBlockEntity} for the kind-name payload.
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

    public MCPShipAnchorBlock(BlockBehaviour.Properties properties) {
        super(properties);
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
