package com.mcpirates.airship.kind;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.Balloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read/write the "hot air amount" m³ value on a Hot Air Burner block. Paired with
 * {@link ThrottleLevers} — actual gas output is {@code burner_volume * lever_state / 15}.
 * The brain owns both knobs so it can hit near-continuous lift values instead of the
 * 16 quantised levels the lever alone provides.
 */
public final class HotAirBurners {

    private HotAirBurners() {}

    /** Idempotent set — the underlying ScrollValueBehaviour skips the write if the value
     *  matches and clamps to the burner's configured [min, max] internally.
     *  @return true if {@code pos} held a Hot Air Burner BE; false otherwise. */
    public static boolean setVolume(Level level, BlockPos pos, int volume) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HotAirBurnerBlockEntity)) return false;
        ScrollValueBehaviour b = BlockEntityBehaviour.get(be, ScrollValueBehaviour.TYPE);
        if (b == null) return false;
        b.setValue(volume);
        return true;
    }

    /** Current m³ volume the burner is set to, or 0 if {@code pos} doesn't hold a Hot Air
     *  Burner BE (or its scroll-value behaviour is missing). Mirror of {@link #setVolume};
     *  use this anywhere you'd otherwise want to cache the last-written value. */
    public static int readVolume(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HotAirBurnerBlockEntity)) return 0;
        ScrollValueBehaviour b = BlockEntityBehaviour.get(be, ScrollValueBehaviour.TYPE);
        return b == null ? 0 : b.getValue();
    }

    /** Capacity (in m³ / hot-air blocks) of the balloon this burner is currently heating,
     *  or -1 if the burner isn't attached to a balloon yet (cold burner, signal=0 before
     *  liftoff, or the balloon's still floodfilling). A burner only joins a balloon once
     *  it has {@code canOutputGas()} — i.e. after the lever goes redstone-hot — so
     *  expect -1 during the very first ticks of LIFTOFF and a real number thereafter. */
    public static int queryBalloonCapacity(Level level, BlockPos burnerPos) {
        BlockEntity be = level.getBlockEntity(burnerPos);
        if (!(be instanceof HotAirBurnerBlockEntity burner)) return -1;
        Balloon b = burner.getBalloon();
        return (b != null) ? b.getCapacity() : -1;
    }

    /** Find the Hot Air Burner adjacent to {@code leverPos}. Levers on the airships are
     *  attached directly to the burner block — ceiling-attached lever → burner above,
     *  floor-attached → burner below, wall-attached → burner behind. We try the
     *  attachment direction first (cheap, correct for every current ship), then fall
     *  back to a six-neighbour scan in case future designs route signal differently. */
    public static BlockPos findAdjacentBurner(Level level, BlockPos leverPos, BlockState leverState) {
        Direction connected = ThrottleLevers.leverConnectedDirection(leverState);
        BlockPos attached = leverPos.relative(connected.getOpposite());
        if (level.getBlockEntity(attached) instanceof HotAirBurnerBlockEntity) return attached;
        for (Direction d : Direction.values()) {
            BlockPos n = leverPos.relative(d);
            if (level.getBlockEntity(n) instanceof HotAirBurnerBlockEntity) return n;
        }
        return null;
    }
}
