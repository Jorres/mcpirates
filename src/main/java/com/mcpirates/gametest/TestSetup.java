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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-test reset + shared helpers. Wipes JVM-static registries (brain ships,
 * ground engagements) and removes any SubLevels still in the parent container
 * from a prior test, and exposes the mock-player / extended-helper utilities
 * we use across the gametest suite.
 *
 * <p>Cross-arena interference is handled by {@code StructureGridSpawnerMixin},
 * which widens the gap between test arenas past
 * {@code AirshipLiftoffTrigger}'s 160-block trigger radius — so no test's
 * production proximity scan reaches into a neighbour arena. This reset only
 * needs to clear the in-JVM state that lives outside the level.
 *
 * <p>SubLevel removal: Sable's {@code GameTestInfoMixin.succeed} cleans
 * SubLevels intersecting the test bbox, but airships that flew past the arena
 * ceiling escape it. We re-sweep here for completeness.
 */
public final class TestSetup {

    /** Simulation distance bumped during mock-player tests so the PLAYER ticket
     *  block-ticks chunks around the player (gametest-server defaults to 0,
     *  which only ticks the chunk the player is standing in). N=4 covers
     *  reasonable orbit/chase bboxes. See [[sable-chunk-ticket-mechanism]]. */
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

    /**
     * Wrap a plain {@link GameTestHelper} into an {@link ExtendedGameTestHelper}
     * so we can call {@link ExtendedGameTestHelper#makeTickingMockServerPlayerInLevel}.
     * NeoForge's {@code @GameTestHolder} hands tests the vanilla helper; the
     * extended subclass is normally only handed out by {@code @TestHolder},
     * which is a bigger surface change. This reflection mirrors the same trick
     * the testframework uses internally in {@code AbstractTest.onGameTest}.
     */
    public static ExtendedGameTestHelper extend(GameTestHelper helper) {
        try {
            Field f = GameTestHelper.class.getDeclaredField("testInfo");
            f.setAccessible(true);
            return new ExtendedGameTestHelper((GameTestInfo) f.get(helper));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("failed to wrap GameTestHelper", e);
        }
    }

    /**
     * Spawn a {@link GameTestPlayer} (testframework's mock {@link ServerPlayer}
     * subclass), pin it at {@code (x, y, z)}, and configure it to behave as a
     * passive position-proxy: no gravity, invulnerable, silent, zero velocity.
     *
     * <p>Uses the 3-arg {@code moveTo(x, y, z)} that {@link GameTestPlayer}
     * overrides to call {@code ServerChunkCache.move(player)} — this refreshes
     * the player's chunk-ticket region, which the 5-arg
     * {@code moveTo(x, y, z, yaw, pitch)} skips. For per-tick re-pinning use
     * {@code player.setPos(x, y, z)} (cheap, no chunk-ticket churn).
     *
     * <p>Caller is responsible for bumping sim/view distance via
     * {@link MinecraftServer#getPlayerList()}'s setters if the brain or any
     * ship physics needs chunks outside the player's immediate cell to tick.
     */
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

    /** Locate the {@link MCPShipAnchorBlockEntity} inside the test arena bounds.
     *  Returns the first anchor found (template-based tests have exactly one
     *  in the source NBT, so order doesn't matter). */
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
