package com.mcpirates.registry;

import com.google.common.collect.ImmutableSet;
import com.mcpirates.MCPirates;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Villager profession registry for mcpirates.
 *
 * <p>{@link #SHERIFF} — the bounty-issuing villager. Workstation is
 * {@link MCPPoiTypes#SHERIFF_WORKSTATION}; trades are wired in
 * {@link com.mcpirates.village.SheriffTrades}. For v0.1 the sheriff offers a single
 * trade: turn in a {@link MCPItems#CAPTAIN_SEAL} for emeralds.
 *
 * <h2>Predicate signatures</h2>
 * {@link VillagerProfession}'s constructor takes two POI predicates:
 * <ol>
 *     <li><b>heldJobSite</b> — POIs the villager will only consider once they already hold
 *         this profession. Used to reclaim a job site after server restart.</li>
 *     <li><b>acquirableJobSite</b> — POIs unemployed villagers will look at to acquire
 *         this profession.</li>
 * </ol>
 * Both point to the same single POI here.
 *
 * <p>Work sound is reused from vanilla cartographer for v0.1 — the sound is small and
 * shipping a custom mcpirates sound event would balloon the PoC scope.
 */
public final class MCPVillagerProfessions {
    public static final DeferredRegister<VillagerProfession> PROFESSIONS =
            DeferredRegister.create(Registries.VILLAGER_PROFESSION, MCPirates.MOD_ID);

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
