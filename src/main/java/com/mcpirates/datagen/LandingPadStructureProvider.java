package com.mcpirates.datagen;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.mcpirates.MCPirates;
import net.minecraft.SharedConstants;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

/**
 * Emits a tiny stone-brick landing pad as a vanilla structure NBT, written to
 * <code>data/mcpirates/structure/airship_landing_pad.nbt</code>. The pad is 5x1x5 with one
 * jigsaw block at the top-center pointing up, named <code>mcpirates:airship_attach</code>,
 * so future pieces (a parked airship) can dock into the same template pool slot later.
 */
public final class LandingPadStructureProvider implements DataProvider {

    private final PackOutput packOutput;

    public LandingPadStructureProvider(PackOutput packOutput) {
        this.packOutput = packOutput;
    }

    @Override
    public String getName() {
        return "MCPirates Structure Templates";
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return CompletableFuture.runAsync(() -> writeNbt(cache,
                ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "airship_landing_pad"),
                buildLandingPad()));
    }

    private void writeNbt(CachedOutput cache, ResourceLocation id, CompoundTag tag) {
        Path path = packOutput.getOutputFolder(PackOutput.Target.DATA_PACK)
                .resolve(id.getNamespace())
                .resolve("structure")
                .resolve(id.getPath() + ".nbt");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            HashingOutputStream hashed = new HashingOutputStream(Hashing.sha1(), bos);
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(hashed))) {
                NbtIo.write(tag, out);
            }
            cache.writeIfNeeded(path, bos.toByteArray(), hashed.hash());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write structure NBT for " + id, e);
        }
    }

    private CompoundTag buildLandingPad() {
        // Pad footprint matches the airship_small NBT (5 wide, 10 deep) so the ship sits
        // perfectly on top of it. No jigsaw — this piece is placed by AirshipNearOutpostFeature
        // at runtime, not stitched into the outpost via worldgen jigsaw.
        final int sizeX = 5;
        final int sizeY = 1;
        final int sizeZ = 10;

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        ListTag size = new ListTag();
        size.add(IntTag.valueOf(sizeX));
        size.add(IntTag.valueOf(sizeY));
        size.add(IntTag.valueOf(sizeZ));
        root.put("size", size);

        ListTag palette = new ListTag();
        CompoundTag stoneBricks = new CompoundTag();
        stoneBricks.putString("Name", "minecraft:stone_bricks");
        palette.add(stoneBricks);
        root.put("palette", palette);

        ListTag blocks = new ListTag();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                blocks.add(blockEntry(0, x, 0, z, null));
            }
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private CompoundTag blockEntry(int paletteIndex, int x, int y, int z, CompoundTag blockEntityNbt) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("state", paletteIndex);
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        entry.put("pos", pos);
        if (blockEntityNbt != null) {
            entry.put("nbt", blockEntityNbt);
        }
        return entry;
    }
}
