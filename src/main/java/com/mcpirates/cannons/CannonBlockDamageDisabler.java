package com.mcpirates.cannons;

import com.mcpirates.MCPirates;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import rbasamoyai.createbigcannons.config.CBCCfgMunitions;
import rbasamoyai.createbigcannons.config.CBCConfigs;

/**
 * Forces Create Big Cannons' grief-state config to {@code NO_DAMAGE} on every server start.
 * This routes all CBC damage paths (kinetic, explosive, shrapnel) to entity-only damage —
 * the cannons still hurt mobs and players, but no blocks break.
 *
 * <p>The setting flows through {@code CBCConfigs.server().munitions.damageRestriction}, which
 * every CBC projectile reads at impact time. See
 * {@code rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile} et al. for the
 * call sites.
 *
 * <p>If a server admin wants to re-enable cannon block damage they can comment out the
 * {@code @EventBusSubscriber} on this class — but the user-facing intent of mcpirates is
 * "pillager airships fight, terrain stays intact", so this is on by default.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class CannonBlockDamageDisabler {

    private CannonBlockDamageDisabler() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        CBCCfgMunitions.GriefState current = CBCConfigs.server().munitions.damageRestriction.get();
        if (current == CBCCfgMunitions.GriefState.NO_DAMAGE) {
            return;
        }
        CBCConfigs.server().munitions.damageRestriction.set(CBCCfgMunitions.GriefState.NO_DAMAGE);
        MCPirates.LOGGER.info(
                "CBC cannon block-damage disabled (damageRestriction was {}, now NO_DAMAGE)",
                current);
    }
}
