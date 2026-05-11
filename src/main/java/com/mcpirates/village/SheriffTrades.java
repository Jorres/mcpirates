package com.mcpirates.village;

import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPStructureTags;
import com.mcpirates.registry.MCPVillagerProfessions;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

import java.util.List;
import java.util.Optional;

/**
 * Trade-table wiring for {@link MCPVillagerProfessions#SHERIFF}.
 *
 * <h2>v0.1 trade set</h2>
 * <ul>
 *     <li><b>All tiers (1..5)</b> — {@code 1 captain_seal → 10 emeralds}.
 *         The "claim a bounty" side: player turns in a captain's seal for cash.
 *         Registered at every tier because a fresh-summoned sheriff at any level
 *         needs to have at least one offer or its trade GUI silently refuses to open.</li>
 *     <li><b>All tiers (1..5)</b> — {@code 16 emeralds + 1 compass → pirate bounty map}.
 *         The "buy a bounty" side: emeralds for a treasure map pointing at the nearest
 *         pillager outpost (a.k.a. nearest dormant pirate airship). Vanilla
 *         {@link VillagerTrades.TreasureMapForEmeralds} handles the actual map
 *         generation via {@code ServerLevel.findNearestMapStructure} on
 *         {@link MCPStructureTags#PIRATE_OUTPOSTS}; we only supply the tag and the
 *         display name. Compass cost is hard-coded in vanilla's record — see
 *         "no compass requirement" entry in deferred items if it bothers playtesters.</li>
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
 * <h2>Why a custom {@link VillagerTrades.ItemListing} for the seal trade</h2>
 *
 * <p>{@link VillagerTrades.ItemsForEmeralds} sells items <em>to</em> the player,
 * charging emeralds. We want the opposite for the seal trade: player gives the villager
 * a seal, receives emeralds. {@link SealForEmeraldsListing} below is a small inline
 * record that produces that direction directly. The map trade uses vanilla's existing
 * {@link VillagerTrades.TreasureMapForEmeralds} record.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class SheriffTrades {

    private static final int SEAL_FOR_EMERALDS_PRICE = 10;
    private static final int SEAL_FOR_EMERALDS_USES = 12;
    private static final int SEAL_FOR_EMERALDS_XP = 5;
    private static final float SEAL_FOR_EMERALDS_PRICE_MULT = 0.05f;

    private static final int BOUNTY_MAP_EMERALD_COST = 16;
    private static final int BOUNTY_MAP_USES = 12;
    private static final int BOUNTY_MAP_XP = 5;
    /** Translation key set on the resulting filled_map's {@code ITEM_NAME} component.
     *  In 1.21.1 vanilla {@link VillagerTrades.TreasureMapForEmeralds} passes this
     *  string straight into {@code Component.translatable(...)} — NO "filled_map."
     *  prefix is prepended (that behaviour was in earlier MC versions). So the lang
     *  entry in {@code en_us.json} is keyed literally {@code "pirate_bounty"}. */
    private static final String BOUNTY_MAP_DISPLAY_NAME = "pirate_bounty";
    /** Map scale: 0=1:1 (128×128 blocks total), 1=1:2 (256×256), 2=1:4 (512×512 — vanilla
     *  treasure map default), 3=1:8 (1024×1024), 4=1:16 (2048×2048). Pirate outposts
     *  generate sparsely (vanilla pillager-outpost spacing ~32 chunks ≈ 512 blocks
     *  guaranteed gap, often much more) so scale=2 leaves the player as a "dot on the
     *  border" before they've even crossed half the distance to the outpost. Scale 3
     *  covers 1024 blocks — comfortably wraps both the player's starting pos and the
     *  outpost in the painted region. */
    private static final byte BOUNTY_MAP_SCALE = 3;

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
            tierList.add(new BountyMapForEmeralds(
                    BOUNTY_MAP_EMERALD_COST,
                    MCPStructureTags.PIRATE_OUTPOSTS,
                    BOUNTY_MAP_DISPLAY_NAME,
                    MapDecorationTypes.RED_X,
                    BOUNTY_MAP_SCALE,
                    BOUNTY_MAP_USES,
                    BOUNTY_MAP_XP));
        }
        MCPirates.LOGGER.info(
                "SheriffTrades: registered seal->emerald + emerald->map trades at tiers 1..5 for {} "
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
     * Treasure-map listing parametrised over {@code scale}. Vanilla
     * {@link VillagerTrades.TreasureMapForEmeralds} is identical except the scale is
     * hard-coded to 2 (= 512×512 blocks of painted area). Pillager outposts are
     * scattered enough that scale 2 routinely leaves the player off-map until they're
     * already near the destination — see {@link #BOUNTY_MAP_SCALE} for the rationale.
     *
     * <p>Code-shape mirrors vanilla 1:1 so future MC updates to the underlying
     * {@code MapItem.create} / {@code MapItemSavedData.addTargetDecoration} signatures
     * are easy to follow.
     */
    private record BountyMapForEmeralds(
            int emeraldCost,
            TagKey<Structure> destination,
            String displayName,
            Holder<MapDecorationType> destinationType,
            byte scale,
            int maxUses,
            int villagerXp
    ) implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            if (!(trader.level() instanceof ServerLevel serverLevel)) {
                return null;
            }
            BlockPos targetPos = serverLevel.findNearestMapStructure(
                    destination, trader.blockPosition(), /*radius=*/100, /*skipExistingChunks=*/true);
            if (targetPos == null) {
                MCPirates.LOGGER.warn(
                        "BountyMapForEmeralds: findNearestMapStructure returned null for {} from {} — "
                                + "trade silently dropped this tick", destination, trader.blockPosition());
                return null;
            }
            ItemStack map = MapItem.create(
                    serverLevel, targetPos.getX(), targetPos.getZ(),
                    scale, /*trackingPosition=*/true, /*unlimitedTracking=*/true);
            MapItem.renderBiomePreviewMap(serverLevel, map);
            MapItemSavedData.addTargetDecoration(map, targetPos, "+", destinationType);
            map.set(DataComponents.ITEM_NAME, Component.translatable(displayName));
            return new MerchantOffer(
                    new ItemCost(Items.EMERALD, emeraldCost),
                    Optional.of(new ItemCost(Items.COMPASS)),
                    map,
                    maxUses,
                    villagerXp,
                    /*priceMultiplier=*/0.2F);
        }
    }
}
