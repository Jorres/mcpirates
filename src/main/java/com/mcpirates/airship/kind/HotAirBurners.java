package com.mcpirates.airship.kind;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.Balloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

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

    /** Every Hot Air Burner block-entity position inside the inclusive AABB. Used at
     *  assembly to discover all burners on a ship without relying on lever→burner
     *  adjacency (galleons run wires several blocks between control panel and burner). */
    public static List<BlockPos> findAllInBox(
            Level level,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (level.getBlockEntity(cursor) instanceof HotAirBurnerBlockEntity) {
                        out.add(cursor.immutable());
                    }
                }
            }
        }
        return out;
    }
}
