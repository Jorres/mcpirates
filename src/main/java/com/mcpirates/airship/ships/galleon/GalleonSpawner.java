package com.mcpirates.airship.ships.galleon;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/**
 * Spawn a galleon at a far location relative to a player. Called by every {@value
 * #BOSS_INTERVAL}th bounty unfurl — the galleon is intentionally not in the worldgen
 * pool so it stays a "boss" moment.
 *
 * <p>Footprint chunks are force-loaded before placement so {@code placeInWorld} doesn't
 * no-op. {@link JigsawReplacementProcessor} resolves the keel jigsaw → air.
 *
 * <p>No retry on bad terrain (caller refunds the scroll), no dedup of overlapping bosses.
 */
public final class GalleonSpawner {

    private static final ResourceLocation GALLEON_NBT =
            ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "galleon");
    /** Hull only — pad is the {@code galleon_pad} piece, applied at worldgen. */
    private static final int GALLEON_SIZE_X = 12;
    private static final int GALLEON_SIZE_Z = 28;

    /** Aligned with {@link com.mcpirates.village.SheriffLifetimeCap#LIFETIME_CAP}. */
    public static final int BOSS_INTERVAL = 5;

    private static final int MIN_DISTANCE_BLOCKS = 800;
    private static final int MAX_DISTANCE_BLOCKS = 2000;
    /** Only used by the dev command; worldgen reads pad jigsaw Y instead. */
    private static final int ALTITUDE_ABOVE_GROUND = 35;

    private GalleonSpawner() {}

    /** Random direction, {@link #MIN_DISTANCE_BLOCKS}..{@link #MAX_DISTANCE_BLOCKS} away.
     *  Returns the lever pos, or null on failure. */
    public static BlockPos spawnGalleon(ServerLevel level, Player player) {
        RandomSource rng = level.getRandom();
        double angle = rng.nextDouble() * Math.PI * 2.0;
        int dist = MIN_DISTANCE_BLOCKS + rng.nextInt(MAX_DISTANCE_BLOCKS - MIN_DISTANCE_BLOCKS);
        int centerX = player.blockPosition().getX() + (int) Math.round(Math.cos(angle) * dist);
        int centerZ = player.blockPosition().getZ() + (int) Math.round(Math.sin(angle) * dist);
        BlockPos anchor = spawnGalleonAt(level, centerX, centerZ);
        if (anchor != null) {
            MCPirates.LOGGER.info(
                    "spawned galleon for {}: primaryAnchor={} (random-far placement)",
                    player.getName().getString(), anchor);
        }
        return anchor;
    }

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
        BlockPos anchorWorld = leverWorld.subtract(GalleonKind.INSTANCE.anchorToLeverDelta());
        MCPirates.LOGGER.info("placed galleon: origin={} lever={} anchor={}",
                origin, leverWorld, anchorWorld);

        // Galleon NBT bakes lever already active, so the proximity trigger no-ops.
        // We need an explicit kick.
        boolean activated = AirshipLiftoffTrigger.activateAnchor(level, anchorWorld);
        if (!activated) {
            MCPirates.LOGGER.warn("galleon spawn: activateAnchor returned false at {}", anchorWorld);
        }
        return leverWorld;
    }

    /** Primary anchor in galleon NBT (left throttle lever). */
    private static BlockPos anchorDeltaFromOrigin() {
        @SuppressWarnings("unused") String sanity = GalleonKind.INSTANCE.name();
        return new BlockPos(4, 8, 13);
    }
}
