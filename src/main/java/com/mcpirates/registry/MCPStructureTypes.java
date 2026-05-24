package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import com.mcpirates.worldgen.PermittedShipOutpostStructure;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MCPStructureTypes {

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, MCPirates.MOD_ID);

    public static final DeferredHolder<StructureType<?>, StructureType<PermittedShipOutpostStructure>> PERMITTED_SHIP_OUTPOST =
            STRUCTURE_TYPES.register("permitted_ship_outpost", () -> () -> PermittedShipOutpostStructure.CODEC);

    private MCPStructureTypes() {}

    public static void register(IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
    }
}
