package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import com.mcpirates.village.FurledBountyItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item registrations for mcpirates.
 *
 * <p>{@link #CAPTAIN_SEAL} — trophy item dropped when a {@code mcpirates:pirate_captain}-
 * tagged pillager dies. Traded back to the sheriff villager for emeralds. The item itself
 * carries no NBT — for v0.1 all captain seals are interchangeable. v0.2 may stamp a
 * {@code ship_id} so seals are tied to specific bounty maps.
 *
 * <p>Block-items (e.g. {@code bounty_board}'s item form) are registered here too, via
 * {@link MCPBlocks#registerBlockItems()}, so creative-tab ordering follows registry order.
 */
public final class MCPItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCPirates.MOD_ID);

    public static final DeferredItem<Item> CAPTAIN_SEAL = ITEMS.registerSimpleItem(
            "captain_seal",
            new Item.Properties().stacksTo(16));

    /** Furled-up treasure scroll. Right-click resolves to a bounty map pointing at
     *  the nearest undefeated pillager outpost — see {@link FurledBountyItem}. */
    public static final DeferredItem<FurledBountyItem> FURLED_BOUNTY = ITEMS.register(
            "furled_bounty",
            () -> new FurledBountyItem(new Item.Properties().stacksTo(16)));

    private MCPItems() {}

    public static void register(IEventBus modBus) {
        // Pull in BlockItems so they share registry ordering with regular items. Must run
        // before ITEMS.register(modBus) so the deferred holder is populated.
        MCPBlocks.registerBlockItems();
        ITEMS.register(modBus);
    }
}
