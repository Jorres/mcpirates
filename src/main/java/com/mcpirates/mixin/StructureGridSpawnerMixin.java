package com.mcpirates.mixin;

import net.minecraft.gametest.framework.StructureGridSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Spread test arenas far enough apart that no two arenas sit within each
 * other's trigger radius.
 *
 * <p><strong>Why</strong>: {@code AirshipLiftoffTrigger.processNearbyAnchors}
 * scans a 10-chunk (160 block) radius for anchor block entities. With the
 * vanilla spacer (col=5, row=6 + structure size ≈ 12–18 blocks between arenas),
 * a single test's on-foot trigger pass reaches into every neighbouring arena
 * and fires combat at <em>their</em> anchors too. The framework gives each test
 * a bbox to call its own, but our production proximity scan deliberately
 * reaches outside it — so we widen the spacer past 160 instead of fighting the
 * scan in test setup.
 *
 * <p>After this mixin, arenas live ~200 blocks apart along both axes. With
 * {@code GameTestServerMixin} pinning the first arena near origin, a typical
 * 6-test run spans x ∈ [0, ~1200] — still tight enough to avoid the f32
 * quantisation that bites Sable past ~10⁷.
 */
@Mixin(StructureGridSpawner.class)
public abstract class StructureGridSpawnerMixin {

    @ModifyConstant(method = "spawnStructure",
            constant = @Constant(intValue = 5))
    private int mcpirates$widenColumnGap(int original) {
        return 200;
    }

    @ModifyConstant(method = "spawnStructure",
            constant = @Constant(intValue = 6))
    private int mcpirates$widenRowGap(int original) {
        return 200;
    }
}
