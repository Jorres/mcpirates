package com.mcpirates.gametest;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.ShipTelemetry;
import com.mcpirates.airship.ships.ramship.RamshipKind;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.neoforged.testframework.gametest.GameTestPlayer;
import org.joml.Quaterniond;
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
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import org.joml.Vector3d;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ramship intercept: victim airship_small crosses orthogonal to the ramship's nose,
 * brain targets the victim's captain. Pass = two successive collisions (ramship hits,
 * retreats, the brain re-engages with the now-perpendicular-moving victim, ramship
 * hits again). Each collision is detected as a sudden drop in ramship forward speed
 * — the same downstream signal {@link com.mcpirates.airship.ships.ramship.RamControls}
 * uses to arm retreat, so the test validates the full end-to-end loop.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RamshipTests {

    private static final int PERP_DISTANCE = 45;
    private static final int VICTIM_WEST_OFFSET = 25;
    private static final int VICTIM_EAST_DESTINATION = 200;
    /** South offset for the post-collision perpendicular leg. 80 keeps us within the
     *  setup-time force-load region while pulling the victim well off its old line. */
    private static final int VICTIM_PERPENDICULAR_OFFSET = 80;
    /** Collision detector arms only after we see this much forward speed — confirms the
     *  ramship is actually charging the victim, not coasting from an earlier state. */
    private static final double CHARGING_MIN_FWD_SPEED = 0.10;
    /** Threshold for "ramship is now in its retreat phase" — RamControls drives all three
     *  propellers in reverse for 120 ticks after detecting impact, so cruise-reverse forward
     *  speed projects to roughly -0.3 b/tick. -0.05 is comfortably negative without false
     *  positives from physics jitter or yaw transitions. */
    private static final double RETREAT_FWD_SPEED = -0.05;
    private static final int TIMEOUT_TICKS = 4500;

    private RamshipTests() {}

    @GameTest(template = "ramship", timeoutTicks = TIMEOUT_TICKS,
              setupTicks = 5, batch = "ramship_intercepts_moving_target", skyAccess = true)
    public static void ramshipInterceptsMovingTarget(GameTestHelper helper) {
        AtomicReference<Airship> ramshipRef = new AtomicReference<>();
        AtomicReference<Airship> victimRef = new AtomicReference<>();
        // Collision cycle state machine, shared across both wait phases:
        //   AWAIT  → ramship.fwd > CHARGING_MIN          → CHARGE
        //   CHARGE → bbox(ramship) intersects bbox(victim) → flag contact
        //   CHARGE → ramship.fwd < RETREAT_FWD_SPEED      → if contacted: count++ → AWAIT
        // A collision is the full triple — charged, made contact, then ended up retreating.
        // Mirrors what RamControls actually does internally (bbox-overlap detection arms the
        // retreat phase) so the test moves in lock-step with the production code's invariants.
        int[] collisionCount = {0};
        boolean[] armed = {false};         // ramship has built up charge speed
        boolean[] bboxContact = {false};   // SubLevel bbox overlap observed this cycle
        double[] lastFwdSpeed = {Double.NaN};  // kept for diagnostic logging only
        long[] firstImpactTick = {-1};
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
                    // Arena pinned at origin, no worldgen rotation; layout resolves directly
                    // against the lever world pos.
                    com.mcpirates.airship.interfaces.Layout glue =
                            RamshipKind.INSTANCE.layoutAt(net.minecraft.world.level.block.Rotation.NONE,
                                                          ramship.airpadAnchor);
                    BlockPos gMin = glue.glueMin();
                    BlockPos gMax = glue.glueMax();
                    int x0 = gMin.getX(), x1 = gMax.getX();
                    int y0 = gMin.getY(), y1 = gMax.getY();
                    int z0 = gMin.getZ(), z1 = gMax.getZ();
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
                // First collision: ramship forward speed drops sharply (collision impulse).
                .thenWaitUntil(() ->
                        pollCollision(helper, level, ramshipRef, victimRef,
                                armed, bboxContact, lastFwdSpeed, collisionCount, 1))
                .thenExecute(() -> {
                    firstImpactTick[0] = level.getGameTime();
                    // The state machine is in AWAIT after a successful collision-count;
                    // explicitly reset to be defensive about any partial state.
                    armed[0] = false;
                    bboxContact[0] = false;
                    Airship victim = victimRef.get();
                    Vector3d vPos = victim.subLevel.logicalPose().position();
                    double newDestX = vPos.x;
                    double newDestZ = vPos.z + VICTIM_PERPENDICULAR_OFFSET;

                    // Force-load the southward leg so the victim's SubLevel keeps ticking
                    // outside the setup-time loaded band.
                    ChunkPos newDestChunk = new ChunkPos(
                            new BlockPos((int) newDestX, 0, (int) newDestZ));
                    for (int dx = -5; dx <= 5; dx++) {
                        for (int dz = -5; dz <= 5; dz++) {
                            level.setChunkForced(newDestChunk.x + dx, newDestChunk.z + dz, true);
                        }
                    }

                    AirshipBrain.navigateTo(victim, newDestX, newDestZ);
                    MCPirates.LOGGER.info(
                            "[ramship-test] first impact at t={}; victim re-routed perpendicular to ({}, {})",
                            firstImpactTick[0],
                            String.format("%.1f", newDestX),
                            String.format("%.1f", newDestZ));
                })
                // Second collision after retreat + re-engage along the perpendicular leg.
                .thenWaitUntil(() ->
                        pollCollision(helper, level, ramshipRef, victimRef,
                                armed, bboxContact, lastFwdSpeed, collisionCount, 2))
                .thenExecute(() -> MCPirates.LOGGER.info(
                        "[ramship-test] SECOND IMPACT at t={} (gap since first={} ticks)",
                        level.getGameTime(), level.getGameTime() - firstImpactTick[0]))
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    /** Sample the ramship's cycle and increment the collision count on each completed
     *  CHARGE → CONTACT → RETREAT triple. Fails the wait until {@code targetCount}
     *  collisions have been seen. Shared by both wait phases — phase 1 waits for count≥1,
     *  phase 2 for count≥2.
     *
     *  <p>The detection mirrors {@link com.mcpirates.airship.ships.ramship.RamControls}'s
     *  internal logic: real bbox overlap with the victim SubLevel proves contact, then the
     *  retreat phase (signed forward speed driven negative by RamControls' all-reverse
     *  propellers) confirms the controller actually fired its retreat response. */
    private static void pollCollision(GameTestHelper helper, ServerLevel level,
                                      AtomicReference<Airship> ramshipRef,
                                      AtomicReference<Airship> victimRef,
                                      boolean[] armed, boolean[] bboxContact,
                                      double[] lastFwdSpeed, int[] collisionCount,
                                      int targetCount) {
        Airship ramship = ramshipRef.get();
        Airship victim = victimRef.get();
        helper.assertTrue(ramship != null && victim != null,
                "ship references lost mid-pursuit");

        double fwd = forwardSpeed(ramship);
        boolean overlap = boxesOverlap(ramship, victim);

        if (!armed[0]) {
            if (fwd > CHARGING_MIN_FWD_SPEED) armed[0] = true;
        } else {
            if (overlap) bboxContact[0] = true;
            if (fwd < RETREAT_FWD_SPEED) {
                if (bboxContact[0]) {
                    collisionCount[0]++;
                    MCPirates.LOGGER.info(
                            "[ramship-test] collision #{} at t={}: retreat after contact, fwd={}",
                            collisionCount[0], level.getGameTime(),
                            String.format("%.3f", fwd));
                } else {
                    // Reversed without making contact — e.g. brain disengaged, ship bled
                    // speed in a turn. Reset and re-arm on the next charge.
                    MCPirates.LOGGER.info(
                            "[ramship-test] t={}: retreat without prior contact — disarm",
                            level.getGameTime());
                }
                armed[0] = false;
                bboxContact[0] = false;
            }
        }
        lastFwdSpeed[0] = fwd;

        long gt = level.getGameTime();
        if (gt % 40 == 0) {
            Vector3d rPos = ramship.subLevel.logicalPose().position();
            Vector3d vPos = victim.subLevel.logicalPose().position();
            String rCtrl = ramship.controls == null ? "—" : ramship.controls.diagnostics(ramship);
            MCPirates.LOGGER.info(String.format(
                    "[ramship-test] t=%d state=%s ramship c=(%.1f,%.1f,%.1f) victim c=(%.1f,%.1f,%.1f) fwd=%.3f armed=%s overlap=%s contact=%s collisions=%d | %s",
                    gt, ramship.state.name(),
                    rPos.x, rPos.y, rPos.z, vPos.x, vPos.y, vPos.z,
                    fwd, armed[0], overlap, bboxContact[0], collisionCount[0], rCtrl));
        }
        helper.assertTrue(collisionCount[0] >= targetCount,
                "waiting for collision #" + targetCount + "; have " + collisionCount[0]
                        + " (armed=" + armed[0] + " contact=" + bboxContact[0] + ")");
    }

    /** True iff the ramship's SubLevel bounding box currently intersects the victim's.
     *  This is the exact same predicate RamControls uses internally to arm retreat — when
     *  it goes true, RamControls is sampling for stalled progress; when followed by a
     *  retreat phase here, that's the production code's full response to a real collision. */
    private static boolean boxesOverlap(Airship ramship, Airship victim) {
        BoundingBox3dc r = ramship.subLevel.boundingBox();
        BoundingBox3dc v = victim.subLevel.boundingBox();
        return r != null && v != null && r.intersects(v);
    }

    /** Signed velocity projection onto the ship's world-forward axis (blocks/tick).
     *  Positive = charging; negative = retreating. Mirrors the same projection
     *  {@link com.mcpirates.airship.ships.ramship.RamControls} uses internally. */
    private static double forwardSpeed(Airship a) {
        Vector3d vel = ShipTelemetry.velocity(a);
        Vector3d worldFwd = a.subLevel.logicalPose().orientation()
                .transform(new Vector3d(a.shipLocalForward), new Vector3d());
        return vel.x * worldFwd.x + vel.z * worldFwd.z;
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

    /**
     * Ramship intercepts a moving synthetic target through the real player-on-airship
     * pipeline: a brainless airship_small assembled via {@link TestSetup#placeAndAssembleAsPassiveTarget},
     * mock player riding it, SubLevel teleported each tick to drift east. The trigger's
     * {@code findSubLevelByWorldBounds} must detect the player-on-SubLevel for the ramship
     * to ever leave MOORED.
     */
    @GameTest(template = "ramship", timeoutTicks = 3000, setupTicks = 5,
              batch = "ramship_intercepts_moving_synthetic_target", skyAccess = true)
    public static void ramshipInterceptsMovingSyntheticAirship(GameTestHelper helper) {
        AtomicReference<SubLevel> targetSubRef = new AtomicReference<>();
        AtomicReference<GameTestPlayer> playerRef = new AtomicReference<>();
        AtomicReference<Airship> ramshipRef = new AtomicReference<>();
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
                    BlockPos airshipOrigin = new BlockPos(
                            ramshipAnchor.getX() - 3,
                            ramshipAnchor.getY(),
                            ramshipAnchor.getZ() - 50);
                    ChunkPos centre = new ChunkPos(ramshipAnchor);
                    BlockPos targetDest = new BlockPos(
                            ramshipAnchor.getX() + 100, ramshipAnchor.getY(), ramshipAnchor.getZ() - 50);
                    for (ChunkPos c : new ChunkPos[]{centre, new ChunkPos(airshipOrigin), new ChunkPos(targetDest)}) {
                        for (int dx = -5; dx <= 5; dx++) {
                            for (int dz = -5; dz <= 5; dz++) {
                                level.setChunkForced(c.x + dx, c.z + dz, true);
                            }
                        }
                    }
                    SubLevel sub = TestSetup.placeAndAssembleAsPassiveTarget(helper, airshipOrigin, "airship_small");
                    if (sub == null) return;
                    targetSubRef.set(sub);
                })
                .thenExecuteAfter(5, () -> {
                    SubLevel sub = targetSubRef.get();
                    BoundingBox3dc bb = sub.boundingBox();
                    double cx = (bb.minX() + bb.maxX()) * 0.5;
                    double cy = (bb.minY() + bb.maxY()) * 0.5;
                    double cz = (bb.minZ() + bb.maxZ()) * 0.5;
                    ExtendedGameTestHelper ext = TestSetup.extend(helper);
                    playerRef.set(TestSetup.spawnPinnedMockPlayer(ext, cx, cy, cz));
                    level.getServer().getPlayerList().setSimulationDistance(TestSetup.MOCK_PLAYER_SIM_DISTANCE);
                    level.getServer().getPlayerList().setViewDistance(TestSetup.MOCK_PLAYER_SIM_DISTANCE);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty()
                                && AirshipBrain.ships().get(0).state == AirshipBrain.State.LIFTOFF,
                        "waiting for ramship to assemble in LIFTOFF state"))
                .thenExecute(() -> ramshipRef.set(AirshipBrain.ships().get(0)))
                // Phase 1: wait for ramship to clear LIFTOFF on its own (rise ≥ 25 +
                // ticksInState ≥ 60). Target is stationary, so it stays in range and the
                // brain promotes LIFTOFF → PURSUE. This wait is the long part of the test
                // — ramship rises at ~0.04 b/tick under buoyancy → ~625 ticks to clear.
                .thenWaitUntil(() -> {
                    Airship ramship = ramshipRef.get();
                    helper.assertTrue(ramship != null && ramship.state == AirshipBrain.State.PURSUE,
                            "waiting for ramship → PURSUE; state="
                                    + (ramship == null ? "—" : ramship.state));
                })
                // Phase 2: now that the ramship is actively chasing, drift the target
                // away each tick + verify the ramship eventually catches up. Movement
                // rate (0.1 b/tick = 2 m/s) is slow enough that the ramship's propellers
                // can close the gap.
                .thenWaitUntil(() -> {
                    SubLevel target = targetSubRef.get();
                    GameTestPlayer player = playerRef.get();
                    Airship ramship = ramshipRef.get();
                    helper.assertTrue(target != null && player != null && ramship != null,
                            "test state lost");

                    if (target instanceof ServerSubLevel ssl) {
                        RigidBodyHandle handle = RigidBodyHandle.of(ssl);
                        if (handle != null) {
                            Vector3d curPos = new Vector3d(target.logicalPose().position());
                            Quaterniond curOri = new Quaterniond(target.logicalPose().orientation());
                            handle.teleport(curPos.add(0.1, 0, 0), curOri);
                        }
                    }

                    BoundingBox3dc tbb = target.boundingBox();
                    double tcx = (tbb.minX() + tbb.maxX()) * 0.5;
                    double tcy = (tbb.minY() + tbb.maxY()) * 0.5;
                    double tcz = (tbb.minZ() + tbb.maxZ()) * 0.5;
                    player.setPos(tcx, tcy, tcz);

                    BoundingBox3dc rbb = ramship.subLevel.boundingBox();
                    double rcx = (rbb.minX() + rbb.maxX()) * 0.5;
                    double rcz = (rbb.minZ() + rbb.maxZ()) * 0.5;
                    double dx = rcx - tcx;
                    double dz = rcz - tcz;
                    double horizDist = Math.sqrt(dx * dx + dz * dz);
                    closestApproach.updateAndGet(prev -> Math.min(prev, horizDist));
                    boolean overlap = rbb.intersects(tbb);
                    long gt = level.getGameTime();
                    if (gt % 40 == 0 || overlap) {
                        MCPirates.LOGGER.info(
                                "[ramship-mock-test] t={} state={} ramship c=({},{}) target c=({},{}) dist={} closest={} overlap={}",
                                gt, ramship.state,
                                String.format("%.1f", rcx), String.format("%.1f", rcz),
                                String.format("%.1f", tcx), String.format("%.1f", tcz),
                                String.format("%.2f", horizDist),
                                String.format("%.2f", closestApproach.get()),
                                overlap);
                    }
                    helper.assertTrue(overlap,
                            "waiting for ramship/target overlap; closest=" + closestApproach.get());
                })
                .thenExecute(() -> {
                    BlockPos rsAnchor = TestSetup.findAnchor(helper);
                    if (rsAnchor != null) {
                        ChunkPos centre = new ChunkPos(rsAnchor);
                        for (int dx = -5; dx <= 5; dx++) {
                            for (int dz = -5; dz <= 5; dz++) {
                                level.setChunkForced(centre.x + dx, centre.z + dz, false);
                            }
                        }
                    }
                    TestSetup.reset(helper);
                })
                .thenSucceed();
    }

}
