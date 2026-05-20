package com.mcpirates.airship.ships.firecracker;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.interfaces.CombatBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Firecracker combat — currently NOOP. The ship carries three lever+clutch pairs:
 * two outboard pairs gate the port/starboard propellers (handled by
 * {@link com.mcpirates.airship.common.TankSteerControls}), and one extra pair sits inside
 * the cabin alongside the powder/shot stash. That third pair is the firecracker's
 * "special" trigger — likely a powder-charge-detonation actuator once combat lands.
 *
 * <p>Marked here so the trigger position is colocated with the combat strategy that will
 * eventually use it, not buried in the kind's private deltas.
 */
public final class FirecrackerCombat implements CombatBehavior {

    /** NBT-frame, lever-relative. Lever is the inner cabin wall-lever at (3,2,14).
     *  Clutch (the actuator) is one east at (4,2,14). */
    public static final BlockPos SPECIAL_LEVER_LEVER_REL  = new BlockPos(-1, -2, +4);
    public static final BlockPos SPECIAL_CLUTCH_LEVER_REL = new BlockPos( 0, -2, +4);

    @Override
    public int fireIntervalTicks() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void aim(Airship ship, LivingEntity target) {
        // no-op until special-pair semantics are implemented
    }

    @Override
    public boolean fire(Airship ship, LivingEntity target) {
        return false;
    }
}
