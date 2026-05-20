package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.ships.AirshipKinds;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.simulated_team.simulated.util.SimAssemblyHelper.AssemblyResult;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
        logArena(helper);
        AirshipBrain.unregisterAll(level);
        AirshipBrain.targetOverride = null;
        MinecraftServer server = level.getServer();
        if (server != null) {
            server.getPlayerList().setSimulationDistance(0);
            server.getPlayerList().setViewDistance(0);
        }
        purgeSubLevels(level);
        // Always render jigsaws in the freshly-placed arena — see replaceJigsaws docs.
        // Idempotent: end-of-test reset over an emptied arena is a no-op walk.
        replaceJigsaws(helper);
    }

    /** Print the world coordinates of the arena bbox so test logs can correlate
     *  "test foo started" with "ramship at (x, y, z)" — useful when chasing cross-test
     *  state leaks where the symptom shows up in one test but is caused by another's
     *  leftover entities in nearby chunks. */
    private static void logArena(GameTestHelper helper) {
        AABB bb = helper.getBounds();
        MCPirates.LOGGER.info(
                "[gametest-arena] reset for test arena min=({},{},{}) max=({},{},{})",
                (int) Math.floor(bb.minX), (int) Math.floor(bb.minY), (int) Math.floor(bb.minZ),
                (int) Math.ceil(bb.maxX), (int) Math.ceil(bb.maxY), (int) Math.ceil(bb.maxZ));
    }

    /** Reflection trick mirrored from testframework's AbstractTest.onGameTest. */
    public static ExtendedGameTestHelper extend(GameTestHelper helper) {
        try {
            Field f = GameTestHelper.class.getDeclaredField("testInfo");
            f.setAccessible(true);
            return new ExtendedGameTestHelper((GameTestInfo) f.get(helper));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("failed to wrap GameTestHelper", e);
        }
    }

    /** 3-arg moveTo so chunk tickets refresh; per-tick re-pinning should use setPos. */
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

    /** Place + glue + assemble a ship NBT bypassing all mcpirates code (anchor is
     *  stripped to air). Produces an inert SubLevel platform for the test player. */
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

        // Strip the ship_anchor so mcpirates ignores this contraption.
        com.mcpirates.airship.interfaces.AirshipKind k = AirshipKinds.byName(shipName);
        if (k == null) {
            helper.fail("no kind for " + shipName);
            return null;
        }
        int[] anchorRel = k.nbtSpec().anchorNbtPos();
        BlockPos anchorWorld = origin.offset(anchorRel[0], anchorRel[1], anchorRel[2]);
        level.setBlock(anchorWorld, Blocks.AIR.defaultBlockState(), 3);

        net.minecraft.core.Vec3i size = tpl.getSize();
        AABB glueAabb = new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
        level.addFreshEntity(new HoneyGlueEntity(level, glueAabb));

        // Need a non-air seed; the anchor cell we just cleared is air.
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

    /**
     * Walk the test arena's bbox and rewrite every {@code minecraft:jigsaw} block to its
     * {@code final_state} (falling back to air when the field is empty or unparseable).
     *
     * <p>Real ships will be placed with rendered jigsaws in the world (worldgen drives
     * the assembler via {@code /place jigsaw} or pool expansion), and tests trip over
     * jigsaws that don't become part of the contraption — so by doing that we simulate
     * the real world. Call this before {@code AirshipLiftoffTrigger.activateAnchor} in
     * any test whose arena NBT was authored with raw jigsaw blocks (keel marker,
     * pad-top marker, etc.).
     */
    public static void replaceJigsaws(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AABB bb = helper.getBounds();
        var lookup = BuiltInRegistries.BLOCK.asLookup();
        int replaced = 0, skipped = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(bb.minX), (int) Math.floor(bb.minY), (int) Math.floor(bb.minZ),
                (int) Math.ceil(bb.maxX) - 1, (int) Math.ceil(bb.maxY) - 1, (int) Math.ceil(bb.maxZ) - 1)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof JigsawBlockEntity jbe)) continue;
            String finalStateStr = jbe.getFinalState();
            BlockState target = Blocks.AIR.defaultBlockState();
            if (finalStateStr != null && !finalStateStr.isEmpty()) {
                try {
                    target = BlockStateParser.parseForBlock(lookup, finalStateStr, false).blockState();
                } catch (CommandSyntaxException e) {
                    skipped++;
                    continue;
                }
            }
            level.setBlock(pos, target, Block.UPDATE_ALL);
            replaced++;
        }
        MCPirates.LOGGER.info("[replaceJigsaws] arena={} replaced={} skipped={}",
                bb, replaced, skipped);
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
