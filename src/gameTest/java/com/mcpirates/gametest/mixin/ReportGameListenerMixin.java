package com.mcpirates.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skip the status beacon vanilla plants 2 blocks below each gametest structure block.
 * Its light beam interferes with airship physics / lift logic.
 */
@Mixin(targets = "net.minecraft.gametest.framework.ReportGameListener")
public abstract class ReportGameListenerMixin {

    @Redirect(
            method = "spawnBeacon",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private static boolean mcpirates$skipBeacon(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.BEACON)) {
            return true;
        }
        return level.setBlockAndUpdate(pos, state);
    }
}
