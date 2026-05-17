package com.mcpirates.gametest;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.kind.RamshipKind;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ramship intercept: victim airship_small crosses orthogonal to the ramship's nose,
 * brain targets the victim's captain, pass = SubLevel AABBs overlap. Forces a real
 * lead/intercept rather than a tail chase.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RamshipTests {

    private static final int PERP_DISTANCE = 45;
    private static final int VICTIM_WEST_OFFSET = 25;
    private static final int VICTIM_EAST_DESTINATION = 200;
    private static final int TIMEOUT_TICKS = 2600;

    private RamshipTests() {}

    @GameTest(template = "ramship", timeoutTicks = TIMEOUT_TICKS,
              setupTicks = 5, batch = "ramship_intercepts_moving_target", skyAccess = true)
    public static void ramshipInterceptsMovingTarget(GameTestHelper helper) {
        AtomicReference<Airship> ramshipRef = new AtomicReference<>();
        AtomicReference<Airship> victimRef = new AtomicReference<>();
        AtomicReference<Double> closestApproach = new AtomicReference<>(Double.POSITIVE_INFINITY);
        ServerLevel level = helper.getLevel();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    TestSetup.reset(helper);
                    BlockPos ramshipAnchor = TestSetup.findAnchor(helper);
                    if (ramshipAnchor == null) {
                        helper.fail("no ramship anchor BE in arena");
                        return;
                    }
                    BlockPos victimOrigin = new BlockPos(
                            ramshipAnchor.getX() - VICTIM_WEST_OFFSET,
                            ramshipAnchor.getY(),
                            ramshipAnchor.getZ() - PERP_DISTANCE);

                    // Force-load so SubLevels keep ticking past arena bounds.
                    // See [[sable-chunk-ticket-mechanism]].
                    ChunkPos ramshipChunk = new ChunkPos(ramshipAnchor);
                    ChunkPos victimChunk = new ChunkPos(victimOrigin);
                    BlockPos destinationEstimate = new BlockPos(
                            ramshipAnchor.getX() + VICTIM_EAST_DESTINATION,
                            ramshipAnchor.getY(),
                            ramshipAnchor.getZ() - PERP_DISTANCE);
                    ChunkPos destChunk = new ChunkPos(destinationEstimate);
                    for (ChunkPos centre : new ChunkPos[]{ramshipChunk, victimChunk, destChunk}) {
                        for (int dx = -6; dx <= 6; dx++) {
                            for (int dz = -6; dz <= 6; dz++) {
                                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                            }
                        }
                    }

                    Optional<StructureTemplate> tplOpt = level.getStructureManager()
                            .get(ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "airship_small"));
                    if (tplOpt.isEmpty()) {
                        helper.fail("airship_small template not loaded");
                        return;
                    }
                    tplOpt.get().placeInWorld(level, victimOrigin, victimOrigin,
                            new StructurePlaceSettings(), level.getRandom(), 2);

                    List<BlockPos> anchors = TestSetup.findAnchorsInRadius(helper, ramshipAnchor, 96);
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
                    // Anything non-air left in the glue bbox is a hull cell BFS missed —
                    // it stays in the world and blocks the SubLevel from rising.
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
                // Wait for both ships to clear LIFTOFF.
                .thenWaitUntil(() -> {
                    Airship ramship = ramshipRef.get();
                    Airship victim = victimRef.get();
                    long gt = level.getGameTime();
                    if (gt % 40 == 0) {
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
                    Airship victim = victimRef.get();
                    Vector3d vPos = victim.subLevel.logicalPose().position();
                    double destX = vPos.x + (VICTIM_WEST_OFFSET + VICTIM_EAST_DESTINATION);
                    AirshipBrain.navigateTo(victim, destX, vPos.z);

                    // Captain rides victim's SubLevel; its world pos tracks the ship and
                    // Sable.HELPER.getContaining resolves to the victim SubLevel — both
                    // pieces the brain needs from one entity.
                    LivingEntity captain = findCaptain(level, victim);
                    if (captain == null) {
                        helper.fail("no captain found among victim's anchoredEntities (size="
                                + victim.anchoredEntities.size() + ")");
                        return;
                    }
                    AirshipBrain.targetOverride = captain;
                    MCPirates.LOGGER.info(
                            "[ramship-test] victim NAVIGATE east to ({}, {}); ramship targeting victim captain {} at ({},{},{})",
                            destX, vPos.z, captain.getUUID(),
                            captain.getX(), captain.getY(), captain.getZ());
                })
                // First AABB overlap = impact = pass.
                .thenWaitUntil(() -> {
                    Airship ramship = ramshipRef.get();
                    Airship victim = victimRef.get();
                    helper.assertTrue(ramship != null && victim != null,
                            "ship references lost mid-pursuit");

                    BoundingBox3dc rBox = ramship.subLevel.boundingBox();
                    BoundingBox3dc vBox = victim.subLevel.boundingBox();
                    Vector3d rC = rBox.center();
                    Vector3d vC = vBox.center();
                    double dx = rC.x - vC.x;
                    double dz = rC.z - vC.z;
                    double horizDist = Math.sqrt(dx * dx + dz * dz);
                    closestApproach.updateAndGet(prev -> Math.min(prev, horizDist));

                    boolean overlap = rBox.intersects(vBox);

                    long gt = level.getGameTime();
                    if (gt % 40 == 0 || overlap) {
                        // Don't call Sable's RigidBodyHandle velocity getters from this
                        // poll — native Rapier panics if body lookup misses mid-assembly.
                        // See [[feedback_rapier_two_sublevel_window]].
                        String rState = ramship.state.name();
                        String rCtrl = ramship.controls == null ? "—" : ramship.controls.diagnostics(ramship);
                        double yawDeg = Math.toDegrees(ramship.yawRadians());
                        MCPirates.LOGGER.info(String.format(
                                "[ramship-test] t=%d state=%s ramship c=(%.1f,%.1f,%.1f) yaw=%.1f° victim c=(%.1f,%.1f,%.1f) horizDist=%.2f overlap=%s closest=%.2f | %s",
                                gt, rState, rC.x, rC.y, rC.z, yawDeg,
                                vC.x, vC.y, vC.z,
                                horizDist, overlap, closestApproach.get(), rCtrl));
                    }
                    helper.assertTrue(overlap,
                            "waiting for ramship/victim AABB overlap (impact); closestApproach="
                                    + closestApproach.get());
                })
                .thenExecute(() -> MCPirates.LOGGER.info(
                        "[ramship-test] IMPACT observed at t={} (closestApproach={})",
                        level.getGameTime(), closestApproach.get()))
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    private static LivingEntity findCaptain(ServerLevel level, Airship victim) {
        for (AnchoredEntity ae : victim.anchoredEntities) {
            Entity e = level.getEntity(ae.uuid());
            if (e instanceof LivingEntity le && e.getTags().contains(MCPDataKeys.CAPTAIN_TAG)) {
                return le;
            }
        }
        return null;
    }

}
