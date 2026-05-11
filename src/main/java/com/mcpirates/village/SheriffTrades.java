package com.mcpirates.village;

import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPVillagerProfessions;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

import java.util.List;
import java.util.Optional;

/**
 * Trade-table wiring for {@link MCPVillagerProfessions#SHERIFF}.
 *
 * <h2>v0.2 trade set</h2>
 * <ul>
 *     <li><b>All tiers (1..5)</b> — {@code 1 captain_seal → 10 emeralds}.
 *         The "claim a bounty" side: player turns in a captain's seal for cash.
 *         Registered at every tier because a fresh-summoned sheriff at any level
 *         needs to have at least one offer or its trade GUI silently refuses to open.</li>
 *     <li><b>All tiers (1..5)</b> — {@code 16 emeralds + 1 compass → 1 furled_bounty}.
 *         The "buy a bounty" side: emeralds for a *furled* bounty scroll. Outpost
 *         selection happens at right-click time inside
 *         {@link FurledBountyItem#use}, not here — so the same sheriff can sell
 *         many scrolls over his lifetime and each unfurls to a different undefeated
 *         outpost depending on where the player is when they break the seal.</li>
 * </ul>
 *
 * <h2>Why both at every tier</h2>
 *
 * <p>An earlier version put trades only at tier 1, and {@code /summon}-ing a sheriff at
 * level&nbsp;&gt;1 produced a villager with no offers, gui refusing to open. The fix
 * (register at every tier) trades a touch of design purity for not-debugging-this-again
 * resilience. If/when we want a real tier progression (novice can only sell seals;
 * master also offers rare loot maps), this scaffolding is what we'd revisit.
 *
 * <h2>Why scroll-then-unfurl instead of pre-baked map</h2>
 *
 * <p>v0.1 issued a {@code filled_map} at trade-gen time, pointing at the outpost
 * closest to the villager. Every map a given sheriff ever sold targeted the same
 * outpost. v0.2 defers outpost selection to {@link FurledBountyItem}; the trade
 * is just a paper-stack sale. See {@link FurledBountyItem} class doc for the
 * defeat-skipping + retry logic that runs at unfurl time.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class SheriffTrades {

    private static final int SEAL_FOR_EMERALDS_PRICE = 10;
    private static final int SEAL_FOR_EMERALDS_USES = 12;
    private static final int SEAL_FOR_EMERALDS_XP = 5;
    private static final float SEAL_FOR_EMERALDS_PRICE_MULT = 0.05f;

    private static final int FURLED_BOUNTY_EMERALD_COST = 16;
    /** maxUses=12 lets a single sheriff sell 12 scrolls per refill cycle. Each
     *  scroll picks its own outpost at unfurl time, so re-selling the *same* trade
     *  to the same player still yields fresh targets — no fixed cap needed here.
     *  Phase 3 will overlay a per-villager lifetime cap on top of this number. */
    private static final int FURLED_BOUNTY_USES = 12;
    private static final int FURLED_BOUNTY_XP = 5;
    private static final float FURLED_BOUNTY_PRICE_MULT = 0.05f;

    private SheriffTrades() {}

    @SubscribeEvent
    public static void onVillagerTrades(VillagerTradesEvent event) {
        if (event.getType() != MCPVillagerProfessions.SHERIFF.get()) {
            return;
        }
        Int2ObjectMap<List<VillagerTrades.ItemListing>> trades = event.getTrades();
        // Both trades registered at every tier — see class doc for the rationale.
        for (int tier = 1; tier <= 5; tier++) {
            List<VillagerTrades.ItemListing> tierList = trades.get(tier);
            if (tierList == null) {
                MCPirates.LOGGER.warn(
                        "SheriffTrades: tier {} list missing from event.getTrades() — skipping", tier);
                continue;
            }
            tierList.add(new SealForEmeraldsListing(
                    SEAL_FOR_EMERALDS_PRICE,
                    SEAL_FOR_EMERALDS_USES,
                    SEAL_FOR_EMERALDS_XP,
                    SEAL_FOR_EMERALDS_PRICE_MULT));
            tierList.add(new FurledBountyForEmeralds(
                    FURLED_BOUNTY_EMERALD_COST,
                    FURLED_BOUNTY_USES,
                    FURLED_BOUNTY_XP,
                    FURLED_BOUNTY_PRICE_MULT));
        }
        MCPirates.LOGGER.info(
                "SheriffTrades: registered seal->emerald + emerald->furled_bounty trades at tiers 1..5 for {} "
                        + "(trades-map size now: t1={} t2={} t3={} t4={} t5={})",
                event.getType(),
                trades.getOrDefault(1, List.of()).size(),
                trades.getOrDefault(2, List.of()).size(),
                trades.getOrDefault(3, List.of()).size(),
                trades.getOrDefault(4, List.of()).size(),
                trades.getOrDefault(5, List.of()).size());
    }

    /**
     * Trade listing where the villager BUYS captain_seal from the player for emeralds.
     * Cost slot (player pays) = 1 captain_seal; result slot (villager pays) = N emeralds.
     */
    private record SealForEmeraldsListing(int emeraldCount, int maxUses, int xp, float priceMult)
            implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            return new MerchantOffer(
                    new ItemCost(MCPItems.CAPTAIN_SEAL.get(), 1),
                    new ItemStack(Items.EMERALD, emeraldCount),
                    maxUses,
                    xp,
                    priceMult);
        }
    }

    /**
     * Trade listing where the villager SELLS one {@code furled_bounty} scroll for
     * {@code emeralds + compass}. No outpost lookup at trade-gen time — the scroll
     * is dumb paper until unfurled. Compass cost mirrors vanilla treasure maps to
     * keep the "you need a compass to make sense of this" flavour.
     */
    private record FurledBountyForEmeralds(int emeraldCost, int maxUses, int xp, float priceMult)
            implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            return new MerchantOffer(
                    new ItemCost(Items.EMERALD, emeraldCost),
                    Optional.of(new ItemCost(Items.COMPASS)),
                    new ItemStack(MCPItems.FURLED_BOUNTY.get(), 1),
                    maxUses,
                    xp,
                    priceMult);
        }
    }
}
