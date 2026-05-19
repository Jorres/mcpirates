package com.mcpirates.airship.ships.galleon;

import com.mcpirates.MCPirates;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/**
 * Dev-command path for placing a galleon hull (no pad) at fixed coords. The real
 * gameplay placement happens via worldgen — see {@link GalleonStructure} +
 * {@link GalleonUnlockState}; {@code FurledBountyItem} flips the unlock flag on a 5th
 * unfurl and points the bounty map at the nearest yet-to-generate galleon cell.
 *
 * <p>Footprint chunks are force-loaded before placement so {@code placeInWorld} doesn't
 * no-op. {@link JigsawReplacementProcessor} resolves the keel jigsaw → air.
 */
public final class GalleonSpawner {

    private static final ResourceLocation GALLEON_NBT =
            ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "galleon");
    private static final int GALLEON_SIZE_X = 12;
    private static final int GALLEON_SIZE_Z = 28;

    /** Worldgen reads pad jigsaw Y; this only applies to the dev command. */
    private static final int ALTITUDE_ABOVE_GROUND = 35;

    private GalleonSpawner() {}

    /** Land the throttle lever at (centerX, terrain+altitude, centerZ). Returns lever
     *  pos, or null on failure (NBT missing / placeInWorld false). */
    public static BlockPos spawnGalleonAt(ServerLevel level, int centerX, int centerZ) {
        BlockPos anchorNbtDelta = anchorDeltaFromOrigin();
        int originX = centerX - anchorNbtDelta.getX();
        int originZ = centerZ - anchorNbtDelta.getZ();

        // Force-load so placeInWorld doesn't no-op on unloaded chunks.
        int minCX = originX >> 4;
        int maxCX = (originX + GALLEON_SIZE_X) >> 4;
        int minCZ = originZ >> 4;
        int maxCZ = (originZ + GALLEON_SIZE_Z) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }

        // Height query AFTER force-load so the heightmap is valid.
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, centerX, centerZ);
        int originY = groundY + ALTITUDE_ABOVE_GROUND - anchorNbtDelta.getY();
        BlockPos origin = new BlockPos(originX, originY, originZ);

        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(GALLEON_NBT);
        if (templateOpt.isEmpty()) {
            MCPirates.LOGGER.error("galleon NBT not found at {}", GALLEON_NBT);
            return null;
        }
        StructureTemplate template = templateOpt.get();

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false)
                .addProcessor(JigsawReplacementProcessor.INSTANCE);

        boolean ok = template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
        if (!ok) {
            MCPirates.LOGGER.warn("galleon placeInWorld returned false at origin={}", origin);
            return null;
        }
        BlockPos leverWorld = origin.offset(anchorNbtDelta);
        MCPirates.LOGGER.info("placed galleon: origin={} lever={}", origin, leverWorld);
        return leverWorld;
    }

    /** Primary anchor in galleon NBT (left throttle lever). */
    private static BlockPos anchorDeltaFromOrigin() {
        @SuppressWarnings("unused") String sanity = GalleonKind.INSTANCE.name();
        return new BlockPos(4, 8, 13);
    }
}
