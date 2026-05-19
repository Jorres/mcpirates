package com.mcpirates.cannons;

import com.mcpirates.MCPirates;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import rbasamoyai.createbigcannons.config.CBCCfgMunitions;
import rbasamoyai.createbigcannons.config.CBCConfigs;

/**
 * Forces CBC's grief state to {@code ALL_DAMAGE} on every server start. Combined with
 * {@link CannonBlockBlacklist} (chest/bed/etc. are skipped per-impact) and the
 * one-block-per-shot cap in {@link com.mcpirates.cannons.mixin.AbstractBigCannonProjectileMixin},
 * this gives "real but bounded" cannon damage: hull blocks chip away, inventory and
 * critical control blocks survive.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class CannonDamageMode {

    private CannonDamageMode() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        CBCCfgMunitions.GriefState current = CBCConfigs.server().munitions.damageRestriction.get();
        if (current == CBCCfgMunitions.GriefState.ALL_DAMAGE) return;
        CBCConfigs.server().munitions.damageRestriction.set(CBCCfgMunitions.GriefState.ALL_DAMAGE);
        MCPirates.LOGGER.info(
                "CBC damageRestriction was {}, set to ALL_DAMAGE (mcpirates owns this)",
                current);
    }
}
