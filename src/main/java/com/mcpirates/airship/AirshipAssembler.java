package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.simibubi.create.content.contraptions.AssemblyException;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.simulated_team.simulated.util.SimAssemblyHelper.AssemblyResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Snaps a pre-built mcpirates airship into a Sable {@link dev.ryanhcode.sable.sublevel.SubLevel}
 * contraption without requiring a {@code PhysicsAssemblerBlock} to be wired up.
 *
 * <p>The airship's structure NBT ships pre-baked honey-glue entities on every internal face of
 * the hull, so Aeronautics' assembly BFS sees the body as one connected piece. The landing pad
 * sits flush against the hull but has no glue crossing that boundary, so the BFS naturally stops
 * at the pad and only the ship lifts off.
 *
 * <p>Returns the {@link AssemblyResult} (or {@code null} on failure) so callers can locate
 * post-assembly block positions: {@code worldPos + result.offset()} → SubLevel-local pos.
 */
public final class AirshipAssembler {

    private AirshipAssembler() {}

    public static AssemblyResult assemble(ServerLevel level, BlockPos seed) {
        try {
            AssemblyResult result =
                    SimAssemblyHelper.assembleFromSingleBlock(level, seed, seed, true, true);
            if (result == null) {
                MCPirates.LOGGER.warn(
                        "SimAssemblyHelper.assembleFromSingleBlock returned null for seed {} — "
                                + "is the airship missing honey-glue?", seed);
                return null;
            }
            MCPirates.LOGGER.info("airship assembled (seed={}, offset={})", seed, result.offset());
            return result;
        } catch (AssemblyException e) {
            MCPirates.LOGGER.error("airship assembly threw at {}", seed, e);
            return null;
        }
    }
}
