package com.mcpirates.airship.kind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Flip a vanilla {@code minecraft:lever} POWERED state, with the proper neighbour-update
 * dance so adjacent Create kinetic blocks (clutches) react to the redstone change.
 *
 * <p>Reminder: the clutches read {@code powered=true} as <em>disengaged</em>. So the brain
 * passes {@code !engaged} as the powered value.
 */
public final class ClutchLevers {

    private ClutchLevers() {}

    public static void setPowered(Level level, BlockPos pos, boolean powered) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LeverBlock leverBlock)) return;
        if (state.getValue(LeverBlock.POWERED) == powered) return;
        BlockState updated = state.setValue(LeverBlock.POWERED, powered);
        level.setBlock(pos, updated, Block.UPDATE_ALL);
        level.updateNeighborsAt(pos, leverBlock);
        Direction face = ThrottleLevers.leverConnectedDirection(state);
        level.updateNeighborsAt(pos.relative(face.getOpposite()), leverBlock);
    }
}
