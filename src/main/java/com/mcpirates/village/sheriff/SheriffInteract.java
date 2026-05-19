package com.mcpirates.village.sheriff;

import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPVillagerProfessions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-click on a {@code mcpirates:sheriff} villager opens {@link SheriffMenu}
 * instead of falling through to vanilla's {@code Villager.mobInteract} → trade GUI.
 *
 * <p>We cancel the event before vanilla's {@code getOffers()} is consulted, which
 * also dodges the "empty {@code Offers:{}} permanently breaks trade-GUI" footgun
 * documented in the project memory — we never call {@code getOffers()} at all.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class SheriffInteract {

    private SheriffInteract() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager)) return;
        if (villager.getVillagerData().getProfession() != MCPVillagerProfessions.SHERIFF.get()) return;
        if (event.getEntity().isSpectator()) return;
        if (villager.isBaby() || villager.isSleeping()) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        Component title = villager.getDisplayName();
        SimpleMenuProvider provider = new SimpleMenuProvider(
                (id, inv, player) -> new SheriffMenu(id, inv, villager),
                title);
        sp.openMenu(provider, buf -> buf.writeVarInt(villager.getId()));
    }
}
