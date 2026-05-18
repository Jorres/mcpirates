package com.mcpirates.gametest.mixin;

import net.minecraft.gametest.framework.StructureUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip the {@code minecraft:barrier} shell that vanilla wraps around every gametest
 * arena.
 *
 * <p>{@link StructureUtils#encaseStructure} is called by {@code GameTestInfo} for every
 * test it sets up and unconditionally places a 4-wall barrier perimeter around the
 * structure (plus a ceiling layer gated on {@code !skyAccess()}). Our orbit tests
 * need the SubLevel to traverse an {@code r ≥ 30} circle — so the ship's hull
 * collides with the wall on the first tick of horizontal motion. The
 * {@code crossbow_board} hull (6 wide) fills its 6-wide template exactly, so
 * there's literally zero clearance between the hull and the wall.
 *
 * <p>Cancelling {@code encaseStructure} drops every barrier. The structure block
 * marker at the corner still exists, the cleared zone from
 * {@code clearSpaceForStructure} still runs — just no walls. A wall-equivalent of
 * vanilla's {@code skyAccess=true}.
 */
@Mixin(StructureUtils.class)
public abstract class StructureUtilsMixin {

    @Inject(method = "encaseStructure", at = @At("HEAD"), cancellable = true)
    private static void mcpirates$skipArenaBarriers(CallbackInfo ci) {
        ci.cancel();
    }
}
