package com.mcpirates.pirates;

import com.mcpirates.MCPirates;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic scan helpers for ship-interior block searches. Finds Create seats and partitions
 * them by colour into "captain seats" (black) and "crew seats" (gray + light_gray), letting
 * the NBT designer declare role semantics through seat colour rather than positional
 * heuristics.
 *
 * <h2>Colour convention</h2>
 *
 * <ul>
 *   <li>{@link DyeColor#BLACK} → captain seat. The captain pirate stands here (tagged for
 *       seal drop, funny name). If more than one black seat is present, the highest-Y one
 *       wins and the rest are downgraded to crew with a warning.</li>
 *   <li>{@link DyeColor#GRAY} / {@link DyeColor#LIGHT_GRAY} → crew seats. Cannons claim the
 *       nearest as cannoneer stations; everything left becomes a crossbowman station.</li>
 *   <li>Anything else → ignored, warning logged. Reserve other colours for future roles
 *       (e.g. red for "fire commander", yellow for "lookout") rather than treating them as
 *       generic crew today.</li>
 * </ul>
 *
 * <p>The previous "highest Y is captain by convention" rule was rotation-stable but
 * fragile: any time the NBT designer added a tall mast seat or moved the helm down, the
 * captain swapped. Colour is explicit and rotation-independent.
 */
public final class Seats {

    private Seats() {}

    /** Partition of a seat scan by colour. */
    public record SeatScan(List<BlockPos> captainSeats, List<BlockPos> crewSeats) {
        public boolean isEmpty() { return captainSeats.isEmpty() && crewSeats.isEmpty(); }
    }

    /**
     * Scan the closed AABB defined by {@code slMin}..{@code slMax} (SubLevel-local block
     * coords, pre-sorted so min ≤ max componentwise) for Create seats and partition them
     * by colour.
     */
    public static SeatScan scan(SubLevel subLevel, BlockPos slMin, BlockPos slMax) {
        Level inner = subLevel.getLevel();
        if (inner == null) return new SeatScan(List.of(), List.of());
        List<BlockPos> captainSeats = new ArrayList<>();
        List<BlockPos> crewSeats = new ArrayList<>();
        int unknownColoured = 0;
        for (BlockPos p : BlockPos.betweenClosed(slMin, slMax)) {
            Block b = inner.getBlockState(p).getBlock();
            if (!(b instanceof SeatBlock seat)) continue;
            DyeColor c = seat.getColor();
            if (c == DyeColor.BLACK) {
                captainSeats.add(p.immutable());
            } else if (c == DyeColor.GRAY || c == DyeColor.LIGHT_GRAY) {
                crewSeats.add(p.immutable());
            } else {
                unknownColoured++;
            }
        }
        if (unknownColoured > 0) {
            MCPirates.LOGGER.warn(
                    "seat scan: {} seat(s) of unsupported colour ignored — use BLACK for captain, "
                    + "GRAY/LIGHT_GRAY for crew. Other colours are reserved for future roles.",
                    unknownColoured);
        }
        return new SeatScan(captainSeats, crewSeats);
    }
}
