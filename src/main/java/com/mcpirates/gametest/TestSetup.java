package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
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
