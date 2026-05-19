package com.mcpirates.registry;

import com.google.common.collect.ImmutableSet;
import com.mcpirates.MCPirates;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MCPVillagerProfessions {
    public static final DeferredRegister<VillagerProfession> PROFESSIONS =
            DeferredRegister.create(Registries.VILLAGER_PROFESSION, MCPirates.MOD_ID);

    // Cartographer work sound is a placeholder; shipping a custom one isn't worth the asset weight yet.
    public static final DeferredHolder<VillagerProfession, VillagerProfession> SHERIFF =
            PROFESSIONS.register("sheriff", () -> new VillagerProfession(
                    /*name=*/"sheriff",
                    /*heldJobSite=*/holder -> holder.is(MCPPoiTypes.SHERIFF_WORKSTATION.getKey()),
                    /*acquirableJobSite=*/holder -> holder.is(MCPPoiTypes.SHERIFF_WORKSTATION.getKey()),
                    /*requestedItems=*/ImmutableSet.of(),
                    /*secondaryJobSites=*/ImmutableSet.of(),
                    /*workSound=*/SoundEvents.VILLAGER_WORK_CARTOGRAPHER));

    private MCPVillagerProfessions() {}

    public static void register(IEventBus modBus) {
        PROFESSIONS.register(modBus);
    }
}
