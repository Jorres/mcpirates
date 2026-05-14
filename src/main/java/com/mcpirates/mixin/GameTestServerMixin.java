package com.mcpirates.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Pin gametest arena positions at world origin instead of a uniformly-random
 * point in {@code [-14999992, 14999992]^2}.
 *
 * <p><strong>Why</strong>: Sable's physics engine is rapier3d with 32-bit
 * (f32) precision. At very large world coordinates the per-tick velocity
 * integration ({@code pos += vel * dt}) gets quantised below the float-step
 * size (≈1.5 units at x=15M), and SubLevels effectively can't accelerate.
 * Sable's own {@code PhysicsTest.testGravity} comments out the assertion
 * with a FIXME pointing at this exact issue. The proper fix is to make
 * sable build against rapier3d-f64; until then, the easiest workaround is
 * to keep test arenas near world (0, _, 0) where f32 precision is fine.
 *
 * <p>Vanilla {@code GameTestServer.startTests} hard-codes the random
 * positioning — no @GameTest annotation parameter, no NeoForge event, no
 * config knob exposes it. The mixin redirects each of the two
 * {@code nextIntBetweenInclusive} calls used to build the first test's
 * NW-corner to return 0, anchoring the StructureGridSpawner at (0, -59, 0).
 * Subsequent tests in the batch are then placed 5-6 blocks apart by the
 * spawner itself, all still near origin.
 *
 * <p>This is gametest-only — the mixin doesn't touch production code paths.
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerMixin {

    @Redirect(
            method = "startTests",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;nextIntBetweenInclusive(II)I"))
    private int mcpirates$pinTestPositionToOrigin(net.minecraft.util.RandomSource random, int low, int high) {
        return 0;
    }
}
