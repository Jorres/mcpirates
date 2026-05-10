package com.mcpirates.client;

import com.mcpirates.MCPirates;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = MCPirates.MOD_ID, value = Dist.CLIENT)
public final class MCPiratesClient {

    private MCPiratesClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MCPirates.LOGGER.info("[{}] client setup", MCPirates.MOD_ID);
    }
}
