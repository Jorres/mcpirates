package com.mcpirates.airship.common;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.interfaces.CombatBehavior;
import net.minecraft.world.entity.LivingEntity;

/**
 * Combat module for ships without any cannons (currently crossbow-board). The brain still
 * calls aim/fire during PURSUE; we just no-op. This makes "ship has no offensive arsenal"
 * a configuration choice rather than a special case in the brain.
 *
 * <p>Future work: replace with a {@code CrossbowVolleyCombat} once turret crossbows
 * exist as a block.
 */
public final class NoCannonCombat implements CombatBehavior {

    @Override
    public int fireIntervalTicks() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void aim(Airship ship, LivingEntity target) {
        // intentional no-op
    }

    @Override
    public boolean fire(Airship ship, LivingEntity target) {
        return false;
    }
}
