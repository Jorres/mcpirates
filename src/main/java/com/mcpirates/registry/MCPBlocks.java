package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.anchor.MCPShipAnchorBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block registrations for mcpirates.
 *
 * <p>{@link #BOUNTY_BOARD} — purely cosmetic block; serves as the POI anchor block for the
 * {@code mcpirates:sheriff} villager profession ({@link MCPPoiTypes#SHERIFF_WORKSTATION},
 * {@link MCPVillagerProfessions#SHERIFF}). Has no block-entity, no GUI on right-click, no
 * tile behaviour at all. Its only gameplay role is "a sheriff villager will claim this as
 * a workstation and start offering bounty-related trades." Visually a wooden wanted-poster
 * block — see {@code assets/mcpirates/textures/block/bounty_board.png}.
 */
public final class MCPBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MCPirates.MOD_ID);

    public static final DeferredBlock<Block> BOUNTY_BOARD = BLOCKS.registerSimpleBlock(
            "bounty_board",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5f, 6.0f)
                    .sound(SoundType.WOOD));

    /** Invisible metadata block baked into every airship's structure NBT by
     *  {@code tools/build_ships.py}. Holds the kind name in its BE so the lift-off
     *  trigger doesn't have to geometrically guess which ship a lever belongs to.
     *  See {@link com.mcpirates.airship.anchor.MCPShipAnchorBlock} for the visual /
     *  physical properties (no model, no collision, unbreakable). */
    public static final DeferredBlock<Block> SHIP_ANCHOR = BLOCKS.register(
            "ship_anchor",
            () -> new MCPShipAnchorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NONE)
                    .strength(-1.0F, 3600000.0F)
                    .noOcclusion()
                    .noLootTable()
                    .noTerrainParticles()
                    .pushReaction(PushReaction.BLOCK)));

    private MCPBlocks() {}

    /** Block-items live in {@link MCPItems#ITEMS} so they share the same registry order and
     *  show up in the creative tab. Call from {@link MCPItems} after blocks are declared. */
    public static void registerBlockItems() {
        MCPItems.ITEMS.register("bounty_board", () ->
                new BlockItem(BOUNTY_BOARD.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
