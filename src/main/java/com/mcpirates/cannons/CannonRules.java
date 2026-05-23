package com.mcpirates.cannons;

import com.mcpirates.MCPirates;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import rbasamoyai.createbigcannons.config.CBCCfgFailure;
import rbasamoyai.createbigcannons.config.CBCCfgMunitions;
import rbasamoyai.createbigcannons.config.CBCConfigs;

/**
 * Force CBC server config into mcpirates' policy on every server start.
 *
 * <ul>
 *   <li>{@code damageRestriction = ALL_DAMAGE} — without it, the {@code cannon_indestructible}
 *       block_armor tag has nothing to override.</li>
 *   <li>{@code failure.disableAllFailure = true} — pirate cannons fire on a fixed 2-charge
 *       load that the player can't influence, so squib/burst/overload rolls would be pure
 *       RNG punishment with no player counterplay.</li>
 * </ul>
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class CannonRules {

    private CannonRules() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        CBCCfgMunitions munitions = CBCConfigs.server().munitions;
        CBCCfgMunitions.GriefState grief = munitions.damageRestriction.get();
        if (grief != CBCCfgMunitions.GriefState.ALL_DAMAGE) {
            munitions.damageRestriction.set(CBCCfgMunitions.GriefState.ALL_DAMAGE);
            MCPirates.LOGGER.info("CBC damageRestriction was {}, forced to ALL_DAMAGE", grief);
        }

        CBCCfgFailure failure = CBCConfigs.server().failure;
        if (!failure.disableAllFailure.get()) {
            failure.disableAllFailure.set(true);
            MCPirates.LOGGER.info("CBC disableAllFailure was false, forced to true");
        }
    }
}
