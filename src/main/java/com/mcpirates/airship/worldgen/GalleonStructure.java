package com.mcpirates.airship.worldgen;

import com.mcpirates.registry.MCPStructureTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
 * Pirate-galleon worldgen structure. Functionally a stripped-down clone of vanilla
 * {@link JigsawStructure} (which is {@code final}, so we can't subclass it), with one
 * addition: {@link #findGenerationPoint} short-circuits to empty when the
 * {@link GalleonUnlockState} flag is false.
 *
 * <h2>Why a custom Structure type</h2>
 *
 * The gameplay design hides the galleon from worldgen until the player unfurls a 5th bounty
 * scroll. Vanilla worldgen has no "global unlock" mechanism for structures; the cleanest hook
 * is at the per-placement decision point ({@code findGenerationPoint}), which means owning the
 * Structure subclass. Until unlock, every grid-cell roll returns empty → no galleons in newly
 * generated chunks. After unlock, the gate becomes transparent and worldgen proceeds normally.
 *
 * <p>Existing chunks generated pre-unlock keep no galleon; that's by design. The bounty map
 * uses {@code findNearestMapStructure(skipExistingChunks=true)} so the player is directed
 * into unexplored territory where the galleon WILL be placed when they arrive.
 *
 * <h2>Codec parity</h2>
 *
 * The JSON fields mirror {@link JigsawStructure}'s minimal-needed subset:
 * {@code start_pool}, {@code size}, {@code start_height}, {@code use_expansion_hack},
 * {@code project_start_to_heightmap}. We omit pool aliases, dimension padding, and liquid
 * settings — they default to vanilla's defaults in the call below. If those ever matter for
 * the galleon, add them to the codec.
 */
public final class GalleonStructure extends Structure {

    public static final MapCodec<GalleonStructure> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            settingsCodec(inst),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool),
            Codec.intRange(0, 20).fieldOf("size").forGetter(s -> s.maxDepth),
            HeightProvider.CODEC.fieldOf("start_height").forGetter(s -> s.startHeight),
            Codec.BOOL.fieldOf("use_expansion_hack").forGetter(s -> s.useExpansionHack),
            Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap")
                    .forGetter(s -> s.projectStartToHeightmap)
    ).apply(inst, GalleonStructure::new));

    private final Holder<StructureTemplatePool> startPool;
    private final int maxDepth;
    private final HeightProvider startHeight;
    private final boolean useExpansionHack;
    private final Optional<Heightmap.Types> projectStartToHeightmap;

    public GalleonStructure(StructureSettings settings,
                            Holder<StructureTemplatePool> startPool,
                            int maxDepth,
                            HeightProvider startHeight,
                            boolean useExpansionHack,
                            Optional<Heightmap.Types> projectStartToHeightmap) {
        super(settings);
        this.startPool = startPool;
        this.maxDepth = maxDepth;
        this.startHeight = startHeight;
        this.useExpansionHack = useExpansionHack;
        this.projectStartToHeightmap = projectStartToHeightmap;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        // The gate — see GalleonUnlockState class doc. Lock-free volatile read, safe from
        // any worldgen worker thread. False (default) → never place. True → vanilla
        // JigsawPlacement runs as in JigsawStructure.findGenerationPoint.
        if (!GalleonUnlockState.isUnlocked()) {
            return Optional.empty();
        }
        ChunkPos chunkPos = ctx.chunkPos();
        int y = startHeight.sample(ctx.random(),
                new WorldGenerationContext(ctx.chunkGenerator(), ctx.heightAccessor()));
        BlockPos origin = new BlockPos(chunkPos.getMinBlockX(), y, chunkPos.getMinBlockZ());
        return JigsawPlacement.addPieces(
                ctx,
                startPool,
                Optional.empty(),
                maxDepth,
                origin,
                useExpansionHack,
                projectStartToHeightmap,
                /*maxDistanceFromCenter=*/80,
                PoolAliasLookup.create(List.of(), origin, ctx.seed()),
                JigsawStructure.DEFAULT_DIMENSION_PADDING,
                JigsawStructure.DEFAULT_LIQUID_SETTINGS);
    }

    @Override
    public StructureType<?> type() {
        return MCPStructureTypes.PIRATE_GALLEON.get();
    }
}
