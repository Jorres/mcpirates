package com.mcpirates.worldgen.processors;

import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPStructureProcessorTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reskins blocks in a structure template by family (envelope colors, plank species, ...).
 *
 * <p>JSON config is a map of {@code family-key → list of weighted variant tuples}, e.g.
 * <pre>{@code
 *   "families": {
 *     "envelope": [
 *       { "weight": 1, "variants": ["red", "light_gray"] },
 *       { "weight": 1, "variants": ["red", "gray"] }
 *     ]
 *   }
 * }</pre>
 *
 * <p>Per placement, for each family:
 * <ol>
 *   <li>Scan the template; collect distinct variants present, counting blocks of each.</li>
 *   <li>Sort detected by {@code (-count, family.canonicalOrder())} → primary-first.</li>
 *   <li>Weighted-pick one pool entry (RNG seeded from the piece origin, deterministic per placement).</li>
 *   <li>Positional remap: detected[i] → picked.variants[i]; rebuild block ids via {@link BlockFamily#rebuild}
 *       and copy matching block-state properties so stairs/slabs/etc. keep their orientation.</li>
 * </ol>
 *
 * <p>Errors are loud: codec rejects unknown family keys, unknown variants, and within-family
 * tuple-size disagreement; placement throws if detected variant count ≠ pool tuple size, or
 * if a target block id is not registered.
 */
public final class VariantSwapProcessor extends StructureProcessor {

    private static final Map<String, BlockFamily> KNOWN_FAMILIES = Map.of(
            EnvelopeFamily.INSTANCE.key(), EnvelopeFamily.INSTANCE
    );

    public record Palette(int weight, List<String> variants) {
        public static final Codec<Palette> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(Palette::weight),
                Codec.STRING.listOf().fieldOf("variants").forGetter(Palette::variants)
        ).apply(inst, Palette::new));
    }

    public record FamilyPool(BlockFamily family, List<Palette> palettes) {}

    public static final MapCodec<VariantSwapProcessor> CODEC =
            Codec.unboundedMap(Codec.STRING, Palette.CODEC.listOf())
                    .fieldOf("families")
                    .flatXmap(
                            VariantSwapProcessor::validateAndBuild,
                            proc -> DataResult.success(proc.rawConfig)
                    );

    private final Map<String, List<Palette>> rawConfig;
    private final List<FamilyPool> familyPools;

    private VariantSwapProcessor(Map<String, List<Palette>> raw, List<FamilyPool> pools) {
        this.rawConfig = raw;
        this.familyPools = pools;
    }

    public List<FamilyPool> familyPools() {
        return familyPools;
    }

    /** Build a deterministic variant of this processor that always picks the supplied
     *  palette index in each family. {@code picks[i]} indexes into {@code familyPools.get(i).palettes}.
     *  Used by the {@code mcpirates place_variant} debug command to render every combo. */
    public VariantSwapProcessor withForcedPicks(int[] picks) {
        if (picks.length != familyPools.size()) {
            throw new IllegalArgumentException("forced picks length " + picks.length
                    + " != family count " + familyPools.size());
        }
        Map<String, List<Palette>> raw = new LinkedHashMap<>();
        List<FamilyPool> pools = new ArrayList<>(familyPools.size());
        for (int i = 0; i < familyPools.size(); i++) {
            FamilyPool fp = familyPools.get(i);
            int idx = picks[i];
            if (idx < 0 || idx >= fp.palettes().size()) {
                throw new IllegalArgumentException("family '" + fp.family().key()
                        + "' has " + fp.palettes().size() + " palettes, requested index " + idx);
            }
            Palette chosen = new Palette(1, fp.palettes().get(idx).variants());
            List<Palette> singleton = List.of(chosen);
            raw.put(fp.family().key(), singleton);
            pools.add(new FamilyPool(fp.family(), singleton));
        }
        return new VariantSwapProcessor(raw, pools);
    }

    private static DataResult<VariantSwapProcessor> validateAndBuild(Map<String, List<Palette>> raw) {
        // Preserve declaration order so per-placement RNG sampling is stable across reloads.
        Map<String, List<Palette>> ordered = new LinkedHashMap<>(raw);
        List<FamilyPool> pools = new ArrayList<>(ordered.size());
        for (var e : ordered.entrySet()) {
            BlockFamily family = KNOWN_FAMILIES.get(e.getKey());
            if (family == null) {
                return DataResult.error(() -> "variant_swap: unknown family '" + e.getKey()
                        + "' (known: " + KNOWN_FAMILIES.keySet() + ")");
            }
            List<Palette> palettes = e.getValue();
            if (palettes.isEmpty()) {
                return DataResult.error(() -> "variant_swap: family '" + e.getKey() + "' has no palettes");
            }
            int size = palettes.get(0).variants().size();
            if (size == 0) {
                return DataResult.error(() -> "variant_swap: family '" + e.getKey()
                        + "' palettes must have at least one variant");
            }
            for (Palette p : palettes) {
                if (p.variants().size() != size) {
                    return DataResult.error(() -> "variant_swap: family '" + e.getKey()
                            + "' has palettes of mixed sizes (" + size + " and " + p.variants().size() + ")");
                }
                for (String v : p.variants()) {
                    try {
                        family.validateVariant(v);
                    } catch (IllegalArgumentException ex) {
                        return DataResult.error(ex::getMessage);
                    }
                }
            }
            pools.add(new FamilyPool(family, palettes));
        }
        return DataResult.success(new VariantSwapProcessor(ordered, pools));
    }

    @Override
    public List<StructureBlockInfo> finalizeProcessing(ServerLevelAccessor serverLevel,
                                                       BlockPos offset,
                                                       BlockPos pos,
                                                       List<StructureBlockInfo> originalBlockInfos,
                                                       List<StructureBlockInfo> processedBlockInfos,
                                                       StructurePlaceSettings settings) {
        if (familyPools.isEmpty()) return processedBlockInfos;
        RandomSource rng = RandomSource.create(Mth.getSeed(pos));

        // Build a block-id → block-id remap by family. We pick per family in declaration
        // order, sampling RNG each time, so a family with no detected variants still
        // consumes its roll — keeps palette selection stable across edits to other families.
        Map<ResourceLocation, ResourceLocation> blockRemap = new HashMap<>();
        for (FamilyPool fp : familyPools) {
            Palette picked = pickWeighted(fp.palettes(), rng);
            planRemap(fp, picked, processedBlockInfos, blockRemap);
        }

        if (blockRemap.isEmpty()) return processedBlockInfos;

        List<StructureBlockInfo> result = new ArrayList<>(processedBlockInfos.size());
        for (StructureBlockInfo info : processedBlockInfos) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(info.state().getBlock());
            ResourceLocation newId = blockRemap.get(id);
            if (newId == null) { result.add(info); continue; }
            Block newBlock = BuiltInRegistries.BLOCK.get(newId);
            if (newBlock == Blocks.AIR && !newId.toString().equals("minecraft:air")) {
                throw new IllegalStateException("variant_swap: target block '" + newId
                        + "' is not registered (typo, or owning mod missing)");
            }
            BlockState newState = copyMatchingProperties(info.state(), newBlock.defaultBlockState());
            result.add(new StructureBlockInfo(info.pos(), newState, info.nbt()));
        }

        MCPirates.LOGGER.debug("variant_swap: remapped {} block-id types at piece origin {}",
                blockRemap.size(), pos);
        return result;
    }

    private static void planRemap(FamilyPool fp,
                                  Palette picked,
                                  List<StructureBlockInfo> blocks,
                                  Map<ResourceLocation, ResourceLocation> blockRemap) {
        // Count distinct variants present in this template for this family.
        Map<String, Integer> counts = new HashMap<>();
        for (StructureBlockInfo info : blocks) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(info.state().getBlock());
            fp.family().extractVariant(id).ifPresent(v -> counts.merge(v, 1, Integer::sum));
        }
        if (counts.isEmpty()) return;

        // Primary-first ordering: highest count first, canonical order breaks ties.
        List<String> detected = counts.keySet().stream()
                .sorted(Comparator.<String>comparingInt(v -> -counts.get(v))
                        .thenComparing(fp.family().canonicalOrder()))
                .toList();

        if (detected.size() != picked.variants().size()) {
            throw new IllegalStateException(String.format(
                    "variant_swap: family '%s' palettes are size %d but template has %d distinct variants %s",
                    fp.family().key(), picked.variants().size(), detected.size(), detected));
        }

        for (int i = 0; i < detected.size(); i++) {
            String oldV = detected.get(i);
            String newV = picked.variants().get(i);
            if (oldV.equals(newV)) continue;
            for (StructureBlockInfo info : blocks) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(info.state().getBlock());
                if (fp.family().extractVariant(id).filter(oldV::equals).isPresent()) {
                    blockRemap.putIfAbsent(id, fp.family().rebuild(id, newV));
                }
            }
        }
    }

    private static Palette pickWeighted(List<Palette> palettes, RandomSource rng) {
        int total = 0;
        for (Palette p : palettes) total += p.weight();
        int roll = rng.nextInt(total);
        int acc = 0;
        for (Palette p : palettes) {
            acc += p.weight();
            if (roll < acc) return p;
        }
        return palettes.get(palettes.size() - 1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState copyMatchingProperties(BlockState from, BlockState to) {
        BlockState out = to;
        for (Property prop : from.getProperties()) {
            if (out.hasProperty(prop)) {
                out = out.setValue(prop, from.getValue(prop));
            }
        }
        return out;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return MCPStructureProcessorTypes.VARIANT_SWAP.get();
    }
}
