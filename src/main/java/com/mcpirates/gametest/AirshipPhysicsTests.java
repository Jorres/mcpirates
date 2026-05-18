package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Catches assembly glue / NBT drift: if glueMin/glueMax misses hull cells, they
 * stay in the parent world and block SubLevel rise (lift > gravity, no motion).
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirshipPhysicsTests {

    private static final double MIN_RISE = 20.0;
    private static final double MAX_XZ_DRIFT = 1.0;
    private static final Vec3 FIXED_TARGET_POS = new Vec3(0.5, 80.0, 0.5);
    private static final double SECOND_TARGET_Y = 110.0;
    /** Guards against SECOND_TARGET_Y mapping to the same plateau as phase 1. */
    private static final double PLATEAU_SHIFT_MIN = 3.0;
    private static final double PURSUIT_ALT_OFFSET = 12.0;
    private static final double TARGET_ALTITUDE_TOLERANCE = 4.0;
    private static final double STABLE_Y_DELTA = 0.08;
    private static final int REQUIRED_STABLE_TICKS = 40;
    private static final int WAIT_LOG_INTERVAL = 40;

    private AirshipPhysicsTests() {}

    @GameTest(template = "airship_small", timeoutTicks = 800, setupTicks = 5,
              batch = "airship_small_rises_under_buoyancy", skyAccess = true)
    public static void airshipSmallRisesUnderBuoyancy(GameTestHelper helper) {
        AtomicReference<Airship> shipRef = new AtomicReference<>();
        AtomicReference<Double> startYRef = new AtomicReference<>();
        AtomicReference<Double> startXRef = new AtomicReference<>();
        AtomicReference<Double> startZRef = new AtomicReference<>();
        ServerLevel parentLevel = helper.getLevel();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    TestSetup.reset(helper);
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in the test arena");
                        return;
                    }
                    if (!AirshipLiftoffTrigger.activateAnchor(helper.getLevel(), anchorWorld)) {
                        helper.fail("activateAnchor returned false at " + anchorWorld);
                        return;
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty(),
                        "waiting for AirshipBrain.register"))
                .thenExecute(() -> {
                    Airship a = AirshipBrain.ships().get(0);
                    shipRef.set(a);
                    Vector3d p = a.subLevel.logicalPose().position();
                    startXRef.set(p.x);
                    startYRef.set(p.y);
                    startZRef.set(p.z);
                })
                // Balloon flood-fills over ~180 ticks.
                .thenIdle(180)
                .thenExecute(() -> sample(parentLevel, shipRef.get(), "t+9s"))
                .thenIdle(200)
                .thenExecute(() -> sample(parentLevel, shipRef.get(), "t+19s"))
                .thenIdle(200)
                .thenExecute(() -> sample(parentLevel, shipRef.get(), "t+29s"))
                .thenExecute(() -> {
                    Airship a = shipRef.get();
                    Vector3d p = a.subLevel.logicalPose().position();
                    double rise = p.y - startYRef.get();
                    double dx = p.x - startXRef.get();
                    double dz = p.z - startZRef.get();
                    double xzDrift = Math.sqrt(dx * dx + dz * dz);
                    if (rise < MIN_RISE) {
                        helper.fail(String.format(
                                "ship did not rise enough: rise=%.2f < %.2f (startY=%.2f → endY=%.2f)",
                                rise, MIN_RISE, startYRef.get(), p.y));
                        return;
                    }
                    if (xzDrift > MAX_XZ_DRIFT) {
                        helper.fail(String.format(
                                "ship drifted horizontally: %.2f > %.2f blocks",
                                xzDrift, MAX_XZ_DRIFT));
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    @GameTest(template = "airship_small", timeoutTicks = 2800, setupTicks = 5,
              batch = "airship_small_stabilizes_at_fixed_target_height", skyAccess = true)
    public static void airshipSmallStabilizesAtFixedTargetHeight(GameTestHelper helper) {
        AtomicReference<Airship> shipRef = new AtomicReference<>();
        AtomicReference<Zombie> targetRef = new AtomicReference<>();
        AtomicReference<Double> desiredTargetYRef = new AtomicReference<>();
        AtomicReference<Double> expectedPlateauYRef = new AtomicReference<>();
        AtomicReference<Double> lastYRef = new AtomicReference<>();
        AtomicInteger stableTicks = new AtomicInteger();
        ServerLevel parentLevel = helper.getLevel();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    TestSetup.reset(helper);
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in the test arena");
                        return;
                    }
                    // PURSUE orbits at radius 25; force-load so PhysicsChunkTicketManager
                    // keeps the SubLevel ticking for the full settle.
                    ChunkPos centre = new ChunkPos(anchorWorld);
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            parentLevel.setChunkForced(centre.x + dx, centre.z + dz, true);
                        }
                    }
                    if (!AirshipLiftoffTrigger.activateAnchor(parentLevel, anchorWorld)) {
                        helper.fail("activateAnchor returned false at " + anchorWorld);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty(),
                        "waiting for AirshipBrain.register"))
                .thenExecute(() -> {
                    Airship ship = AirshipBrain.ships().get(0);
                    shipRef.set(ship);

                    Zombie target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, FIXED_TARGET_POS);
                    target.setNoGravity(true);
                    target.setInvulnerable(true);
                    target.setSilent(true);
                    target.setDeltaMovement(Vec3.ZERO);
                    targetRef.set(target);

                    AirshipBrain.targetOverride = target;
                    desiredTargetYRef.set(target.getEyeY() + PURSUIT_ALT_OFFSET);
                })
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    helper.assertTrue(ship != null, "waiting for ship reference");
                    helper.assertTrue(ship.plateauTable != null && ship.plateauTable.size() > 0,
                            "waiting for PURSUE plateau table");
                })
                .thenExecute(() -> {
                    Airship ship = shipRef.get();
                    double desiredTargetY = desiredTargetYRef.get();
                    double expectedPlateauY = ship.plateauTable.pickClosest(desiredTargetY).equilibriumY();
                    expectedPlateauYRef.set(expectedPlateauY);
                    MCPirates.LOGGER.info(String.format(Locale.ROOT,
                            "[physics-test] fixed target zombie=(%.2f,%.2f,%.2f) desiredY=%.2f expectedPlateauY=%.2f",
                            targetRef.get().getX(), targetRef.get().getY(), targetRef.get().getZ(),
                            desiredTargetY, expectedPlateauY));
                })
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    Zombie target = targetRef.get();
                    helper.assertTrue(ship != null, "waiting for ship reference");
                    helper.assertTrue(target != null && target.isAlive(), "fixed zombie target disappeared");
                    helper.assertTrue(ship.state == AirshipBrain.State.PURSUE,
                            "waiting for PURSUE; state=" + ship.state);

                    Vector3d pos = ship.subLevel.logicalPose().position();
                    Double lastY = lastYRef.getAndSet(pos.y);
                    double tickDeltaY = lastY == null ? Double.POSITIVE_INFINITY : Math.abs(pos.y - lastY);
                    double expectedPlateauY = expectedPlateauYRef.get();
                    double dy = Math.abs(pos.y - expectedPlateauY);
                    boolean stable = dy <= TARGET_ALTITUDE_TOLERANCE
                            && tickDeltaY <= STABLE_Y_DELTA;

                    int stableFor = stable ? stableTicks.incrementAndGet() : 0;
                    if (!stable) {
                        stableTicks.set(0);
                    }
                    if (helper.getTick() % WAIT_LOG_INTERVAL == 0) {
                        MCPirates.LOGGER.info(String.format(Locale.ROOT,
                                "[physics-test] fixed-target settle state=%s y=%.2f expected=%.2f dy=%.2f tickDeltaY=%.3f stable=%d/%d",
                                ship.state, pos.y, expectedPlateauY, dy, tickDeltaY,
                                stableFor, REQUIRED_STABLE_TICKS));
                    }
                    helper.assertTrue(stableFor >= REQUIRED_STABLE_TICKS, String.format(Locale.ROOT,
                            "waiting for altitude settle: y=%.2f expected=%.2f dy=%.2f tickDeltaY=%.3f stable=%d/%d",
                            pos.y, expectedPlateauY, dy, tickDeltaY,
                            stableFor, REQUIRED_STABLE_TICKS));
                })
                // Phase 2: tp target up; ship must re-stabilize at a higher plateau row.
                .thenExecute(() -> {
                    Airship ship = shipRef.get();
                    Zombie target = targetRef.get();
                    double oldExpectedPlateauY = expectedPlateauYRef.get();

                    target.teleportTo(target.getX(), SECOND_TARGET_Y, target.getZ());
                    target.setDeltaMovement(Vec3.ZERO);

                    double newDesiredTargetY = target.getEyeY() + PURSUIT_ALT_OFFSET;
                    double newExpectedPlateauY =
                            ship.plateauTable.pickClosest(newDesiredTargetY).equilibriumY();

                    helper.assertTrue(
                            Math.abs(newExpectedPlateauY - oldExpectedPlateauY) >= PLATEAU_SHIFT_MIN,
                            String.format(Locale.ROOT,
                                    "phase-2 teleport did not change plateau target: was %.2f, now %.2f "
                                            + "(table rows insufficiently spaced or SECOND_TARGET_Y too close)",
                                    oldExpectedPlateauY, newExpectedPlateauY));

                    expectedPlateauYRef.set(newExpectedPlateauY);
                    lastYRef.set(null);
                    stableTicks.set(0);

                    MCPirates.LOGGER.info(String.format(Locale.ROOT,
                            "[physics-test] phase-2 zombie tp'd to y=%.2f; expectedPlateauY %.2f → %.2f (Δ=%.2f)",
                            SECOND_TARGET_Y, oldExpectedPlateauY, newExpectedPlateauY,
                            newExpectedPlateauY - oldExpectedPlateauY));
                })
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    Zombie target = targetRef.get();
                    helper.assertTrue(ship != null, "waiting for ship reference");
                    helper.assertTrue(target != null && target.isAlive(),
                            "fixed zombie target disappeared in phase 2");
                    helper.assertTrue(ship.state == AirshipBrain.State.PURSUE,
                            "phase-2 lost PURSUE; state=" + ship.state);

                    Vector3d pos = ship.subLevel.logicalPose().position();
                    Double lastY = lastYRef.getAndSet(pos.y);
                    double tickDeltaY = lastY == null ? Double.POSITIVE_INFINITY : Math.abs(pos.y - lastY);
                    double expectedPlateauY = expectedPlateauYRef.get();
                    double dy = Math.abs(pos.y - expectedPlateauY);
                    boolean stable = dy <= TARGET_ALTITUDE_TOLERANCE
                            && tickDeltaY <= STABLE_Y_DELTA;

                    int stableFor = stable ? stableTicks.incrementAndGet() : 0;
                    if (!stable) {
                        stableTicks.set(0);
                    }
                    if (helper.getTick() % WAIT_LOG_INTERVAL == 0) {
                        MCPirates.LOGGER.info(String.format(Locale.ROOT,
                                "[physics-test] phase-2 settle state=%s y=%.2f expected=%.2f dy=%.2f tickDeltaY=%.3f stable=%d/%d",
                                ship.state, pos.y, expectedPlateauY, dy, tickDeltaY,
                                stableFor, REQUIRED_STABLE_TICKS));
                    }
                    helper.assertTrue(stableFor >= REQUIRED_STABLE_TICKS, String.format(Locale.ROOT,
                            "phase-2 waiting for altitude settle: y=%.2f expected=%.2f dy=%.2f tickDeltaY=%.3f stable=%d/%d",
                            pos.y, expectedPlateauY, dy, tickDeltaY,
                            stableFor, REQUIRED_STABLE_TICKS));
                })
                .thenExecute(() -> {
                    Zombie target = targetRef.get();
                    if (target != null) {
                        target.discard();
                    }
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld != null) {
                        ChunkPos centre = new ChunkPos(anchorWorld);
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dz = -3; dz <= 3; dz++) {
                                parentLevel.setChunkForced(centre.x + dx, centre.z + dz, false);
                            }
                        }
                    }
                    TestSetup.reset(helper);
                })
                .thenSucceed();
    }


    private static void sample(ServerLevel parentLevel, Airship a, String phase) {
        if (a == null) return;
        com.mcpirates.airship.ShipTelemetry.snapshot(a, "physics-test " + phase);
    }

}
