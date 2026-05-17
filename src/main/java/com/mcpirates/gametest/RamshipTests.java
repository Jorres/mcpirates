package com.mcpirates.gametest;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end ramship behaviour: place a ramship and a victim {@code airship_small}
 * orthogonally, drive the victim in a straight line across the ramship's path, point
 * the ramship's brain at the victim's own captain, and assert the two SubLevel
 * bounding boxes intersect (impact) within the timeout.
 *
 * <p>Geometry (top-down, Minecraft NORTH = -Z, EAST = +X):
 * <pre>
 *       victim start -------------- victim end
 *                       (+X path)
 *                            ▲ ramship nose (NORTH/-Z)
 *                            │
 *                            │
 *                          ramship
 * </pre>
 * The victim's east-west path runs at Z = ramshipZ - PERP_DISTANCE (NORTH of the
 * ramship); it starts WEST of the ramship's X and is navigated far past the ramship's
 * X. The ramship's nose points NORTH, so it must turn out of bay and intercept the
 * crossing victim — exercising lead/intercept rather than a tail chase.
 *
 * <p>Target seam: {@link AirshipBrain#targetOverride} — the victim's own captain
 * (a {@code Pillager} stuck to the victim's SubLevel via {@code sable$setPlotPosition}).
 * Captain rides the SubLevel, so {@code captain.getX/Y/Z()} reports the victim's
 * live world position and {@code Sable.HELPER.getContaining(captain)} resolves to the
 * victim's SubLevel — both pieces the brain needs in one entity, no mock player needed,
 * no separate ship override, no proximity-scanner side-effects.
 *
 * <p>Pass condition: ramship and victim SubLevel bounding boxes intersect at any tick.
 * Fail condition: the GameTest global timeout elapses without intersection.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RamshipTests {

    /** Z gap between the ramship's nose row and the victim's east-west path. Large
     *  enough that the orthogonal geometry forces a real intercept (not a placement
     *  overlap or immediate point-blank hit); small enough to close within timeout. */
    private static final int PERP_DISTANCE = 45;
    /** How far WEST of the ramship's X the victim is placed at spawn. */
    private static final int VICTIM_WEST_OFFSET = 25;
    /** How far EAST of the ramship's X the victim is navigated to. Picked far past the
     *  ramship's X so the victim keeps moving for the whole intercept window instead of
     *  arriving and stopping mid-test. */
    private static final int VICTIM_EAST_DESTINATION = 200;
    /** Hard ceiling on the test duration. The GameTest will succeed earlier on the
     *  first tick the SubLevel AABBs overlap. */
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
                    // Place victim NORTH of the ramship and WEST of its X. Its origin
                    // is the airship_small NBT min corner; airship_small NBT is 7×9×12,
                    // forward = NORTH (nose at NBT z=0). With PERP_DISTANCE = 45 we
                    // leave a clear gap of ~33 blocks between the airship_small tail
                    // and the ramship nose, so the placement never drops blocks on the
                    // ramship hull.
                    BlockPos victimOrigin = new BlockPos(
                            ramshipAnchor.getX() - VICTIM_WEST_OFFSET,
                            ramshipAnchor.getY(),
                            ramshipAnchor.getZ() - PERP_DISTANCE);

                    // Force-load chunks around both ships AND the victim's eastward
                    // destination so the SubLevel rigid bodies keep ticking as they drift
                    // past the arena bounds. See [[sable-chunk-ticket-mechanism]].
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
                    // Drive the victim east along +X, staying on the same Z line. The
                    // destination is far past the ramship's X so the victim never
                    // "arrives" and stops mid-window.
                    Airship victim = victimRef.get();
                    Vector3d vPos = victim.subLevel.logicalPose().position();
                    double destX = vPos.x + (VICTIM_WEST_OFFSET + VICTIM_EAST_DESTINATION);
                    AirshipBrain.navigateTo(victim, destX, vPos.z);

                    // Target = victim's captain. Captain is a Pillager stuck to the victim
                    // SubLevel via sable$setPlotPosition, so its world-frame position tracks
                    // the SubLevel automatically and Sable.HELPER.getContaining(captain)
                    // resolves to the victim SubLevel — no mock player, no second override.
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
                // Poll AABB intersection every tick. First overlap = impact = pass.
                // The GameTest global timeout caps the wait if no overlap ever occurs.
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
                        // Yaw is derived from the SubLevel's logical orientation quaternion
                        // (pure Java math), so it's safe to sample mid-tick. We deliberately
                        // do NOT call Sable's RigidBodyHandle.getLinearVelocity /
                        // getAngularVelocity from this poll loop — they go straight into
                        // native Rapier and panic if Rapier's body lookup misses (which can
                        // happen during SubLevel assembly/teardown ticks). See
                        // [[feedback_rapier_two_sublevel_window]].
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

    /** Find the captain pillager among the victim's anchored crew. Captain is the only
     *  member tagged with {@link MCPDataKeys#CAPTAIN_TAG}. */
    private static LivingEntity findCaptain(ServerLevel level, Airship victim) {
        for (AnchoredEntity ae : victim.anchoredEntities) {
            Entity e = level.getEntity(ae.uuid());
            if (e instanceof LivingEntity le && e.getTags().contains(MCPDataKeys.CAPTAIN_TAG)) {
                return le;
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
