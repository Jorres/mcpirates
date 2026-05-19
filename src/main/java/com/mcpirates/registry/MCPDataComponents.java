package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Unit;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class MCPDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MCPirates.MOD_ID);

    public static final Supplier<DataComponentType<Unit>> IS_GALLEON_BOUNTY =
            DATA_COMPONENTS.registerComponentType("is_galleon_bounty", builder -> builder
                    .persistent(Unit.CODEC)
                    .networkSynchronized(StreamCodec.unit(Unit.INSTANCE)));

    private MCPDataComponents() {}

    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
    }
}
