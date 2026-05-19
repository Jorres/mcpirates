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
        CannonOps.Aim a = CannonOps.aimAt(ship, mount, target);
        if (a != null) ship.lastAimByMount.put(mount, a);
    }

    @Override
    public boolean fire(Airship ship, LivingEntity target) {
        if (ship.slCannonMounts.isEmpty()) return false;
        BlockPos mount = ship.slCannonMounts.get(0);
        if (!ship.isMountManned(mount)) return false;
        // aim() runs at a higher cadence than fire() so the cache is normally populated
        // by the time we get here; the miss path covers first-tick startup.
        CannonOps.Aim aim = ship.lastAimByMount.get(mount);
        if (aim == null) aim = CannonOps.computeAim(ship, mount, target);
        if (!aim.canFire()) return false;
        return CannonOps.fireOnce(ship, mount);
    }
}
