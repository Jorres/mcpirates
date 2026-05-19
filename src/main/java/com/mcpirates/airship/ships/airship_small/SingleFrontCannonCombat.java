package com.mcpirates.airship.ships.airship_small;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.hardware.CannonOps;
import com.mcpirates.airship.interfaces.CombatBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Combat module for ships with a single forward-mounted cannon (airship_small). Just
 * delegates aim/fire to the one mount in {@link Airship#slCannonMounts}.
 */
public final class SingleFrontCannonCombat implements CombatBehavior {

    /** 10 seconds between shots, matching the original single-ship cadence. Long enough
     *  that players have time to react and reposition. */
    private static final int FIRE_INTERVAL_TICKS = 200;

    @Override
    public int fireIntervalTicks() {
        return FIRE_INTERVAL_TICKS;
    }

    @Override
    public void aim(Airship ship, LivingEntity target) {
        if (ship.slCannonMounts.isEmpty()) return;
        BlockPos mount = ship.slCannonMounts.get(0);
        // Skip aiming when the gunner is dead — cosmetically the barrel freezes at its
        // last aim, signalling "this cannon is out of action".
        if (!ship.isMountManned(mount)) return;
        CannonOps.aimAt(ship, mount, target);
    }

    @Override
    public boolean fire(Airship ship, LivingEntity target) {
        if (ship.slCannonMounts.isEmpty()) return false;
        BlockPos mount = ship.slCannonMounts.get(0);
        if (!ship.isMountManned(mount)) return false;
        CannonOps.Aim aim = CannonOps.computeAim(ship, mount, target);
        if (aim.outOfRange()) return false;
        return CannonOps.fireOnce(ship, mount);
    }
}
