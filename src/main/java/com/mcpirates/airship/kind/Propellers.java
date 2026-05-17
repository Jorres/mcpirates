package com.mcpirates.airship.kind;

import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Toggle the {@link BasePropellerBlock#REVERSED REVERSED} blockstate on an
 * Aeronautics propeller. In-game this is what a wrench right-click does; we
 * use it from {@link ShipControls} implementations to flip the thrust
 * direction of one side mid-flight (counter-rotation pivot).
 *
 * <p>No-op if the block at {@code pos} isn't a propeller, or if it's already
 * in the requested state — avoids unnecessary block updates.
 */
public final class Propellers {

    private Propellers() {}

    public static void setReversed(Level level, BlockPos pos, boolean reversed) {
        if (level == null || pos == null) return;
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(BasePropellerBlock.REVERSED)) return;
        if (state.getValue(BasePropellerBlock.REVERSED) == reversed) return;
        level.setBlock(pos, state.setValue(BasePropellerBlock.REVERSED, reversed), 3);
    }
}
