package com.mcpirates.datagen;

import com.mcpirates.MCPirates;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class MCPDatagen {

    private MCPDatagen() {}

    @SubscribeEvent
    public static void onGather(GatherDataEvent event) {
        event.getGenerator().addProvider(
                event.includeServer(),
                new LandingPadStructureProvider(event.getGenerator().getPackOutput()));
    }
}
