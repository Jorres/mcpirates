package com.mcpirates.pirates;

import com.mcpirates.MCPirates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-{@link ServerLevel} record of airship-anchor block positions whose captain has
 * been killed. The bounty map item ({@code furled_bounty}) consults this on unfurl
 * to skip outposts whose ship is already defeated, so the player keeps getting
 * pointed at fresh targets.
 *
 * <h2>Why a level-attached {@link SavedData}</h2>
 *
 * <p>Defeat is a long-lived per-world fact: the captain can die hours before a player
 * tries to redeem a scroll, the chunk can be unloaded in between, the player can log
 * out and back in. Per-entity NBT dies with the captain; per-block NBT requires the
 * outpost chunk to be loaded. SavedData lives at world-save-root level and survives
 * everything except outright save deletion.
 *
 * <h2>Key choice — BlockPos vs ChunkPos</h2>
 *
 * <p>Stored as exact {@link BlockPos} (= the analog-lever's world position when the
 * airship was assembled). The {@link #isDefeated(BlockPos)} query snaps the supplied
 * outpost-origin position to its {@link ChunkPos} and looks for any stored anchor
 * inside that chunk (or a 3×3 chunk neighbourhood). The block-precision storage
 * leaves headroom for future fanout (e.g. multiple ships per outpost) without
 * migrating data.
 */
public final class DefeatedAirships extends SavedData {

    public static final String SAVE_NAME = MCPirates.MOD_ID + ":defeated_airships";

    /** All known defeated-ship anchor positions in this level. */
    private final Set<BlockPos> anchors = new HashSet<>();
    /** Global counter of bounty scrolls unfurled in this level. Bumped by
     *  {@link com.mcpirates.village.FurledBountyItem} on every successful resolve so it
     *  can decide when to spawn a boss (galleon) instead of pointing at a regular
     *  outpost. World-scoped (one counter per dimension); per-player tracking would
     *  require fanout to player NBT but the gameplay loop doesn't need it. */
    private int scrollsUnfurled = 0;

    public static DefeatedAirships get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DefeatedAirships::new, DefeatedAirships::load),
                SAVE_NAME);
    }

    public boolean markDefeated(BlockPos anchor) {
        boolean added = this.anchors.add(anchor.immutable());
        if (added) this.setDirty();
        return added;
    }

    public int defeatedCount() {
        return this.anchors.size();
    }

    /** Increment and return the new value (1-based: returns 1 the first time it's called). */
    public int incrementScrollsUnfurled() {
        this.scrollsUnfurled++;
        this.setDirty();
        return this.scrollsUnfurled;
    }

    public int scrollsUnfurled() {
        return this.scrollsUnfurled;
    }

    /**
     * @return true if the given outpost-origin position has *any* defeated airship
     * anchor in its chunk or any of the 8 neighbouring chunks. The neighbourhood
     * widens the check so we don't fail to suppress a defeated outpost just because
     * the lever happens to sit in a chunk adjacent to the outpost's reported origin
     * — pillager outposts span ~14 blocks, often straddling chunk boundaries.
     */
    public boolean isDefeated(BlockPos outpostOrigin) {
        ChunkPos centre = new ChunkPos(outpostOrigin);
        for (BlockPos anchor : this.anchors) {
            ChunkPos cp = new ChunkPos(anchor);
            int dx = cp.x - centre.x;
            int dz = cp.z - centre.z;
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) return true;
        }
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (BlockPos pos : this.anchors) {
            list.add(LongTag.valueOf(pos.asLong()));
        }
        tag.put("anchors", list);
        tag.putInt("scrollsUnfurled", this.scrollsUnfurled);
        return tag;
    }

    public static DefeatedAirships load(CompoundTag tag, HolderLookup.Provider registries) {
        DefeatedAirships d = new DefeatedAirships();
        ListTag list = tag.getList("anchors", net.minecraft.nbt.Tag.TAG_LONG);
        for (int i = 0; i < list.size(); i++) {
            d.anchors.add(BlockPos.of(((LongTag) list.get(i)).getAsLong()));
        }
        d.scrollsUnfurled = tag.getInt("scrollsUnfurled");
        return d;
    }
}
