package com.mcpirates.gametest.mixin;

import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Filter the test list by a system property: {@code -Dmcpirates.gametest.only=<needle>}.
 * Match is case-insensitive substring on the registered test name (which is the
 * lowercased method name). Empty/unset property = no filtering.
 *
 * <p>Wired through {@code build.gradle}:
 * {@code ./gradlew runGameTestServer -Ponly=crossbowBoardOrbitsCcw} maps to
 * {@code -Dmcpirates.gametest.only=crossbowBoardOrbitsCcw}.
 *
 * <p>Why a mixin (not a per-method early-succeed): filtering at discovery means
 * non-matching tests are never scheduled, so their setup phases don't burn ticks
 * and their ships don't register into the static SHIPS list during the run.
 */
@Mixin(GameTestRegistry.class)
public abstract class GameTestRegistryMixin {

    @Inject(method = "getAllTestFunctions", at = @At("RETURN"), cancellable = true)
    private static void mcpirates$applyFilter(CallbackInfoReturnable<Collection<TestFunction>> cir) {
        String filter = System.getProperty("mcpirates.gametest.only", "").trim();
        if (filter.isEmpty()) return;
        String needle = filter.toLowerCase();
        Collection<TestFunction> all = cir.getReturnValue();
        List<TestFunction> filtered = all.stream()
                .filter(tf -> tf.testName().toLowerCase().contains(needle))
                .collect(Collectors.toList());
        System.out.println("[mcpirates.gametest] filter=\"" + filter + "\" matched "
                + filtered.size() + "/" + all.size() + " tests");
        cir.setReturnValue(filtered);
    }
}
