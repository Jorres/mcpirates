package com.mcpirates.airship.ships.galleon;

import com.mcpirates.MCPirates;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-(overworld-level) flag that gates the {@code mcpirates:pirate_galleon} worldgen
 * structure. Default false; flipped true the first time a player unfurls a "boss" (every
 * Nth) bounty scroll. Persists to the dimension's {@code data/} folder via {@link SavedData}.
 *
 * <h2>Thread-safety</h2>
 *
 * Worldgen runs on a {@code ChunkGenerator} worker pool, NOT the main server thread. Vanilla's
 * {@link ServerLevel#getDataStorage} is main-thread only, so {@link GalleonStructure} can't
 * call {@link #get(ServerLevel)} from {@code findGenerationPoint}. Instead, on server start we
 * mirror the persisted boolean into a {@code volatile static} field; worldgen reads that
 * field lock-free. When the flag flips during a session, the {@link #unlock} call updates
 * both the SavedData (for disk) and the mirror (for in-progress chunk generation).
 *
 * <h2>Why per-level (not per-player or per-team)</h2>
 *
 * Galleons live in worldgen which is shared world state. Once any player unfurls a 5th
 * scroll the world's worldgen permits galleons forever — there's no plausible "undo." A
 * second player joining the world inherits the same unlocked state. If/when the design wants
 * per-player gating, the flag becomes per-player NBT and the structure check shifts to a
 * runtime visibility filter on the bounty-map item instead of worldgen.
 */
public final class GalleonUnlockState extends SavedData {

    // Underscore, not colon: SavedData names become file paths on disk and Windows
    // rejects colons. Matches the pattern used by DefeatedAirships.SAVE_NAME.
    public static final String SAVE_NAME = MCPirates.MOD_ID + "_galleon_unlock";

    /** Thread-safe mirror read by worldgen workers. Updated whenever the SavedData unlocks
     *  AND when SavedData is first loaded at server start (via {@link #hydrateGlobal}). */
    private static volatile boolean GLOBAL_UNLOCKED = false;

    private boolean unlocked = false;

    /** Cheap, lock-free check used by {@link GalleonStructure#findGenerationPoint}. Safe to
     *  call from any thread. */
    public static boolean isUnlocked() {
        return GLOBAL_UNLOCKED;
    }

    public static GalleonUnlockState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GalleonUnlockState::new, GalleonUnlockState::load),
                SAVE_NAME);
    }

    /** Main-thread call. Idempotent — flipping when already unlocked is a no-op. */
    public void unlock() {
        if (!this.unlocked) {
            this.unlocked = true;
            setDirty();
            MCPirates.LOGGER.info("galleon worldgen unlocked");
        }
        GLOBAL_UNLOCKED = true;
    }

    /** Push the persisted boolean to the worker-readable mirror. Called once per server
     *  start by the {@code MCPirates} init code so the very first chunk generation after
     *  load sees the correct flag. */
    public void hydrateGlobal() {
        GLOBAL_UNLOCKED = this.unlocked;
        MCPirates.LOGGER.info("galleon unlock state hydrated: {}", this.unlocked);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("unlocked", this.unlocked);
        return tag;
    }

    public static GalleonUnlockState load(CompoundTag tag, HolderLookup.Provider registries) {
        GalleonUnlockState s = new GalleonUnlockState();
        s.unlocked = tag.getBoolean("unlocked");
        return s;
    }
}
