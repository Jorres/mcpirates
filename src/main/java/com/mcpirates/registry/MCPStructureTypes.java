package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.ships.galleon.GalleonStructure;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers mcpirates' custom {@link StructureType}s. Currently just
 * {@link #PIRATE_GALLEON} — the gated jigsaw structure type used by the boss-galleon
 * datapack ({@code data/mcpirates/worldgen/structure/pirate_galleon.json} → {@code "type":
 * "mcpirates:pirate_galleon"}).
 *
 * <p>{@code StructureType} is a single-method interface returning a {@code MapCodec};
 * the lambda below registers an instance that hands back our {@link GalleonStructure#CODEC}.
 */
public final class MCPStructureTypes {

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, MCPirates.MOD_ID);

    public static final DeferredHolder<StructureType<?>, StructureType<GalleonStructure>> PIRATE_GALLEON =
            STRUCTURE_TYPES.register("pirate_galleon", () -> () -> GalleonStructure.CODEC);

    private MCPStructureTypes() {}

    public static void register(IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
    }
}
