package com.mcpirates;

import com.mcpirates.registry.MCPCreativeTabs;
import com.mcpirates.registry.MCPItems;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MCPirates.MOD_ID)
public class MCPirates {
    public static final String MOD_ID = "mcpirates";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public MCPirates(IEventBus modBus) {
        LOGGER.info("[{}] initializing", MOD_ID);

        MCPItems.register(modBus);
        MCPCreativeTabs.register(modBus);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
