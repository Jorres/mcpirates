package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Structure tags owned by mcpirates.
 *
 * <p>{@link #PIRATE_OUTPOSTS} — the set of structures the {@code mcpirates:sheriff}
 * villager's bounty scroll will treat as a valid target on unfurl. Tag JSON at
 * {@code data/mcpirates/tags/worldgen/structure/pirate_outposts.json}; currently
 * holds just {@code minecraft:pillager_outpost} (every outpost gets the airship
 * since we override the {@code base_plates} pool, so one entry covers everything).
 * Add additional outpost-providing structures here as the modpack grows.
 */
public final class MCPStructureTags {

    public static final TagKey<Structure> PIRATE_OUTPOSTS =
            TagKey.create(Registries.STRUCTURE, MCPirates.id("pirate_outposts"));

    /** Set of structures the boss-bounty (every Nth scroll) path searches with
     *  {@code findNearestMapStructure} after the player unfurls a 5th scroll. Tag JSON at
     *  {@code data/mcpirates/tags/worldgen/structure/pirate_galleons.json}; holds
     *  {@code mcpirates:pirate_galleon}. Separate from {@link #PIRATE_OUTPOSTS} so the
     *  regular bounty flow never accidentally points at a galleon and vice versa. */
    public static final TagKey<Structure> PIRATE_GALLEONS =
            TagKey.create(Registries.STRUCTURE, MCPirates.id("pirate_galleons"));

    private MCPStructureTags() {}
}
