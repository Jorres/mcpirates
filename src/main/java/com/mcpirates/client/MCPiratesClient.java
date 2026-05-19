package com.mcpirates.client;

import com.mcpirates.MCPirates;
import com.mcpirates.client.sheriff.SheriffScreen;
import com.mcpirates.registry.MCPDataComponents;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPMenuTypes;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = MCPirates.MOD_ID, value = Dist.CLIENT)
public final class MCPiratesClient {

    private MCPiratesClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MCPirates.LOGGER.info("[{}] client setup", MCPirates.MOD_ID);
        // ItemProperties is not thread-safe; defer to the client thread.
        event.enqueueWork(() -> ItemProperties.register(
                MCPItems.FURLED_BOUNTY.get(),
                ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "is_galleon_bounty"),
                (stack, level, entity, seed) ->
                        stack.has(MCPDataComponents.IS_GALLEON_BOUNTY.get()) ? 1.0f : 0.0f));
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(MCPMenuTypes.SHERIFF.get(), SheriffScreen::new);
    }
}
