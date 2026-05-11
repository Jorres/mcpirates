package com.mcpirates;

import com.mcpirates.registry.MCPBlocks;
import com.mcpirates.command.OutpostCommands;
import com.mcpirates.registry.MCPCreativeTabs;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPPoiTypes;
import com.mcpirates.registry.MCPVillagerProfessions;
import com.mcpirates.village.SheriffNameAssigner;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MCPirates.MOD_ID)
public class MCPirates {
    public static final String MOD_ID = "mcpirates";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public MCPirates(IEventBus modBus) {
        LOGGER.info("[{}] initializing", MOD_ID);

        // Registry order: blocks first because MCPItems pulls in block-items, and
        // MCPPoiTypes references MCPBlocks at register-time. MCPVillagerProfessions
        // references MCPPoiTypes. Creative tabs last so the iteration finds everything.
        MCPBlocks.register(modBus);
        MCPItems.register(modBus);
        MCPPoiTypes.register(modBus);
        MCPVillagerProfessions.register(modBus);
        MCPCreativeTabs.register(modBus);

        NeoForge.EVENT_BUS.addListener(OutpostCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(SheriffNameAssigner::onEntityJoinLevel);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
