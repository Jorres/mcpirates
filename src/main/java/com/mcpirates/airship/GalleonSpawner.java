package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.GalleonKind;
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
 * Programmatically place a galleon ({@code mcpirates:galleon}) at a chosen far location.
 * Called by {@link com.mcpirates.village.FurledBountyItem} when the player unfurls the
 * Nth (currently every 5th) bounty scroll — see {@link #BOSS_INTERVAL}.
 *
 * <p>The galleon is intentionally <em>not</em> in the regular {@code airships}
 * worldgen pool — pillager outposts only spin up small-cruiser-class ships
 * (airship_small, crossbow_board). The galleon arrives exclusively via this
 * spawner so it stays a "boss" moment tied to the sheriff bounty chain.
 *
 * <h2>Placement details</h2>
 *
 * Origin is picked at a random angle, {@link #MIN_DISTANCE_BLOCKS}..{@link #MAX_DISTANCE_BLOCKS}
 * away from the player. Y is the local heightmap MAX + {@link #ALTITUDE_ABOVE_GROUND} so the
 * ship hangs visibly above the trees but well below cloud altitude. We {@code setChunkForced}
 * across the footprint before {@link StructureTemplate#placeInWorld} so the placement doesn't
 * silently no-op on unloaded chunks. The chunks stay force-loaded until the player approaches
 * — could be optimised by un-forcing after place, but the cost is a handful of chunks per
 * outstanding boss bounty which is negligible.
 *
 * <p>{@link JigsawReplacementProcessor} converts the {@code mcpirates:airship_keel} jigsaw
 * (injected at NBT-import time for landing-pad attachment) into its final_state of
 * {@code minecraft:air}; otherwise we'd leave a stray jigsaw block hanging under the galleon.
 *
 * <h2>Limits</h2>
 *
 * <p>No retry / pathfinding around bad terrain — if the chosen XZ lands in a chunk vanilla
 * can't load (e.g. dimension boundary), we fail and the caller refunds the scroll.
 * Improvement candidate: try a handful of randomised offsets like {@code FurledBountyItem}
 * already does for outposts.
 *
 * <p>No de-duplication: a player who unfurls boss scrolls #5, #10, #15… in rapid succession
 * gets multiple galleons in the world simultaneously. The brain handles N ships fine, but
 * gameplay-wise some throttling (e.g. require previous galleon defeated before next can
 * spawn) would feel cleaner. Tracked as a follow-up.
 */
public final class GalleonSpawner {

    private static final ResourceLocation GALLEON_NBT =
            ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "galleon");
    /** Raw galleon NBT footprint: 12 wide × 28 long. The pad is provided by the
     *  separate {@code galleon_pad} parent piece at worldgen time; the dev command
     *  here places only the ship hull at altitude. */
    private static final int GALLEON_SIZE_X = 12;
    private static final int GALLEON_SIZE_Z = 28;

    /** Every Nth unfurl spawns a galleon instead of pointing at a regular outpost. Aligned
     *  with {@link com.mcpirates.village.SheriffLifetimeCap#LIFETIME_CAP} so the player gets
     *  one boss per fully-exhausted sheriff. */
    public static final int BOSS_INTERVAL = 5;

    private static final int MIN_DISTANCE_BLOCKS = 800;
    private static final int MAX_DISTANCE_BLOCKS = 2000;
    /** Dev command spawns the bare galleon hull this many blocks above terrain. The
     *  real worldgen-placed galleon gets its altitude from the pad's jigsaw Y, not
     *  this constant — they're independent. */
    private static final int ALTITUDE_ABOVE_GROUND = 35;

    private GalleonSpawner() {}

    /**
     * Spawn a galleon at a random far location relative to {@code player}. Convenience
     * wrapper around {@link #spawnGalleonAt} — picks a random direction +
     * {@link #MIN_DISTANCE_BLOCKS}..{@link #MAX_DISTANCE_BLOCKS} radius.
     *
     * @return the world position of the galleon's primary anchor (throttle lever) on
     *         success. Null if placement couldn't proceed.
     */
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

    /**
     * Deterministic placement at a chosen XZ. The galleon's primary anchor (throttle
     * lever) lands at ({@code centerX}, terrain + altitude, {@code centerZ}). Used by
     * both the random-far {@link #spawnGalleon} path and the {@code /mcpirates galleon
     * spawn} dev command.
     *
     * @return world position of the primary anchor on success; null if NBT missing or
     *         {@link StructureTemplate#placeInWorld} failed.
     */
    public static BlockPos spawnGalleonAt(ServerLevel level, int centerX, int centerZ) {
        // Galleon NBT primary anchor — kept in sync with the kind by anchorDeltaFromOrigin.
        BlockPos anchorNbtDelta = anchorDeltaFromOrigin();

        // We want the throttle lever to land at (centerX, _, centerZ). origin (NBT 0,0,0)
        // is therefore offset from center by -anchorNbtDelta in X/Z.
        int originX = centerX - anchorNbtDelta.getX();
        int originZ = centerZ - anchorNbtDelta.getZ();

        // Force-load the footprint chunks so setBlock doesn't no-op on unloaded ones.
        int minCX = originX >> 4;
        int maxCX = (originX + GALLEON_SIZE_X) >> 4;
        int minCZ = originZ >> 4;
        int maxCZ = (originZ + GALLEON_SIZE_Z) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }

        // Height query AFTER force-load so the heightmap is valid. The dev command
        // places only the bare galleon hull (no pad — that's the worldgen path's job),
        // so we offset upward by ALTITUDE_ABOVE_GROUND to make the ship hover visibly
        // above the terrain instead of clipping into it.
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
        // anchorToLeverDelta = (0, 0, +1) per GalleonKind: anchor = lever - delta.
        BlockPos anchorWorld = leverWorld.subtract(GalleonKind.INSTANCE.anchorToLeverDelta());
        MCPirates.LOGGER.info("placed galleon: origin={} lever={} anchor={}",
                origin, leverWorld, anchorWorld);

        // Immediately fire the activation pipeline. The galleon NBT ships its
        // throttle lever already at the activated state (it's meant to be
        // an in-flight boss, not a dormant outpost ship), so the proximity
        // trigger by itself does nothing — assembly + crew spawn need an
        // explicit kick from the spawner.
        boolean activated = AirshipLiftoffTrigger.activateAnchor(level, anchorWorld);
        if (!activated) {
            MCPirates.LOGGER.warn("galleon spawn: activateAnchor returned false at {}", anchorWorld);
        }
        return leverWorld;
    }

    /** Raw galleon NBT primary anchor: (3, 9, 14). The galleon ship NBT is just the
     *  ship — pad is provided by the separate {@code galleon_pad} piece at worldgen
     *  time, not baked in. The kind stores deltas <em>relative to this anchor</em>. */
    private static BlockPos anchorDeltaFromOrigin() {
        @SuppressWarnings("unused") String sanity = GalleonKind.INSTANCE.name();
        return new BlockPos(3, 9, 14);
    }
}
