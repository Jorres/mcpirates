package com.mcpirates.registry;

import com.google.common.collect.ImmutableSet;
import com.mcpirates.MCPirates;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * POI types for mcpirates villager professions.
 *
 * <p>{@link #SHERIFF_WORKSTATION} — POI anchored to {@link MCPBlocks#BOUNTY_BOARD}.
 * Villagers within search range of an unclaimed bounty board take the {@code sheriff}
 * profession (see {@link MCPVillagerProfessions}).
 *
 * <h2>NeoForge handles the {@code TYPE_BY_STATE} side-table for us</h2>
 *
 * <p>In vanilla 1.21.1, {@code PoiTypes.registerBlockStates(Holder, Set<BlockState>)} is
 * package-private and called only from {@code PoiTypes.bootstrap()} during built-in POI
 * registration. Modded POIs registered via {@link DeferredRegister} would, in plain
 * Mojang code, never make it into {@code PoiTypes.TYPE_BY_STATE} — the static map that
 * {@code PoiManager} consults to decide whether a block update is a POI candidate.
 *
 * <p><strong>NeoForge patches this.</strong> When a {@link PoiType} is registered through
 * its registry (and the type's {@code matchingStates} set is non-empty), NeoForge's
 * patches to {@code PoiTypes} automatically call {@code registerBlockStates} during the
 * registry add. <strong>Do not call it ourselves</strong> — empirically that throws
 * {@code IllegalStateException: Block{…} is defined in more than one PoI type} when the
 * NeoForge auto-registration has already added it. An earlier draft of this class hooked
 * {@link net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent} to reflectively invoke
 * the method; the dev client logged that exception at boot. The fix is to do nothing —
 * the {@link DeferredHolder} constructor below is sufficient.
 */
public final class MCPPoiTypes {
    public static final DeferredRegister<PoiType> POI_TYPES =
            DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, MCPirates.MOD_ID);

    /** {@code maxTickets=1}: only one sheriff can claim a given board.
     *  {@code validRange=1}: villagers must be within 1 block to "use" the workstation
     *  (matches vanilla cartographer table). */
    public static final DeferredHolder<PoiType, PoiType> SHERIFF_WORKSTATION =
            POI_TYPES.register("sheriff_workstation", () -> new PoiType(
                    ImmutableSet.copyOf(MCPBlocks.BOUNTY_BOARD.get().getStateDefinition().getPossibleStates()),
                    /*maxTickets=*/1,
                    /*validRange=*/1));

    private MCPPoiTypes() {}

    public static void register(IEventBus modBus) {
        POI_TYPES.register(modBus);
    }
}
