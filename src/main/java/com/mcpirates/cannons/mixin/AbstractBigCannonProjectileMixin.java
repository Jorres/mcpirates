package com.mcpirates.cannons.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile.ImpactResult;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile.ImpactResult.KinematicOutcome;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

/**
 * One block per shot: any return value that says {@code PENETRATE} is downgraded to
 * {@code STOP}. The block CBC just broke is still gone; only the projectile's onward
 * travel is cut, so a single shell can't tunnel through multiple blocks even when its
 * mass would naturally carry it through softer materials.
 *
 * <p>Block-by-block invulnerability (chests, beds, …) is handled entirely by datapack:
 * see {@code data/mcpirates/block_armor/tags/cannon_indestructible.json}. CBC's own
 * {@code BlockArmorPropertiesHandler} reads the tag and applies absurd toughness, which
 * makes {@code blockBroken} resolve false in
 * {@link AbstractBigCannonProjectile#calculateBlockPenetration} without any code from us.
 */
@Mixin(AbstractBigCannonProjectile.class)
public abstract class AbstractBigCannonProjectileMixin {

    @Inject(method = "calculateBlockPenetration", at = @At("RETURN"), cancellable = true)
    private void mcpirates$capPenetration(ProjectileContext ctx, BlockState state,
                                           BlockHitResult hit,
                                           CallbackInfoReturnable<ImpactResult> cir) {
        ImpactResult r = cir.getReturnValue();
        if (r != null && r.kinematics() == KinematicOutcome.PENETRATE) {
            cir.setReturnValue(new ImpactResult(KinematicOutcome.STOP, r.shouldRemove()));
        }
    }
}
