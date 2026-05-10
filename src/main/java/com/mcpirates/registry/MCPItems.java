package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MCPItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCPirates.MOD_ID);

    private MCPItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
