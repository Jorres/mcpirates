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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parameterized across all pirate-airship kinds except ramship (ramship intercepts, doesn't
 * orbit). Each kind runs two scenarios — rotation 0 spawns bow-NORTH which pickOrbitDir
 * naturally chooses as CCW, rotation 2 spawns bow-SOUTH which chooses CW — and verifies
 * the chosen direction, sustained radius / altitude, and signed angular sweep once the
 * orbit has converged.
 *
 * <p>Exact equilibrium r / y differs between kinds — galleon orbits wider than the
 * lighter kinds because its inertia drives the heading-lag offset higher — so the
 * radius/altitude tolerances are deliberately wide. What stays the same across kinds
 * is the route: stable orbit at non-zero radius, correct direction, bounded altitude.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrbitMovementTests {

    private static final double TARGET_OFFSET_X = 15.0;
    /** Target eye + PURSUE_ALT_OFFSET puts the orbit altitude at ~33. Low enough that
     *  ships don't need to climb 130+ blocks (saves test budget) but high enough to
     *  clear the arena floor and let the orbit develop in open air. */
    private static final double TARGET_Y = 20.0;
    /** Matches OrbitMovement.PURSUE_ALT_OFFSET. */
    private static final double PURSUE_ALT_OFFSET = 12.0;
    /** Window-based altitude-settle gate. Per-tick |Δy| is rapier-integration noise
     *  for slow climbers (crossbow_board has tickDy ≈ 0.004 while still rising at bulk
     *  rate 0.04 b/tick), so a per-tick threshold fires while the ship is mid-climb.
     *  Comparing y to its value 40 ticks ago captures bulk rate, not jitter. */
    private static final int ARRIVAL_Y_WINDOW_TICKS = 40;
    private static final double ARRIVAL_Y_WINDOW_DELTA = 1.0;
    private static final int ARRIVAL_Y_STABLE_TICKS = 40;
    /** Sustained-orbit invariants. Tolerances are sized to bracket observed worst-case
     *  per-kind values (mean r offset ≤ 17, range r ≤ 4.3, mean y offset ≤ 6, range y ≤
     *  4.3, |sweep| ≥ 53° / 400 ticks) so a kind-specific regression trips the band
     *  rather than getting masked. */
    private static final double ORBIT_MEAN_R_TOLERANCE = 18.0;
    private static final double ORBIT_MEAN_Y_TOLERANCE = 7.0;
    private static final double ORBIT_RANGE_R_TOLERANCE = 5.0;
    private static final double ORBIT_RANGE_Y_TOLERANCE = 5.0;
    private static final int ORBIT_SAMPLE_TICKS = 400;
    /** Headroom over the 30°-ish span the slowest kind (firecracker) can drift in
     *  a 400-tick sample window. 45° was right at firecracker's noise floor — the test
     *  flaked on the boundary across reruns. 40° still proves "orbits in the expected
     *  direction with meaningful sweep" while giving every kind a few degrees of slack. */
    private static final double MIN_SWEEP_DEG = 40.0;
    private static final int LOG_INTERVAL = 40;
    /** 6-chunk radius (~96 blocks) covers each kind's actual orbit, including galleon. */
    private static final int FORCE_LOAD_RADIUS_CHUNKS = 6;

    private OrbitMovementTests() {}

    /** Find the airship whose {@code airpadAnchor} lies inside the test arena's bounding
     *  box. Avoids the parallel-batch hazard where {@code ships().get(0)} returns a
     *  ship from a different test. */
    private static Airship findShipInArena(GameTestHelper helper) {
        net.minecraft.world.phys.AABB bb = helper.getBounds();
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

    @GameTest(template = "airship_small", timeoutTicks = 3500, setupTicks = 5,
              batch = "orbit_airship_small_ccw", skyAccess = true)
    public static void airshipSmallOrbitsCcw(GameTestHelper helper) {
        runOrbitTest(helper, +1);
    }

    @GameTest(template = "airship_small", timeoutTicks = 3500, setupTicks = 5,
              batch = "orbit_airship_small_cw", rotationSteps = 2, skyAccess = true)
    public static void airshipSmallOrbitsCw(GameTestHelper helper) {
        runOrbitTest(helper, -1);
    }

    // crossbow_board climbs slower than airship_small (twin burner but ~88kg vs 61.5kg),
    // so it needs more time to reach orbit altitude.
    @GameTest(template = "crossbow_board", timeoutTicks = 5500, setupTicks = 5,
              batch = "orbit_crossbow_board_ccw", skyAccess = true)
    public static void crossbowBoardOrbitsCcw(GameTestHelper helper) {
        runOrbitTest(helper, +1);
    }

    @GameTest(template = "crossbow_board", timeoutTicks = 5500, setupTicks = 5,
              batch = "orbit_crossbow_board_cw", rotationSteps = 2, skyAccess = true)
    public static void crossbowBoardOrbitsCw(GameTestHelper helper) {
        runOrbitTest(helper, -1);
    }

    @GameTest(template = "galleon", timeoutTicks = 6000, setupTicks = 5,
              batch = "orbit_galleon_ccw", skyAccess = true)
    public static void galleonOrbitsCcw(GameTestHelper helper) {
        runOrbitTest(helper, +1);
    }

    @GameTest(template = "galleon", timeoutTicks = 6000, setupTicks = 5,
              batch = "orbit_galleon_cw", rotationSteps = 2, skyAccess = true)
    public static void galleonOrbitsCw(GameTestHelper helper) {
        runOrbitTest(helper, -1);
    }

    @GameTest(template = "firecracker", timeoutTicks = 5500, setupTicks = 5,
              batch = "orbit_firecracker_ccw", skyAccess = true)
    public static void firecrackerOrbitsCcw(GameTestHelper helper) {
        runOrbitTest(helper, +1);
    }

    @GameTest(template = "firecracker", timeoutTicks = 5500, setupTicks = 5,
              batch = "orbit_firecracker_cw", rotationSteps = 2, skyAccess = true)
    public static void firecrackerOrbitsCw(GameTestHelper helper) {
        runOrbitTest(helper, -1);
    }

    private static void runOrbitTest(GameTestHelper helper, int expectedOrbitDir) {
        ServerLevel level = helper.getLevel();
        AtomicReference<Airship> shipRef = new AtomicReference<>();
        AtomicReference<GameTestPlayer> targetRef = new AtomicReference<>();
        ArrayDeque<Double> yHistory = new ArrayDeque<>();
        AtomicInteger altStableTicks = new AtomicInteger();
        List<double[]> samples = new ArrayList<>();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    TestSetup.reset(helper);
                    BlockPos anchor = TestSetup.findAnchor(helper);
                    if (anchor == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    // Orbit leaves the arena bbox; force-load so the SubLevel keeps
                    // ticking. See [[sable-chunk-ticket-mechanism]].
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
                // Filter by arena bounds: parallel gametest batches register their own
                // ships into the global AirshipBrain.SHIPS list, so ships().get(0) can
                // return a ship from a different test (e.g. assemblesCrossbowBoardKindAndActuates
                // manually flips its ship to RETURN→PURSUE). Selecting by airpadAnchor
                // inside this arena's bbox isolates the correct one.
                .thenWaitUntil(() -> helper.assertTrue(
                        findShipInArena(helper) != null,
                        "waiting for AirshipBrain.register inside arena " + helper.getBounds()))
                .thenExecute(() -> {
                    Airship ship = findShipInArena(helper);
                    shipRef.set(ship);
                    MCPirates.LOGGER.info(String.format(Locale.ROOT,
                            "[orbit-test:%s] picked ship subLevel=%s airpadAnchor=%s state=%s",
                            ship.kind.name(), ship.subLevel.getUniqueId(),
                            ship.airpadAnchor, ship.state));

                    Vector3d shipPos = ship.subLevel.logicalPose().position();
                    double targetX = shipPos.x + TARGET_OFFSET_X;
                    double targetZ = shipPos.z;

                    ExtendedGameTestHelper ext = TestSetup.extend(helper);
                    GameTestPlayer player = TestSetup.spawnPinnedMockPlayer(
                            ext, targetX, TARGET_Y, targetZ);
                    targetRef.set(player);
                    level.getServer().getPlayerList()
                            .setSimulationDistance(TestSetup.MOCK_PLAYER_SIM_DISTANCE);
                    level.getServer().getPlayerList()
                            .setViewDistance(TestSetup.MOCK_PLAYER_SIM_DISTANCE);
                    // No targetOverride — findEnemyPlayerOnAirship picks the nearest
                    // ServerPlayer within DISENGAGE_RANGE_SQ, which is our pinned mock.

                    MCPirates.LOGGER.info(String.format(Locale.ROOT,
                            "[orbit-test:%s] ship=(%.1f,%.1f,%.1f) yaw=%.1f° target=(%.1f,%.1f,%.1f) expectedDir=%d",
                            ship.kind.name(),
                            shipPos.x, shipPos.y, shipPos.z,
                            Math.toDegrees(ship.yawRadians()),
                            targetX, TARGET_Y, targetZ, expectedOrbitDir));
                })
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    helper.assertTrue(ship != null && ship.state == State.PURSUE,
                            "waiting for PURSUE; state="
                                    + (ship == null ? "—" : ship.state));
                })
                // pickOrbitDir is a pure XZ+yaw computation — fail fast before the orbit
                // develops so a sign regression is obvious without sample analysis.
                .thenExecute(() -> {
                    Airship ship = shipRef.get();
                    if (ship.orbitDir != expectedOrbitDir) {
                        Vector3d sp = ship.subLevel.logicalPose().position();
                        GameTestPlayer p = targetRef.get();
                        helper.fail(String.format(Locale.ROOT,
                                "[%s] orbitDir mismatch: expected %d, got %d (ship=(%.1f,%.1f) yaw=%.1f° target=(%.1f,%.1f))",
                                ship.kind.name(), expectedOrbitDir, ship.orbitDir,
                                sp.x, sp.z, Math.toDegrees(ship.yawRadians()),
                                p.getX(), p.getZ()));
                    }
                })
                // Arrival = |y(now) − y(now − WINDOW)| stays below WINDOW_DELTA for
                // ARRIVAL_Y_STABLE_TICKS consecutive ticks. Window-based (not per-tick)
                // because per-tick |Δy| is rapier integration noise that fires the gate
                // while a slow climber is still mid-ascent.
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    GameTestPlayer p = targetRef.get();
                    helper.assertTrue(ship != null && p != null, "test state lost");
                    Vector3d sp = ship.subLevel.logicalPose().position();
                    double dx = sp.x - p.getX();
                    double dz = sp.z - p.getZ();
                    double r = Math.sqrt(dx * dx + dz * dz);
                    double orbitR = ship.kind.orbitRadius();
                    double expectedY = p.getEyeY() + PURSUE_ALT_OFFSET;
                    yHistory.addLast(sp.y);
                    while (yHistory.size() > ARRIVAL_Y_WINDOW_TICKS) {
                        yHistory.removeFirst();
                    }
                    double windowDy;
                    if (yHistory.size() < ARRIVAL_Y_WINDOW_TICKS) {
                        windowDy = Double.POSITIVE_INFINITY;
                    } else {
                        windowDy = Math.abs(sp.y - yHistory.peekFirst());
                    }
                    boolean rateOk = windowDy <= ARRIVAL_Y_WINDOW_DELTA;
                    int stable;
                    if (rateOk) {
                        stable = altStableTicks.incrementAndGet();
                    } else {
                        altStableTicks.set(0);
                        stable = 0;
                    }
                    if (helper.getTick() % LOG_INTERVAL == 0) {
                        MCPirates.LOGGER.info(String.format(Locale.ROOT,
                                "[orbit-test:%s] approach r=%.2f orbitR=%.2f y=%.2f targetY=%.2f windowDy=%.3f stable=%d/%d",
                                ship.kind.name(),
                                r, orbitR, sp.y, expectedY, windowDy,
                                stable, ARRIVAL_Y_STABLE_TICKS));
                    }
                    helper.assertTrue(stable >= ARRIVAL_Y_STABLE_TICKS,
                            String.format(Locale.ROOT,
                                    "waiting for altitude settle: r=%.2f y=%.2f windowDy=%.3f stable=%d/%d",
                                    r, sp.y, windowDy, stable, ARRIVAL_Y_STABLE_TICKS));
                })
                .thenExecute(() -> {
                    samples.clear();
                    MCPirates.LOGGER.info(
                            "[orbit-test:{}] entered orbit band; sampling {} ticks",
                            shipRef.get().kind.name(), ORBIT_SAMPLE_TICKS);
                })
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    GameTestPlayer p = targetRef.get();
                    Vector3d sp = ship.subLevel.logicalPose().position();
                    samples.add(new double[]{sp.x - p.getX(), sp.z - p.getZ(), sp.y});
                    helper.assertTrue(samples.size() >= ORBIT_SAMPLE_TICKS,
                            "sampling orbit; collected=" + samples.size()
                                    + "/" + ORBIT_SAMPLE_TICKS);
                })
                .thenExecute(() -> {
                    Airship ship = shipRef.get();
                    GameTestPlayer p = targetRef.get();
                    double orbitR = ship.kind.orbitRadius();
                    double expectedY = p.getEyeY() + PURSUE_ALT_OFFSET;

                    double maxR = Double.NEGATIVE_INFINITY, minR = Double.POSITIVE_INFINITY;
                    double maxY = Double.NEGATIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
                    double sumR = 0, sumY = 0;
                    double signedSweep = 0.0;
                    double lastTheta = Double.NaN;
                    for (double[] s : samples) {
                        double r = Math.sqrt(s[0] * s[0] + s[1] * s[1]);
                        if (r > maxR) maxR = r;
                        if (r < minR) minR = r;
                        if (s[2] > maxY) maxY = s[2];
                        if (s[2] < minY) minY = s[2];
                        sumR += r;
                        sumY += s[2];
                        double theta = Math.atan2(s[1], s[0]);
                        if (!Double.isNaN(lastTheta)) {
                            double d = theta - lastTheta;
                            if (d > Math.PI) d -= 2 * Math.PI;
                            if (d < -Math.PI) d += 2 * Math.PI;
                            signedSweep += d;
                        }
                        lastTheta = theta;
                    }
                    int n = samples.size();
                    double meanR = sumR / n;
                    double meanY = sumY / n;
                    double rangeR = maxR - minR;
                    double rangeY = maxY - minY;
                    double sweepDeg = Math.toDegrees(signedSweep);
                    MCPirates.LOGGER.info(String.format(Locale.ROOT,
                            "[orbit-test:%s] samples=%d r mean=%.2f range=%.2f (target=%.2f) y mean=%.2f range=%.2f (target=%.2f) sweep=%.1f° expectedSign=%d",
                            ship.kind.name(),
                            n, meanR, rangeR, orbitR, meanY, rangeY, expectedY,
                            sweepDeg, expectedOrbitDir));

                    String kindName = ship.kind.name();
                    if (Math.abs(meanR - orbitR) > ORBIT_MEAN_R_TOLERANCE) {
                        helper.fail(String.format(Locale.ROOT,
                                "[%s] orbit mean radius off: mean=%.2f expected %.2f ± %.2f",
                                kindName, meanR, orbitR, ORBIT_MEAN_R_TOLERANCE));
                        return;
                    }
                    if (rangeR > ORBIT_RANGE_R_TOLERANCE) {
                        helper.fail(String.format(Locale.ROOT,
                                "[%s] orbit unstable horizontally: range=%.2f > %.2f (min=%.2f max=%.2f)",
                                kindName, rangeR, ORBIT_RANGE_R_TOLERANCE, minR, maxR));
                        return;
                    }
                    if (Math.abs(meanY - expectedY) > ORBIT_MEAN_Y_TOLERANCE) {
                        helper.fail(String.format(Locale.ROOT,
                                "[%s] orbit mean altitude off: mean=%.2f expected %.2f ± %.2f",
                                kindName, meanY, expectedY, ORBIT_MEAN_Y_TOLERANCE));
                        return;
                    }
                    if (rangeY > ORBIT_RANGE_Y_TOLERANCE) {
                        helper.fail(String.format(Locale.ROOT,
                                "[%s] orbit unstable vertically: range=%.2f > %.2f (min=%.2f max=%.2f)",
                                kindName, rangeY, ORBIT_RANGE_Y_TOLERANCE, minY, maxY));
                        return;
                    }
                    // Magnitude guard catches a mid-orbit orbitStuckDecisions flip that
                    // would cancel the sweep even with the correct initial sign.
                    if (Math.signum(sweepDeg) != Math.signum(expectedOrbitDir)
                            || Math.abs(sweepDeg) < MIN_SWEEP_DEG) {
                        helper.fail(String.format(Locale.ROOT,
                                "[%s] orbit direction/extent wrong: sweep=%.1f° expectedSign=%d minMag=%.1f°",
                                kindName, sweepDeg, expectedOrbitDir, MIN_SWEEP_DEG));
                    }
                })
                .thenExecute(() -> {
                    GameTestPlayer p = targetRef.get();
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
}
