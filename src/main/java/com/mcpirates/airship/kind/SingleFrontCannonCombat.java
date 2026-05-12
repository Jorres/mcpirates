package com.mcpirates.airship.kind;

import com.mcpirates.airship.Airship;
import net.minecraft.server.level.ServerPlayer;

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
    public void aim(Airship ship, ServerPlayer target) {
        if (ship.slCannonMounts.isEmpty()) return;
        net.minecraft.core.BlockPos mount = ship.slCannonMounts.get(0);
        // Skip aiming when the gunner is dead — cosmetically the barrel freezes at its
        // last aim, signalling "this cannon is out of action".
        if (!ship.isMountManned(mount)) return;
        CannonOps.aimAt(ship, mount, target);
    }

    @Override
    public boolean fire(Airship ship, ServerPlayer target) {
        if (ship.slCannonMounts.isEmpty()) return false;
        net.minecraft.core.BlockPos mount = ship.slCannonMounts.get(0);
        if (!ship.isMountManned(mount)) return false;
        return CannonOps.fireOnce(ship, mount);
    }
}
