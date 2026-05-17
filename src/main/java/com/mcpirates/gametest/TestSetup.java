package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.kind.AnchorNbtPositions;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.simulated_team.simulated.util.SimAssemblyHelper.AssemblyResult;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;



/**
 * Per-test reset + shared helpers. Clears JVM-static registries and purges any
 * SubLevels that escaped Sable's bbox sweep (e.g. ships that flew past the arena
 * ceiling). Cross-arena spacing is handled by {@code StructureGridSpawnerMixin}.
 */
public final class TestSetup {

    /** Gametest-server sim distance defaults to 0 (only the player's chunk ticks).
     *  N=4 covers reasonable orbit/chase bboxes. [[sable-chunk-ticket-mechanism]]. */
    public static final int MOCK_PLAYER_SIM_DISTANCE = 4;

    private TestSetup() {}

    public static void reset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AirshipBrain.unregisterAll(level);
        AirshipLiftoffTrigger.clearGroundEngagements(level);
        AirshipBrain.targetOverride = null;
        MinecraftServer server = level.getServer();
        if (server != null) {
            server.getPlayerList().setSimulationDistance(0);
            server.getPlayerList().setViewDistance(0);
        }
        purgeSubLevels(level);
    }

    /** Wrap a vanilla {@link GameTestHelper} into an {@link ExtendedGameTestHelper}
     *  for access to {@code makeTickingMockServerPlayerInLevel}. Mirrors the
     *  reflection trick the testframework uses internally in {@code AbstractTest.onGameTest}. */
    public static ExtendedGameTestHelper extend(GameTestHelper helper) {
        try {
            Field f = GameTestHelper.class.getDeclaredField("testInfo");
            f.setAccessible(true);
            return new ExtendedGameTestHelper((GameTestInfo) f.get(helper));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("failed to wrap GameTestHelper", e);
        }
    }

    /** Pin a {@link GameTestPlayer} at (x,y,z) as a passive position proxy. Uses the
     *  3-arg {@code moveTo} so chunk tickets refresh; the 5-arg variant skips that.
     *  For per-tick re-pinning use {@code setPos} to avoid chunk-ticket churn.
     *  Caller bumps sim/view distance if the brain needs chunks outside the player cell. */
    public static GameTestPlayer spawnPinnedMockPlayer(
            ExtendedGameTestHelper ext, double x, double y, double z) {
        GameTestPlayer player = ext.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
        player.moveTo(x, y, z);
        player.setNoGravity(true);
        player.setInvulnerable(true);
        player.setSilent(true);
        player.setDeltaMovement(0, 0, 0);
        return player;
    }

    /**
     * Drop a ship template, strip its mcpirates anchor, glue it, and have Sable
     * assemble the result into a {@link SubLevel} — all *without* going through any
     * mcpirates code. The resulting SubLevel is a plain Sable contraption with no
     * brain, no crew, no captain — exactly what a real player riding their own
     * (non-pirate) airship looks like to {@code AirshipLiftoffTrigger}.
     *
     * <p>Sequence:
     * <ol>
     *   <li>{@code template.placeInWorld} drops the structure.</li>
     *   <li>Set the {@code mcpirates:ship_anchor} block to air, so neither
     *       {@code AirshipLiftoffTrigger}'s chunk scan nor any later trigger
     *       claims this ship as a pirate.</li>
     *   <li>Spawn a {@link HoneyGlueEntity} covering the structure's bbox.</li>
     *   <li>Call {@link SimAssemblyHelper#assembleFromSingleBlock} on a known
     *       solid cell of the structure. Sable walks the glue, gathers blocks,
     *       and produces a {@link SubLevel}.</li>
     * </ol>
     *
     * <p>Bypassing mcpirates entirely is deliberate: this helper exists to provide
     * an inert SubLevel platform for test players. Going through
     * {@code activateAnchor} would also register a pirate brain + spawn a captain
     * that would attack the test player — fighting the very thing we want to test.
     *
     * @return the assembled SubLevel, or null on failure (helper called {@code fail}).
     */
    public static SubLevel placeAndAssembleAsPassiveTarget(
            GameTestHelper helper, BlockPos origin, String shipName) {
        ServerLevel level = helper.getLevel();
        java.util.Optional<StructureTemplate> tplOpt = level.getStructureManager()
                .get(ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, shipName));
        if (tplOpt.isEmpty()) {
            helper.fail("structure template not loaded: " + shipName);
            return null;
        }
        StructureTemplate tpl = tplOpt.get();
        tpl.placeInWorld(level, origin, origin,
                new StructurePlaceSettings(), level.getRandom(), 2);

        // Strip the ship_anchor so mcpirates ignores this contraption entirely.
        int[] anchorRel = AnchorNbtPositions.BY_NAME.get(shipName);
        if (anchorRel == null) {
            helper.fail("no AnchorNbtPositions entry for " + shipName);
            return null;
        }
        BlockPos anchorWorld = origin.offset(anchorRel[0], anchorRel[1], anchorRel[2]);
        level.setBlock(anchorWorld, Blocks.AIR.defaultBlockState(), 3);

        // Glue + Sable assembly. The glue defines the volume Sable will gather.
        // assembleFromSingleBlock walks glue connectivity from the seed and produces
        // a SubLevel containing every reachable block in that volume.
        net.minecraft.core.Vec3i size = tpl.getSize();
        AABB glueAabb = new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
        level.addFreshEntity(new HoneyGlueEntity(level, glueAabb));

        // Find a non-air seed in the placed structure. assembleFromSingleBlock returns
        // null if toAssemble is air, so we can't reuse the (now-air) anchor cell.
        BlockPos seed = null;
        outer:
        for (int dy = 0; dy < size.getY(); dy++) {
            for (int dx = 0; dx < size.getX(); dx++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) {
                        seed = p;
                        break outer;
                    }
                }
            }
        }
        if (seed == null) {
            helper.fail("no non-air block in placed " + shipName + " to seed assembly from");
            return null;
        }
        try {
            AssemblyResult result = SimAssemblyHelper.assembleFromSingleBlock(
                    level, seed, seed,
                    /*includeStart=*/true,
                    /*includeEncasingGlue=*/true);
            if (result == null || result.subLevel() == null) {
                helper.fail("SimAssemblyHelper.assembleFromSingleBlock returned null for "
                        + shipName + " — glue or block layout invalid");
                return null;
            }
            return result.subLevel();
        } catch (Exception e) {
            helper.fail("SimAssemblyHelper.assembleFromSingleBlock threw " + e);
            return null;
        }
    }

    public static BlockPos findAnchor(GameTestHelper helper) {
        AABB bb = helper.getBounds();
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(bb.minX), (int) Math.floor(bb.minY), (int) Math.floor(bb.minZ),
                (int) Math.ceil(bb.maxX) - 1, (int) Math.ceil(bb.maxY) - 1, (int) Math.ceil(bb.maxZ) - 1)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MCPShipAnchorBlockEntity) {
                return pos.immutable();
            }
        }
        return null;
    }

    public static List<BlockPos> findAnchorsInRadius(GameTestHelper helper, BlockPos centre, int radius) {
        List<BlockPos> anchors = new ArrayList<>();
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(
                centre.getX() - radius, centre.getY() - radius, centre.getZ() - radius,
                centre.getX() + radius, centre.getY() + radius, centre.getZ() + radius)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MCPShipAnchorBlockEntity) {
                anchors.add(pos.immutable());
            }
        }
        return anchors;
    }

    private static void purgeSubLevels(ServerLevel level) {
        ServerSubLevelContainer container =
                (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
        if (container == null) return;
        List<SubLevel> stale = new ArrayList<>(container.getAllSubLevels());
        if (stale.isEmpty()) return;
        MCPirates.LOGGER.info("[gametest-reset] purging {} leftover SubLevel(s)", stale.size());
        for (SubLevel sl : stale) {
            container.removeSubLevel(sl, SubLevelRemovalReason.REMOVED);
        }
    }
}
