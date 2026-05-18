package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Block-entity type registrations for mcpirates.
 *
 * <p>{@link #SHIP_ANCHOR} — the metadata BE attached to every {@link MCPBlocks#SHIP_ANCHOR}
 * block. Stores the {@link com.mcpirates.airship.interfaces.AirshipKind} name so
 * {@link com.mcpirates.airship.AirshipLiftoffTrigger} can identify a ship without
 * heuristic geometric matching.
 */
public final class MCPBlockEntityTypes {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MCPirates.MOD_ID);

    public static final Supplier<BlockEntityType<MCPShipAnchorBlockEntity>> SHIP_ANCHOR =
            BLOCK_ENTITY_TYPES.register("ship_anchor", () ->
                    BlockEntityType.Builder
                            .of(MCPShipAnchorBlockEntity::new, MCPBlocks.SHIP_ANCHOR.get())
                            .build(null));

    private MCPBlockEntityTypes() {}

    public static void register(IEventBus modBus) {
        BLOCK_ENTITY_TYPES.register(modBus);
    }
}
