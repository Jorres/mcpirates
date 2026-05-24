package com.mcpirates.worldgen;

import com.mcpirates.MCPirates;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-overworld permits for {@code PermittedShipOutpostStructure}. Two layers:
 * <ul>
 *   <li>{@code permits}: per-chunk allow-list keyed by permit-key (ResourceLocation matching
 *       a structure_set / Structure ID). One scroll unfurl = one permit. The Structure's
 *       {@code findGenerationPoint} consults this lock-free via the static mirror below.</li>
 *   <li>{@code openGates}: forward-compatibility hook for the future "one map opens a kind
 *       globally" mode. When a key is in {@code openGates}, any chunk that is a structure_set
 *       candidate generates the structure naturally. No callsite writes here today.</li>
 * </ul>
 *
 * <p>Threading: SavedData mutation happens on the main thread; worldgen workers read the
 * volatile mirrors. Writers copy-on-publish — readers never observe a partial map.
 */
public final class OutpostPermits extends SavedData {

    public static final String SAVE_NAME = MCPirates.MOD_ID + "_outpost_permits";

    private static volatile Map<ResourceLocation, Set<ChunkPos>> PERMITS_MIRROR = Map.of();
    private static volatile Set<ResourceLocation> OPEN_GATES_MIRROR = Set.of();

    // Per-(key, chunk) record of cells where our findGenerationPoint has been invoked
    // (either returning empty for lack of permit, or placing the structure). Vanilla's
    // StructureCheck caches the result of that call — once cached, a permit issued later
    // is silently ignored. We track our own evaluation set so findUnclaimedCandidate can
    // skip these cells. In-memory only; matches vanilla's own cache scope.
    private static final ConcurrentHashMap<ResourceLocation, Set<ChunkPos>> EVALUATED =
            new ConcurrentHashMap<>();

    private final Map<ResourceLocation, Set<ChunkPos>> permits = new HashMap<>();
    private final Set<ResourceLocation> openGates = new HashSet<>();

    /** Hot path — called from PermittedShipOutpostStructure.findGenerationPoint on
     *  worldgen worker threads. Lock-free volatile reads. */
    public static boolean isAllowed(ResourceLocation permitKey, ChunkPos pos) {
        if (OPEN_GATES_MIRROR.contains(permitKey)) return true;
        Set<ChunkPos> set = PERMITS_MIRROR.get(permitKey);
        return set != null && set.contains(pos);
    }

