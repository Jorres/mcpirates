package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipBrain.State;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import org.joml.Vector3d;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cannons-on-pirate-ship live-fire test: airship_small reaches PURSUE, fires its single front
 * cannon at a pinned mock player. The player is wrapped in a hollow oak-plank box; the test
 * passes when at least {@link #MIN_PLANKS_BROKEN} planks are missing post-fire, proving the
 * ship-cannon path actually spawns projectiles AND they reach the target arc.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CannonShootingTests {

    private static final double TARGET_OFFSET_X = 15.0;
    private static final double TARGET_Y = 20.0;
    private static final int FORCE_LOAD_RADIUS_CHUNKS = 6;
    /** 5×5×5 hollow box = 5³ − 3³ = 98 plank blocks. Generous catchment for the shot. */
    private static final int BOX_HALF = 2;
    private static final int MIN_PLANKS_BROKEN = 1;
    /** PURSUE entry happens ~1500 ticks after liftoff (airship_small). SingleFrontCannonCombat
     *  fires every 200 ticks → 2400 ticks of shooting time = ~12 shots window. */
    private static final int SHOOTING_WINDOW_TICKS = 2400;

    private CannonShootingTests() {}

    @GameTest(template = "airship_small", timeoutTicks = 5500, setupTicks = 5,
              batch = "cannon_shooting_chips_plank_box", skyAccess = true)
    public static void airshipSmallChipsPlankBox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AtomicReference<Airship> shipRef = new AtomicReference<>();
        AtomicReference<GameTestPlayer> playerRef = new AtomicReference<>();
        AtomicInteger initialPlanks = new AtomicInteger(-1);
        int[] boxCentre = new int[3];

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    TestSetup.reset(helper);
                    BlockPos anchor = TestSetup.findAnchor(helper);
                    if (anchor == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    ChunkPos centre = new ChunkPos(anchor);
                    for (int dx = -FORCE_LOAD_RADIUS_CHUNKS; dx <= FORCE_LOAD_RADIUS_CHUNKS; dx++) {
                        for (int dz = -FORCE_LOAD_RADIUS_CHUNKS; dz <= FORCE_LOAD_RADIUS_CHUNKS; dz++) {
                            level.setChunkForced(centre.x + dx, centre.z + dz, true);
                        }
                    }
                    if (!AirshipLiftoffTrigger.activateAnchor(level, anchor)) {
                        helper.fail("activateAnchor returned false at " + anchor);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        findShipInArena(helper) != null,
                        "waiting for AirshipBrain.register inside arena " + helper.getBounds()))
                .thenExecute(() -> {
                    Airship ship = findShipInArena(helper);
                    shipRef.set(ship);
                    Vector3d shipPos = ship.subLevel.logicalPose().position();
                    double tx = shipPos.x + TARGET_OFFSET_X;
                    double tz = shipPos.z;

                    ExtendedGameTestHelper ext = TestSetup.extend(helper);
                    GameTestPlayer player = TestSetup.spawnPinnedMockPlayer(ext, tx, TARGET_Y, tz);
                    playerRef.set(player);
                    level.getServer().getPlayerList()
                            .setSimulationDistance(TestSetup.MOCK_PLAYER_SIM_DISTANCE);
                    level.getServer().getPlayerList()
                            .setViewDistance(TestSetup.MOCK_PLAYER_SIM_DISTANCE);

                    boxCentre[0] = (int) Math.floor(tx);
                    boxCentre[1] = (int) Math.floor(TARGET_Y);
                    boxCentre[2] = (int) Math.floor(tz);
                    BlockState plank = Blocks.OAK_PLANKS.defaultBlockState();
                    int built = 0;
                    for (int dx = -BOX_HALF; dx <= BOX_HALF; dx++) {
                        for (int dy = -BOX_HALF; dy <= BOX_HALF; dy++) {
                            for (int dz = -BOX_HALF; dz <= BOX_HALF; dz++) {
                                boolean onSurface =
                                        Math.abs(dx) == BOX_HALF
                                        || Math.abs(dy) == BOX_HALF
                                        || Math.abs(dz) == BOX_HALF;
                                if (!onSurface) continue;
                                BlockPos p = new BlockPos(
                                        boxCentre[0] + dx, boxCentre[1] + dy, boxCentre[2] + dz);
                                level.setBlockAndUpdate(p, plank);
                                built++;
                            }
                        }
                    }
                    initialPlanks.set(built);
                    MCPirates.LOGGER.info(String.format(Locale.ROOT,
                            "[shoot-test] arena=%s ship=(%.1f,%.1f,%.1f) target=(%.1f,%.1f,%.1f) box-centre=(%d,%d,%d) box-planks=%d",
                            helper.getBounds(),
                            shipPos.x, shipPos.y, shipPos.z,
                            tx, TARGET_Y, tz,
                            boxCentre[0], boxCentre[1], boxCentre[2], built));
                })
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    helper.assertTrue(ship != null && ship.state == State.PURSUE,
                            "waiting for PURSUE; state="
                                    + (ship == null ? "—" : ship.state));
                })
                .thenExecute(() -> {
                    AirshipBrain.CANNON_FIRE_ENABLED = true;
                    MCPirates.LOGGER.info("[shoot-test] PURSUE reached; CANNON_FIRE_ENABLED=true");
                })
                .thenIdle(SHOOTING_WINDOW_TICKS)
                .thenExecute(() -> {
                    int now = countPlanks(level, boxCentre);
                    int initial = initialPlanks.get();
                    int broken = initial - now;
                    MCPirates.LOGGER.info(String.format(Locale.ROOT,
                            "[shoot-test] planks %d -> %d (broken=%d, threshold=%d)",
                            initial, now, broken, MIN_PLANKS_BROKEN));
                    if (broken < MIN_PLANKS_BROKEN) {
                        helper.fail(String.format(Locale.ROOT,
                                "ship cannons didn't break any planks: %d -> %d in %d ticks",
                                initial, now, SHOOTING_WINDOW_TICKS));
                    }
                })
                .thenExecute(() -> {
                    AirshipBrain.CANNON_FIRE_ENABLED = false;
                    GameTestPlayer p = playerRef.get();
                    if (p != null) p.discard();
                    BlockPos anchor = TestSetup.findAnchor(helper);
                    if (anchor != null) {
                        ChunkPos centre = new ChunkPos(anchor);
                        for (int dx = -FORCE_LOAD_RADIUS_CHUNKS; dx <= FORCE_LOAD_RADIUS_CHUNKS; dx++) {
                            for (int dz = -FORCE_LOAD_RADIUS_CHUNKS; dz <= FORCE_LOAD_RADIUS_CHUNKS; dz++) {
                                level.setChunkForced(centre.x + dx, centre.z + dz, false);
                            }
                        }
                    }
                    TestSetup.reset(helper);
                })
                .thenSucceed();
    }

    private static Airship findShipInArena(GameTestHelper helper) {
        AABB bb = helper.getBounds();
        for (Airship a : AirshipBrain.ships()) {
            double cx = a.airpadAnchor.getX() + 0.5;
            double cy = a.airpadAnchor.getY() + 0.5;
            double cz = a.airpadAnchor.getZ() + 0.5;
            if (cx >= bb.minX && cx < bb.maxX
                    && cy >= bb.minY && cy < bb.maxY
                    && cz >= bb.minZ && cz < bb.maxZ) {
                return a;
            }
        }
        return null;
    }

    private static int countPlanks(ServerLevel level, int[] centre) {
        int count = 0;
        for (BlockPos p : BlockPos.betweenClosed(
                centre[0] - BOX_HALF, centre[1] - BOX_HALF, centre[2] - BOX_HALF,
                centre[0] + BOX_HALF, centre[1] + BOX_HALF, centre[2] + BOX_HALF)) {
            if (level.getBlockState(p).is(Blocks.OAK_PLANKS)) count++;
        }
        return count;
    }
}
