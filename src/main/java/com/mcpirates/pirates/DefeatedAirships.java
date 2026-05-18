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
 * Per-level SavedData of defeated-ship anchor positions. Bounty maps query this so
 * unfurls skip already-cleared outposts. Stored as exact BlockPos; {@link #isDefeated}
 * does a 3×3 chunk neighborhood check (outposts straddle chunk boundaries).
 */
public final class DefeatedAirships extends SavedData {

    /** Underscore not colon — Windows rejects ':' in SavedData filenames. */
    public static final String SAVE_NAME = MCPirates.MOD_ID + "_defeated_airships";

    private final Set<BlockPos> anchors = new HashSet<>();
    /** World-scoped boss-bounty pacing counter. */
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

    /** Strict exact-pos lookup; triggers use this so a defeated peer doesn't suppress
     *  its still-living siblings in the same outpost. */
    public boolean containsExact(BlockPos leverWorldPos) {
        return this.anchors.contains(leverWorldPos);
    }

    /** 1-based; returns 1 the first time. */
    public int incrementScrollsUnfurled() {
        this.scrollsUnfurled++;
        this.setDirty();
        return this.scrollsUnfurled;
    }

    public int scrollsUnfurled() {
        return this.scrollsUnfurled;
    }

    /** 3×3 chunk neighborhood check; bounty map uses this to skip the outpost. */
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
