package com.mcpirates.village;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPVillagerProfessions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Per-sheriff lifetime cap on {@code furled_bounty} sales.
 *
 * <p>Vanilla {@link MerchantOffer} restocks via {@code restock()} on work-day cycles —
 * any "out of stock" state is wiped to zero uses on the next refill. To enforce a
 * cap that survives refills we keep a counter on the villager's persistent NBT
 * (incremented on each successful bounty trade) and re-peg the offer to out-of-stock
 * every {@link #ENFORCEMENT_PERIOD_TICKS} ticks once the counter hits the cap.
 *
 * <p>The tick-based re-peg sits well below noise — one map lookup + one comparison
 * per villager every 5 seconds. Compared to a {@code restock()} mixin it's:
 * (a) zero foreign-code surface to maintain across MC updates, and
 * (b) trivially toggleable (just remove the listener), which is what we want for a
 * gameplay knob that may move.
 *
 * <p>UI side-effect: the trade appears greyed-out as "out of stock" once the cap is
 * hit, identical to vanilla's exhausted-trade rendering. Making it disappear from
 * the GUI entirely is a separate (Phase 4) job — see project README.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class SheriffLifetimeCap {

    // NBT counter key lives in {@link MCPDataKeys#SHERIFF_SCROLLS_SOLD_NBT_KEY}.

    /** How many scrolls a single sheriff is allowed to sell in his entire lifetime. */
    public static final int LIFETIME_CAP = 5;
    /** Re-pegging is idempotent; running it once a second is plenty. */
    private static final int ENFORCEMENT_PERIOD_TICKS = 100;

    private SheriffLifetimeCap() {}

    @SubscribeEvent
    public static void onTrade(TradeWithVillagerEvent event) {
        AbstractVillager merchant = event.getAbstractVillager();
        if (!(merchant instanceof Villager villager)) return;
        if (villager.getVillagerData().getProfession() != MCPVillagerProfessions.SHERIFF.get()) return;
        MerchantOffer offer = event.getMerchantOffer();
        if (!offer.getResult().is(MCPItems.FURLED_BOUNTY.get())) return;

        CompoundTag data = villager.getPersistentData();
        int sold = data.getInt(MCPDataKeys.SHERIFF_SCROLLS_SOLD_NBT_KEY) + 1;
        data.putInt(MCPDataKeys.SHERIFF_SCROLLS_SOLD_NBT_KEY, sold);

        if (sold >= LIFETIME_CAP) {
            // Lock the offer immediately — don't wait for the next enforcement tick.
            // Cosmetically this is identical to vanilla "out of stock", but on the
            // next work-day refill restock() will zero `uses` again; the per-tick
            // enforcement below re-pegs it.
            offer.setToOutOfStock();
            MCPirates.LOGGER.info(
                    "sheriff {} hit lifetime bounty cap ({}/{}), locking trade",
                    villager.getUUID(), sold, LIFETIME_CAP);
        } else {
            MCPirates.LOGGER.info(
                    "sheriff {} sold a bounty scroll ({}/{})",
                    villager.getUUID(), sold, LIFETIME_CAP);
        }
    }

    @SubscribeEvent
    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (villager.tickCount % ENFORCEMENT_PERIOD_TICKS != 0) return;
        if (villager.getVillagerData().getProfession() != MCPVillagerProfessions.SHERIFF.get()) return;

        int sold = villager.getPersistentData().getInt(MCPDataKeys.SHERIFF_SCROLLS_SOLD_NBT_KEY);
        if (sold < LIFETIME_CAP) return;

        // Cap reached — make sure all FURLED_BOUNTY offers in his trade list are
        // pegged out-of-stock. Iterating once a second over ~5 offers is cheap and
        // handles the restock-resets-uses race without any mixin.
        for (MerchantOffer o : villager.getOffers()) {
            if (o.getResult().is(MCPItems.FURLED_BOUNTY.get()) && !o.isOutOfStock()) {
                o.setToOutOfStock();
            }
        }
    }
}
