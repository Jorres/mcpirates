package com.mcpirates.airship.kind;

import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.lang.reflect.Field;

/**
 * Read/write 0..15 throttle state for either flavour of "analog lever" a ship can use:
 *
 * <ul>
 *   <li>{@code create:analog_lever} — package-private {@code state} field, written via
 *       reflection + neighbour updates to propagate the redstone change to the burner
 *       above. Identical to the path the original (single-kind) brain used.</li>
 *   <li>{@code simulated:throttle_lever} — public {@code setSignal(int)} on its BE handles
 *       blockstate inversion + neighbour updates internally; we just call it.</li>
 * </ul>
 *
 * <p>Reads always go through {@code getState()} (both BEs expose it).
 */
public final class ThrottleLevers {

    private static Field cachedAnalogStateField;

    private ThrottleLevers() {}

    /** Returns 0..15 if {@code pos} holds either supported BE, else -1. */
    public static int readState(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AnalogLeverBlockEntity al) return al.getState();
        if (be instanceof ThrottleLeverBlockEntity tl) return tl.getState();
        return -1;
    }

    /** Idempotent set — skips the write if state already matches.
     *  @return true on success or no-op; false if the BE is missing/unexpected. */
    public static boolean setState(Level level, BlockPos pos, int state) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AnalogLeverBlockEntity al) {
            return setAnalog(level, pos, al, state);
        }
        if (be instanceof ThrottleLeverBlockEntity tl) {
            if (tl.getState() == state) return true;
            tl.setSignal(state);
            return true;
        }
        return false;
    }

    private static boolean setAnalog(Level level, BlockPos pos, AnalogLeverBlockEntity lever, int state) {
        if (lever.getState() == state) return true;
        try {
            if (cachedAnalogStateField == null) {
                cachedAnalogStateField = AnalogLeverBlockEntity.class.getDeclaredField("state");
                cachedAnalogStateField.setAccessible(true);
            }
            cachedAnalogStateField.setInt(lever, state);
        } catch (ReflectiveOperationException e) {
            return false;
        }
        lever.setChanged();
        BlockState bs = level.getBlockState(pos);
        Block block = bs.getBlock();
        level.updateNeighborsAt(pos, block);
        Direction connected = leverConnectedDirection(bs);
        level.updateNeighborsAt(pos.relative(connected.getOpposite()), block);
        level.sendBlockUpdated(pos, bs, bs, Block.UPDATE_ALL);
        return true;
    }

    /** Which way the lever's attached-block face points; used for neighbour-update routing. */
    public static Direction leverConnectedDirection(BlockState state) {
        AttachFace face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
        return switch (face) {
            case CEILING -> Direction.DOWN;
            case FLOOR -> Direction.UP;
            case WALL -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
        };
    }
}
