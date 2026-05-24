package com.mcpirates.worldgen;

import com.mcpirates.registry.MCPStructureTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import java.util.List;
import java.util.Optional;

/**
 * Jigsaw worldgen structure gated by {@link OutpostPermits}. findGenerationPoint returns empty
 * for any chunk not in the permit set (or under an open gate). Permits are stamped by
 * FurledBountyItem at scroll unfurl, one per scroll.
 *
 * <p>Shape mirrors {@link JigsawStructure}'s minimal subset (start_pool, size, start_height,
 * use_expansion_hack, project_start_to_heightmap). The added field {@code permit_key} is an
 * explicit ResourceLocation declaring which permit-set entry this structure consults — by
 * convention the same path as the structure's own ID, but explicit so renames don't silently
 * break the gate.
 */
public final class PermittedShipOutpostStructure extends Structure {

    public static final MapCodec<PermittedShipOutpostStructure> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            settingsCodec(inst),
            ResourceLocation.CODEC.fieldOf("permit_key").forGetter(s -> s.permitKey),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool),
            Codec.intRange(0, 20).fieldOf("size").forGetter(s -> s.maxDepth),
            HeightProvider.CODEC.fieldOf("start_height").forGetter(s -> s.startHeight),
            Codec.BOOL.fieldOf("use_expansion_hack").forGetter(s -> s.useExpansionHack),
            Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap")
                    .forGetter(s -> s.projectStartToHeightmap)
    ).apply(inst, PermittedShipOutpostStructure::new));

    private final ResourceLocation permitKey;
    private final Holder<StructureTemplatePool> startPool;
    private final int maxDepth;
    private final HeightProvider startHeight;
    private final boolean useExpansionHack;
    private final Optional<Heightmap.Types> projectStartToHeightmap;

    public PermittedShipOutpostStructure(StructureSettings settings,
                                         ResourceLocation permitKey,
                                         Holder<StructureTemplatePool> startPool,
                                         int maxDepth,
                                         HeightProvider startHeight,
                                         boolean useExpansionHack,
                                         Optional<Heightmap.Types> projectStartToHeightmap) {
        super(settings);
        this.permitKey = permitKey;
        this.startPool = startPool;
        this.maxDepth = maxDepth;
        this.startHeight = startHeight;
        this.useExpansionHack = useExpansionHack;
        this.projectStartToHeightmap = projectStartToHeightmap;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        ChunkPos chunkPos = ctx.chunkPos();
        OutpostPermits.recordEvaluation(permitKey, chunkPos);
        if (!OutpostPermits.isAllowed(permitKey, chunkPos)) return Optional.empty();
        int y = startHeight.sample(ctx.random(),
                new WorldGenerationContext(ctx.chunkGenerator(), ctx.heightAccessor()));
        BlockPos origin = new BlockPos(chunkPos.getMinBlockX(), y, chunkPos.getMinBlockZ());
        return JigsawPlacement.addPieces(
                ctx, startPool, Optional.empty(), maxDepth, origin, useExpansionHack,
                projectStartToHeightmap, /*maxDistanceFromCenter=*/80,
                PoolAliasLookup.create(List.of(), origin, ctx.seed()),
                JigsawStructure.DEFAULT_DIMENSION_PADDING, JigsawStructure.DEFAULT_LIQUID_SETTINGS);
    }

    @Override
    public StructureType<?> type() {
        return MCPStructureTypes.PERMITTED_SHIP_OUTPOST.get();
    }

    public ResourceLocation permitKey() {
        return permitKey;
    }
}
