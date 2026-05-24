package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import com.mcpirates.worldgen.processors.VariantSwapProcessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MCPStructureProcessorTypes {

    public static final DeferredRegister<StructureProcessorType<?>> TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, MCPirates.MOD_ID);

    public static final DeferredHolder<StructureProcessorType<?>, StructureProcessorType<VariantSwapProcessor>>
            VARIANT_SWAP = TYPES.register("variant_swap", () -> () -> VariantSwapProcessor.CODEC);

    private MCPStructureProcessorTypes() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
