package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Structure tags owned by mcpirates.
 *
 * <p>{@link #PIRATE_OUTPOSTS} — the set of structures the {@code mcpirates:sheriff}
 * villager's bounty-map trade will locate. The tag JSON lives at
 * {@code data/mcpirates/tags/worldgen/structure/pirate_outposts.json}; v0.1 contains
 * just {@code minecraft:pillager_outpost}. Adding {@code kaisyn:*_outpost} (Towns &amp;
 * Towers' variants) is a follow-up — keeping the v0.1 list small avoids accidental
 * leaks into structures we don't actually spawn an airship at.
 */
public final class MCPStructureTags {

    public static final TagKey<Structure> PIRATE_OUTPOSTS =
            TagKey.create(Registries.STRUCTURE, MCPirates.id("pirate_outposts"));

    private MCPStructureTags() {}
}
