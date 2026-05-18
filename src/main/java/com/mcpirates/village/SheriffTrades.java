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
 * Sheriff trades — both registered at every tier (level-1 sheriffs with empty offers
 * have refused-to-open GUI bugs):
 * <ul>
 *   <li>1 captain_seal → 10 emeralds</li>
 *   <li>16 emeralds + 1 compass → 1 furled_bounty</li>
 * </ul>
 * Outpost selection is deferred to {@link FurledBountyItem#use} so one sheriff can
 * sell many scrolls each pointing at a fresh outpost.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class SheriffTrades {

    private static final int SEAL_FOR_EMERALDS_PRICE = 10;
    private static final int SEAL_FOR_EMERALDS_USES = 12;
    private static final int SEAL_FOR_EMERALDS_XP = 5;
    private static final float SEAL_FOR_EMERALDS_PRICE_MULT = 0.05f;

    private static final int FURLED_BOUNTY_EMERALD_COST = 16;
    /** Refill cap. Lifetime cap lives in {@link SheriffLifetimeCap}. */
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

    /** Villager BUYS 1 captain_seal for N emeralds. */
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

    /** Villager SELLS 1 furled_bounty for emeralds + compass. */
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
