package com.mcpirates;

import com.mcpirates.airship.worldgen.GalleonUnlockState;
import com.mcpirates.registry.MCPBlockEntityTypes;
import com.mcpirates.registry.MCPBlocks;
import com.mcpirates.registry.MCPCreativeTabs;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPPoiTypes;
import com.mcpirates.registry.MCPStructureTypes;
import com.mcpirates.registry.MCPVillagerProfessions;
import com.mcpirates.village.SheriffNameAssigner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
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
        MCPBlockEntityTypes.register(modBus);
        MCPItems.register(modBus);
        MCPPoiTypes.register(modBus);
        MCPVillagerProfessions.register(modBus);
        MCPCreativeTabs.register(modBus);
        MCPStructureTypes.register(modBus);

        // SheriffNameAssigner uses a plain listener instead of @EventBusSubscriber
        // because it doesn't have a register-event entry point of its own — the
        // EntityJoinLevelEvent listener IS the whole class. Adding it manually keeps
        // the class itself dependency-free of NeoForge annotations.
        NeoForge.EVENT_BUS.addListener(SheriffNameAssigner::onEntityJoinLevel);

        // On server start, mirror the persisted galleon-unlock flag from the overworld's
        // SavedData into the worker-readable static field — see GalleonUnlockState for
        // why we can't read SavedData from worldgen threads. Must run *after* the level
        // is loaded; ServerStartedEvent fires after the overworld is up.
        NeoForge.EVENT_BUS.addListener(MCPirates::onServerStarted);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        var overworld = event.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.warn("[{}] ServerStartedEvent fired but OVERWORLD is null; "
                    + "galleon-unlock mirror left at default (false)", MOD_ID);
            return;
        }
        GalleonUnlockState.get(overworld).hydrateGlobal();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
