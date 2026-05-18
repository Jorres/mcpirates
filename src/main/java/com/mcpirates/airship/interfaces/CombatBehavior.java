package com.mcpirates.airship.interfaces;

import com.mcpirates.airship.Airship;
import net.minecraft.world.entity.LivingEntity;

/**
 * Per-kind cannon strategy. The brain calls {@link #aim} every aim tick and
 * {@link #fire} every fire tick during PURSUE — but only while
 * {@code AirshipBrain.CANNON_AIM_ENABLED} and {@code CANNON_FIRE_ENABLED} respectively
 * are true.
 *
 * <p>The two methods are deliberately split so visual tracking (cheap; just yaw/pitch
 * writes) runs at a higher cadence than firing (spawns projectiles).
 */
public interface CombatBehavior {

    /** Cooldown between fire opportunities. The brain enforces it. */
    int fireIntervalTicks();

    /** Aim every active cannon at {@code target}. Called by the brain at
     *  {@code AIM_INTERVAL} cadence. */
    void aim(Airship ship, LivingEntity target);

    /** Fire whatever should fire this opportunity. Returns true if at least one shot was
     *  fired (the brain then resets {@link Airship#lastFireTick}); false to skip the
     *  cooldown reset (e.g., for cannonless ships, or when no side has a clear shot). */
    boolean fire(Airship ship, LivingEntity target);
}
