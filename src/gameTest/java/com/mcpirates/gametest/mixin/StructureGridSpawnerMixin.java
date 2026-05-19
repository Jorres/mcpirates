package com.mcpirates.gametest.mixin;

import net.minecraft.gametest.framework.StructureGridSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Spread test arenas far enough apart that one test's stateful setup can't reach
 * into a neighbour's coordinates.
 *
 * <p><strong>Why</strong>: two constraints.
 * <ol>
 *   <li><b>Trigger radius:</b> {@code AirshipLiftoffTrigger.processNearbyAnchors}
 *       scans a 10-chunk (160-block) radius for anchor block entities. With the
 *       vanilla spacer (col=5, row=6 + structure size ≈ 12–18 blocks between arenas),
 *       a single test's trigger pass reaches into every neighbouring arena.</li>
 *   <li><b>Force-loaded chunk reach:</b> {@code RamshipTests.ramshipInterceptsMovingTarget}
 *       force-loads three 13×13-chunk grids spanning roughly 600 blocks along X
 *       (ramship → victim → dest chunk). At 200 blocks of arena spacing, these
 *       overlap two neighbouring arenas to the east. Leaked tickets from a timed-out
 *       test then tick neighbouring arenas' chunks before they reset, producing the
 *       cascade failure we hit on master.</li>
 * </ol>
 *
 * <p>{@code 600} clears both: comfortably past the trigger radius AND past the
 * widest force-loaded grid we currently spawn. A 38-test run spans x ∈ [0, ~3600],
 * still well inside the f32-precision band Sable's physics tolerates.
 */
@Mixin(StructureGridSpawner.class)
public abstract class StructureGridSpawnerMixin {

    @ModifyConstant(method = "spawnStructure",
            constant = @Constant(intValue = 5))
    private int mcpirates$widenColumnGap(int original) {
        return 600;
    }

    @ModifyConstant(method = "spawnStructure",
            constant = @Constant(intValue = 6))
    private int mcpirates$widenRowGap(int original) {
        return 600;
    }
}