    /** Records that vanilla's StructureCheck has cached a findGenerationPoint result
     *  for this (key, chunk) pair. Called once per call by the Structure subclass. */
    public static void recordEvaluation(ResourceLocation permitKey, ChunkPos pos) {
        EVALUATED.computeIfAbsent(permitKey, k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    public static boolean isEvaluated(ResourceLocation permitKey, ChunkPos pos) {
        Set<ChunkPos> set = EVALUATED.get(permitKey);
        return set != null && set.contains(pos);
    }

    public static OutpostPermits get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OutpostPermits::new, OutpostPermits::load),
                SAVE_NAME);
    }

    public void permit(ResourceLocation key, ChunkPos pos) {
        if (permits.computeIfAbsent(key, k -> new HashSet<>()).add(pos)) {
            setDirty();
            republishMirror();
            MCPirates.LOGGER.info("permit issued: {} at {}", key, pos);
        }
    }

    /** Forward-compatibility hook — opens a kind globally so every structure_set candidate
     *  generates naturally. Nothing in current code calls this; reserved for the
     *  "one map unlocks worldgen-wide" future mode. */
    public void openGate(ResourceLocation key) {
        if (openGates.add(key)) {
            setDirty();
            republishMirror();
            MCPirates.LOGGER.info("permit gate opened: {}", key);
        }
    }

    public void hydrateGlobal() {
        republishMirror();
        int total = permits.values().stream().mapToInt(Set::size).sum();
        MCPirates.LOGGER.info("outpost permits hydrated: {} cells / {} keys, {} open gates",
                total, permits.size(), openGates.size());
    }

    private void republishMirror() {
        Map<ResourceLocation, Set<ChunkPos>> snap = new HashMap<>();
        for (var e : permits.entrySet()) snap.put(e.getKey(), Set.copyOf(e.getValue()));
        PERMITS_MIRROR = Map.copyOf(snap);
        OPEN_GATES_MIRROR = Set.copyOf(openGates);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag permitsList = new ListTag();
        for (var entry : permits.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putString("key", entry.getKey().toString());
            ListTag positions = new ListTag();
            for (ChunkPos pos : entry.getValue()) positions.add(LongTag.valueOf(pos.toLong()));
            e.put("positions", positions);
            permitsList.add(e);
        }
        tag.put("permits", permitsList);

        ListTag gates = new ListTag();
        for (ResourceLocation key : openGates) gates.add(StringTag.valueOf(key.toString()));
        tag.put("open_gates", gates);
        return tag;
    }

    public static OutpostPermits load(CompoundTag tag, HolderLookup.Provider registries) {
        OutpostPermits p = new OutpostPermits();
        ListTag permitsList = tag.getList("permits", Tag.TAG_COMPOUND);
        for (int i = 0; i < permitsList.size(); i++) {
            CompoundTag e = permitsList.getCompound(i);
            ResourceLocation key = ResourceLocation.parse(e.getString("key"));
            Set<ChunkPos> positions = new HashSet<>();
            ListTag list = e.getList("positions", Tag.TAG_LONG);
            for (int j = 0; j < list.size(); j++) {
                positions.add(new ChunkPos(((LongTag) list.get(j)).getAsLong()));
            }
            p.permits.put(key, positions);
        }
        ListTag gates = tag.getList("open_gates", Tag.TAG_STRING);
        for (int i = 0; i < gates.size(); i++) {
            p.openGates.add(ResourceLocation.parse(gates.getString(i)));
        }
        return p;
    }

    /** Outer-radius cap for the probe-annulus fallback when the spiral exhausts. Far
     *  enough to escape ocean/desert-dominated regions or heavy local exploration; close
     *  enough that the resulting map is still within sailing range of the player. */
    private static final int PROBE_MAX_CHUNKS = 800;
    /** Number of random samples in the probe annulus per call. 50 covers
     *  spiral-exhaust failure modes in >99% of seeds while staying cheap (~50 biome probes). */
    private static final int PROBE_SAMPLES = 50;

    /**
     * Two-phase search for a chunk that can host a permit-gated structure:
     * <ol>
     *   <li>Spiral outward by region (Chebyshev rings), nearest-first, up to
     *       {@code spiralRadiusChunks}. Picks the closest virgin candidate cell
     *       that survives biome + usability filters.</li>
     *   <li>If the spiral exhausts: scatter {@link #PROBE_SAMPLES} probes uniformly
     *       in the annulus from {@code spiralRadiusChunks} out to {@link #PROBE_MAX_CHUNKS}.
     *       Bypasses local biome traps (ocean spawn) and cache-poisoned territory
     *       (heavily explored area) without paying the cost of an exhaustive spiral.</li>
     * </ol>
     * Returns null only if both phases miss — exceedingly rare.
     *
     * <p>Convention: structure_set ResourceLocation == permit key. Probe RNG is
     * seeded deterministically from {@code (worldSeed XOR origin.toLong())} so two
     * scrolls minted at the same position resolve to the same probe sequence (and
     * the already-permitted skip falls through to the next one).
     */
    public static @Nullable ChunkPos findUnclaimedCandidate(
            ServerLevel level, ResourceLocation key, ChunkPos origin, int spiralRadiusChunks) {
        SearchContext ctx = SearchContext.resolve(level, key);
        if (ctx == null) return null;
        Set<ChunkPos> claimed = OutpostPermits.get(level).permits.getOrDefault(key, Set.of());

        ChunkPos near = spiralSearch(ctx, origin, spiralRadiusChunks, claimed);
        if (near != null) {
            MCPirates.LOGGER.info("permit search via=spiral key={} origin={} cell={}",
                    key, origin, near);
            return near;
        }

        ChunkPos far = probeAnnulus(ctx, origin, spiralRadiusChunks, PROBE_MAX_CHUNKS,
                PROBE_SAMPLES, claimed);
        if (far != null) {
            MCPirates.LOGGER.info("permit search via=probe key={} origin={} cell={} "
                            + "(spiral {}c exhausted, hit in probe {}..{}c)",
                    key, origin, far, spiralRadiusChunks, spiralRadiusChunks, PROBE_MAX_CHUNKS);
            return far;
        }

        MCPirates.LOGGER.info("permit search exhausted key={} origin={} "
                        + "(spiral {}c + {} probes in {}..{}c)",
                key, origin, spiralRadiusChunks,
                PROBE_SAMPLES, spiralRadiusChunks, PROBE_MAX_CHUNKS);
        return null;
    }

    private static @Nullable ChunkPos spiralSearch(SearchContext ctx, ChunkPos origin,
                                                    int radiusChunks, Set<ChunkPos> claimed) {
        int spacing = ctx.spread.spacing();
        int regionRadius = Math.max(1, (radiusChunks + spacing - 1) / spacing);
        int originRegionX = Math.floorDiv(origin.x, spacing);
        int originRegionZ = Math.floorDiv(origin.z, spacing);

        for (int r = 0; r <= regionRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ChunkPos candidate = ctx.spread.getPotentialStructureChunk(
                            ctx.seed, originRegionX + dx, originRegionZ + dz);
                    if (acceptableCandidate(ctx, candidate, claimed)) return candidate;
                }
            }
        }
        return null;
    }

    private static @Nullable ChunkPos probeAnnulus(SearchContext ctx, ChunkPos origin,
                                                    int minRChunks, int maxRChunks,
                                                    int samples, Set<ChunkPos> claimed) {
        int spacing = ctx.spread.spacing();
        double minRegions = (double) minRChunks / spacing;
        double maxRegions = (double) maxRChunks / spacing;
        if (maxRegions <= minRegions) return null;
        int originRegionX = Math.floorDiv(origin.x, spacing);
        int originRegionZ = Math.floorDiv(origin.z, spacing);

        // Deterministic per (worldSeed, originChunk) — same scroll position → same probe
        // sequence, which makes test verification possible without sacrificing variance
        // across actual gameplay (different positions → different sequences).
        RandomSource rng = RandomSource.create(ctx.seed ^ origin.toLong());
        double minSq = minRegions * minRegions;
        double maxSq = maxRegions * maxRegions;

        for (int i = 0; i < samples; i++) {
            // Uniform area sampling in the annulus.
            double r = Math.sqrt(rng.nextDouble() * (maxSq - minSq) + minSq);
            double angle = rng.nextDouble() * 2.0 * Math.PI;
            int dx = (int) Math.round(r * Math.cos(angle));
            int dz = (int) Math.round(r * Math.sin(angle));
            ChunkPos candidate = ctx.spread.getPotentialStructureChunk(
                    ctx.seed, originRegionX + dx, originRegionZ + dz);
            if (acceptableCandidate(ctx, candidate, claimed)) return candidate;
        }
        return null;
    }

    private static boolean acceptableCandidate(SearchContext ctx, ChunkPos candidate,
                                                Set<ChunkPos> claimed) {
        if (claimed.contains(candidate)) return false;
        if (isUnusable(ctx.level, ctx.key, candidate)) return false;
        // No isStructureChunk filter on purpose: our structure_sets deliberately omit
        // `frequency` and `exclusion_zone`, so the cell from getPotentialStructureChunk
        // IS always the canonical candidate vanilla would pick.
        // Biome pre-check — match vanilla's isValidBiome (Structure.java:118). Sample at
        // the heightmap-projected Y, not sea level, so coastal cells aren't false-rejected.
        int sampleX = candidate.getMinBlockX();
        int sampleZ = candidate.getMinBlockZ();
        int surfaceY = ctx.level.getChunkSource().getGenerator().getBaseHeight(
                sampleX, sampleZ, Heightmap.Types.WORLD_SURFACE_WG, ctx.level,
                ctx.level.getChunkSource().randomState());
        var biome = ctx.level.getChunkSource().getGenerator().getBiomeSource()
                .getNoiseBiome(
                        QuartPos.fromBlock(sampleX),
                        QuartPos.fromBlock(surfaceY),
                        QuartPos.fromBlock(sampleZ),
                        ctx.level.getChunkSource().randomState().sampler());
        return ctx.structure.biomes().contains(biome);
    }

    private record SearchContext(ServerLevel level, ResourceLocation key, long seed,
                                  RandomSpreadStructurePlacement spread, Structure structure) {
        static @Nullable SearchContext resolve(ServerLevel level, ResourceLocation key) {
            ResourceKey<StructureSet> setKey = ResourceKey.create(Registries.STRUCTURE_SET, key);
            var setOpt = level.registryAccess().registry(Registries.STRUCTURE_SET)
                    .flatMap(r -> r.getOptional(setKey));
            if (setOpt.isEmpty()) {
                MCPirates.LOGGER.warn("findUnclaimedCandidate: no structure_set {}", key);
                return null;
            }
            StructurePlacement placement = setOpt.get().placement();
            if (!(placement instanceof RandomSpreadStructurePlacement spread)) {
                MCPirates.LOGGER.warn("findUnclaimedCandidate: {} placement {} is not random_spread",
                        key, placement.getClass().getSimpleName());
                return null;
            }
            ResourceKey<Structure> structureKey = ResourceKey.create(Registries.STRUCTURE, key);
            var structureOpt = level.registryAccess().registry(Registries.STRUCTURE)
                    .flatMap(r -> r.getOptional(structureKey));
            if (structureOpt.isEmpty()) {
                MCPirates.LOGGER.warn("findUnclaimedCandidate: no structure {}", key);
                return null;
            }
            return new SearchContext(level, key, level.getSeed(), spread, structureOpt.get());
        }
    }

    /** Reject a candidate if it's been touched in any way that prevents a permit from
     *  taking effect. Three signals (any matching → unusable):
     *  <ul>
     *    <li>Chunk in memory (currently loaded — already past STRUCTURE_STARTS).</li>
     *    <li>Chunk on disk (was generated and saved — too late to permit).</li>
     *    <li>Our findGenerationPoint has fired for this (key, chunk) — vanilla's StructureCheck
     *        cached the result; a fresh permit won't trigger a re-check.</li>
     *  </ul>
     */
    private static boolean isUnusable(ServerLevel level, ResourceLocation key, ChunkPos candidate) {
        if (level.hasChunk(candidate.x, candidate.z)) return true;
        try {
            if (level.getChunkSource().chunkMap.read(candidate).join().isPresent()) return true;
        } catch (CompletionException e) {
            MCPirates.LOGGER.warn("chunk-disk probe failed for {}: {}", candidate, e.toString());
        }
        return isEvaluated(key, candidate);
    }
}
