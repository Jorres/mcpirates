package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Physics tests for assembled airships. Guards against assembly glue / NBT
 * drift: if {@code AirshipKind.glueMin/glueMax} doesn't cover the full ship
 * NBT, some hull cells stay in the parent world post-assembly and physically
 * block the SubLevel's rigid body from rising (symptom: "lift > gravity but
 * ship doesn't move"). These tests catch that by asserting a clear Y climb.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirshipPhysicsTests {

    private static final double MIN_RISE = 20.0;
    private static final double MAX_XZ_DRIFT = 1.0;

    private AirshipPhysicsTests() {}

    /**
     * Drop an airship_small in the arena, activate via the production trigger,
     * and assert it rises at least {@link #MIN_RISE} blocks. {@code skyAccess}
     * removes the arena ceiling barrier so the body can reach its pressure
     * equilibrium altitude (~y=100) instead of slamming into a lid.
     */
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
                    BlockPos anchorWorld = findAnchor(helper);
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
                    org.joml.Vector3d p = a.subLevel.logicalPose().position();
                    startXRef.set(p.x);
                    startYRef.set(p.y);
                    startZRef.set(p.z);
                })
                // Balloon flood-fills over ~180 ticks; let the ship climb ~30 s.
                .thenIdle(180)
                .thenExecute(() -> sample(parentLevel, shipRef.get(), "t+9s"))
                .thenIdle(200)
                .thenExecute(() -> sample(parentLevel, shipRef.get(), "t+19s"))
                .thenIdle(200)
                .thenExecute(() -> sample(parentLevel, shipRef.get(), "t+29s"))
                .thenExecute(() -> {
                    Airship a = shipRef.get();
                    org.joml.Vector3d p = a.subLevel.logicalPose().position();
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


    /** Snapshot ship Y + linear velocity + balloon state to the log so a failure
     *  leaves enough trail to triage from `runs/gametest/logs/latest.log` alone. */
    private static void sample(ServerLevel parentLevel, Airship a, String phase) {
        if (a == null) return;
        org.joml.Vector3d p = a.subLevel.logicalPose().position();
        org.joml.Vector3d v = readVelocity(parentLevel, a.subLevel);
        double mass = (a.subLevel instanceof ServerSubLevel ssl && ssl.getMassTracker() != null)
                ? ssl.getMassTracker().getMass() : Double.NaN;
        String balloonStr = readBalloonState(a);
        MCPirates.LOGGER.info(String.format(
                "[physics-test] %s pos=(%.2f,%.2f,%.2f) v=(%.3f,%.3f,%.3f) mass=%.2f %s",
                phase, p.x, p.y, p.z, v.x, v.y, v.z, mass, balloonStr));
    }

    private static String readBalloonState(Airship a) {
        if (a.slBurnerPositions == null || a.slBurnerPositions.isEmpty()) return "no-burner-pos";
        BlockPos slBurner = a.slBurnerPositions.get(0);
        BlockEntity be = a.subLevel.getLevel().getBlockEntity(slBurner);
        if (!(be instanceof dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity burner)) {
            return "burner-be-missing";
        }
        var balloon = burner.getBalloon();
        if (balloon == null) return String.format("balloon=null signal=%d", burner.getSignalStrength());
        if (balloon instanceof dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon sb) {
            return String.format("balloon cap=%d filled=%.1f target=%.1f totalLift=%.2f signal=%d",
                    sb.getCapacity(), sb.getTotalFilledVolume(), sb.getTotalTargetVolume(),
                    sb.getTotalLift(), burner.getSignalStrength());
        }
        return "balloon class=" + balloon.getClass().getSimpleName();
    }

    private static org.joml.Vector3d readVelocity(ServerLevel parentLevel, SubLevel sl) {
        if (!(sl instanceof ServerSubLevel ssl)) return new org.joml.Vector3d();
        ServerSubLevelContainer container =
                (ServerSubLevelContainer) SubLevelContainer.getContainer(parentLevel);
        if (container == null || container.physicsSystem() == null) return new org.joml.Vector3d();
        RigidBodyHandle handle = container.physicsSystem().getPhysicsHandle(ssl);
        if (handle == null) return new org.joml.Vector3d();
        return handle.getLinearVelocity(new org.joml.Vector3d());
    }

    /** First {@link MCPShipAnchorBlockEntity} in the arena, or null. The
     *  template guarantees exactly one. */
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
}
