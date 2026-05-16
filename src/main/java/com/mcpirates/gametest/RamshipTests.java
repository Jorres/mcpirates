package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.kind.RamshipKind;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end ramship behaviour: place a ramship and a victim {@code airship_small}
 * orthogonally, drive the victim in a straight line, point the ramship's brain at it,
 * and assert the ramship closes to interception distance within the timeout.
 *
 * <p>Two seams the test uses:
 * <ul>
 *   <li>{@link AirshipBrain#targetShipOverride} — pins the victim's SubLevel as the
 *       ramship's target directly, skipping the player-discovery step.
 *   <li>{@link AirshipBrain#navigateTo} — drives the victim deterministically across the
 *       ramship's path so the intercept is exercised rather than dodged.
 * </ul>
 *
 * <p>TODO: extend to two-phase divergence assertion (sample victim trajectory with vs.
 * without ramship, assert phase-2 position differs). Current test only verifies the
 * ramship reaches collision range; a moving target deflected by collision impulse is
 * the next-level signal but needs Sable's contact resolution to be observable here.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RamshipTests {

    /** Horizontal offset where the victim airship_small is placed relative to the ramship
     *  airpad. Large enough that placement doesn't drop blocks on top of the ramship hull,
     *  small enough that the intercept happens within the timeout. */
    private static final int VICTIM_OFFSET_X = 50;
    /** Ticks the test waits between activation+NAVIGATE setup and the intercept check.
     *  ~30 s at 20 tps. Enough for both ships to lift off, stabilise, and for the ramship
     *  to close most of the way. */
    private static final int INTERCEPT_WINDOW_TICKS = 1200;
    /** Pass threshold: horizontal distance between ship centres at the sample tick. The
     *  ramship's hull is ~9 wide and the victim's ~7 — anything below ~12 blocks counts
     *  as overlapping/contact for our purposes. */
    private static final double INTERCEPT_DISTANCE = 15.0;

    private RamshipTests() {}

    @GameTest(template = "ramship", timeoutTicks = INTERCEPT_WINDOW_TICKS + 400,
              setupTicks = 5, batch = "ramship_intercepts_moving_target", skyAccess = true)
    public static void ramshipInterceptsMovingTarget(GameTestHelper helper) {
        AtomicReference<Airship> ramshipRef = new AtomicReference<>();
        AtomicReference<Airship> victimRef = new AtomicReference<>();
        ServerLevel level = helper.getLevel();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    TestSetup.reset(helper);
                    BlockPos ramshipAnchor = findAnchor(helper);
                    if (ramshipAnchor == null) {
                        helper.fail("no ramship anchor BE in arena");
                        return;
                    }
                    // Force-load a generous chunk region around BOTH ship positions.
                    // The victim is VICTIM_OFFSET_X east of the ramship anchor — large
                    // enough to push the victim's anchor chunk to the edge of a ring
                    // centred only on the ramship. Center one ring on each anchor and
                    // make it wide enough to cover orbit / chase drift.
                    ChunkPos ramshipChunk = new ChunkPos(ramshipAnchor);
                    BlockPos victimAnchorEstimate = ramshipAnchor.offset(VICTIM_OFFSET_X, 0, 0);
                    ChunkPos victimChunk = new ChunkPos(victimAnchorEstimate);
                    for (ChunkPos centre : new ChunkPos[]{ramshipChunk, victimChunk}) {
                        for (int dx = -6; dx <= 6; dx++) {
                            for (int dz = -6; dz <= 6; dz++) {
                                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                            }
                        }
                    }

                    // Place victim airship_small east of the ramship.
                    Optional<StructureTemplate> tplOpt = level.getStructureManager()
                            .get(ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "airship_small"));
                    if (tplOpt.isEmpty()) {
                        helper.fail("airship_small template not loaded");
                        return;
                    }
                    AABB bb = helper.getBounds();
                    BlockPos victimOrigin = new BlockPos(
                            (int) Math.floor(bb.minX) + VICTIM_OFFSET_X,
                            (int) Math.floor(bb.minY),
                            (int) Math.floor(bb.minZ));
                    tplOpt.get().placeInWorld(level, victimOrigin, victimOrigin,
                            new StructurePlaceSettings(), level.getRandom(), 2);

                    // Activate both ships.
                    List<BlockPos> anchors = findAnchorsInRadius(helper, ramshipAnchor, 96);
                    if (anchors.size() < 2) {
                        helper.fail("expected 2 anchors (ramship + victim), found " + anchors.size());
                        return;
                    }
                    for (BlockPos a : anchors) {
                        AirshipLiftoffTrigger.activateAnchor(level, a);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        AirshipBrain.ships().size() == 2,
                        "waiting for 2 ships registered, got " + AirshipBrain.ships().size()))
                .thenExecute(() -> {
                    for (Airship a : AirshipBrain.ships()) {
                        if (a.kind instanceof RamshipKind) {
                            ramshipRef.set(a);
                        } else {
                            victimRef.set(a);
                        }
                    }
                    if (ramshipRef.get() == null || victimRef.get() == null) {
                        helper.fail("could not identify both ships by kind: "
                                + AirshipBrain.ships().stream()
                                .map(a -> a.kind.name()).toList());
                        return;
                    }
                    // Post-assembly leftover scan: anything non-air inside the ramship's
                    // glue bbox (parent-world frame) is a hull cell BFS missed — it stays
                    // in the world and the SubLevel rigid body collides with it on rise.
                    Airship ramship = ramshipRef.get();
                    BlockPos lever = ramship.airpadAnchor;
                    BlockPos gMin = RamshipKind.INSTANCE.glueMin();
                    BlockPos gMax = RamshipKind.INSTANCE.glueMax();
                    int x0 = lever.getX() + gMin.getX(), x1 = lever.getX() + gMax.getX();
                    int y0 = lever.getY() + gMin.getY(), y1 = lever.getY() + gMax.getY();
                    int z0 = lever.getZ() + gMin.getZ(), z1 = lever.getZ() + gMax.getZ();
                    int leftover = 0;
                    for (BlockPos p : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
                        if (!level.getBlockState(p).isAir()) {
                            if (leftover < 10) {
                                MCPirates.LOGGER.info(
                                        "[ramship-test] leftover in glue bbox: {} = {}",
                                        p, level.getBlockState(p));
                            }
                            leftover++;
                        }
                    }
                    MCPirates.LOGGER.info(
                            "[ramship-test] post-assembly leftover-non-air count in ramship glue bbox ({},{},{})..({},{},{}): {}",
                            x0, y0, z0, x1, y1, z1, leftover);
                })
                // Wait for both ships to clear LIFTOFF (steady at cruise altitude).
                .thenWaitUntil(() -> {
                    Airship ramship = ramshipRef.get();
                    Airship victim = victimRef.get();
                    long gt = level.getGameTime();
                    if (gt % 40 == 0) {  // log every 2s gametime
                        Vector3d rPos = ramship == null ? null : ramship.subLevel.logicalPose().position();
                        Vector3d vPos = victim == null ? null : victim.subLevel.logicalPose().position();
                        MCPirates.LOGGER.info(String.format(
                                "[ramship-test] t=%d ramship={state=%s, y=%.2f, sl=%s} victim={state=%s, y=%.2f, sl=%s}",
                                gt,
                                ramship == null ? "—" : ramship.state.name(),
                                rPos == null ? Double.NaN : rPos.y,
                                ramship == null ? "—" : (ramship.subLevel.getLevel() == null ? "null-level" : "live"),
                                victim == null ? "—" : victim.state.name(),
                                vPos == null ? Double.NaN : vPos.y,
                                victim == null ? "—" : (victim.subLevel.getLevel() == null ? "null-level" : "live")));
                    }
                    helper.assertTrue(
                            ramship != null && victim != null
                                    && ramship.state != AirshipBrain.State.LIFTOFF
                                    && victim.state != AirshipBrain.State.LIFTOFF,
                            "waiting for both ships to clear LIFTOFF");
                })
                .thenExecute(() -> {
                    // Drive the victim along -X (back toward the ramship) so paths cross.
                    // Destination chosen far past the ramship so victim never "arrives"
                    // and stops mid-window.
                    Airship victim = victimRef.get();
                    Vector3d vPos = victim.subLevel.logicalPose().position();
                    AirshipBrain.navigateTo(victim, vPos.x - 200.0, vPos.z);

                    // Pin the ramship's target to the victim's SubLevel.
                    Airship victimSnapshot = victim;
                    AirshipBrain.targetShipOverride = ignored -> victimSnapshot.subLevel;
                    // Spawn a mock player on the victim's SubLevel so findEnemyPlayerOnAirship
                    // returns non-null and the state machine engages PURSUE. The brain's
                    // decideNextState reads targetPos (player-derived) for distance gating;
                    // without it, the ramship sits in RETURN regardless of the targetShipOverride.
                    net.minecraft.server.level.ServerPlayer mockPlayer =
                            helper.makeMockServerPlayerInLevel();
                    Vector3d vPosNow = victimSnapshot.subLevel.logicalPose().position();
                    mockPlayer.moveTo(vPosNow.x, vPosNow.y, vPosNow.z);
                    AirshipBrain.targetOverride = ignored -> mockPlayer.isAlive() ? mockPlayer : null;
                    MCPirates.LOGGER.info("[ramship-test] victim NAVIGATE to ({}, {}); ramship target pinned to victim subLevel {}",
                            vPos.x - 200.0, vPos.z, victimSnapshot.subLevel.getUniqueId());
                })
                .thenIdle(INTERCEPT_WINDOW_TICKS)
                .thenExecute(() -> {
                    Airship ramship = ramshipRef.get();
                    Airship victim = victimRef.get();
                    Vector3d rPos = ramship.subLevel.logicalPose().position();
                    Vector3d vPos = victim.subLevel.logicalPose().position();
                    double dx = rPos.x - vPos.x;
                    double dz = rPos.z - vPos.z;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    MCPirates.LOGGER.info("[ramship-test] final positions: ramship=({},{},{}) victim=({},{},{}) horizDist={}",
                            String.format("%.1f", rPos.x), String.format("%.1f", rPos.y), String.format("%.1f", rPos.z),
                            String.format("%.1f", vPos.x), String.format("%.1f", vPos.y), String.format("%.1f", vPos.z),
                            String.format("%.2f", dist));
                    if (dist > INTERCEPT_DISTANCE) {
                        helper.fail(String.format(
                                "ramship did not intercept: final horizDist=%.2f > %.2f",
                                dist, INTERCEPT_DISTANCE));
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    private static BlockPos findAnchor(GameTestHelper helper) {
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

    private static List<BlockPos> findAnchorsInRadius(GameTestHelper helper, BlockPos centre, int radius) {
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
}
