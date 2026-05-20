package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipBrain.State;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirshipGameTests {

    private AirshipGameTests() {}

    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_and_actuates_on_pursue")
    public static void assemblesAndActuatesOnPursue(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "airship_small", true);
    }

    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_and_actuates_rotated_90", rotationSteps = 1)
    public static void assemblesAndActuatesRotated90(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "airship_small", true);
    }

    @GameTest(template = "crossbow_board", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_crossbow_board")
    public static void assemblesCrossbowBoardKindAndActuates(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "crossbow_board", false);
    }

    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "rehydrate_preserves_liftoff")
    public static void rehydratePreservesLiftoff(GameTestHelper helper) {
        runRehydrateTest(helper, ship -> {}, State.LIFTOFF);
    }

    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "rehydrate_preserves_hover")
    public static void rehydratePreservesHover(GameTestHelper helper) {
        runRehydrateTest(helper, ship ->
                AirshipBrain.transitionState(ship, State.HOVER,
                        helper.getLevel().getGameTime()),
                State.HOVER);
    }

    private static void runRehydrateTest(GameTestHelper helper,
                                         java.util.function.Consumer<Airship> beforeUnregister,
                                         State expectedState) {
        TestSetup.reset(helper);

        final UUID[] preWipeSubLevelId = new UUID[1];
        final Set<UUID> preWipeCrewUuids = new HashSet<>();
        final BlockPos[] preWipeAirpad = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    if (!AirshipLiftoffTrigger.activateAnchor(helper.getLevel(), anchorWorld)) {
                        helper.fail("activateAnchor returned false");
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty(),
                        "waiting for initial register"))
                .thenExecute(() -> {
                    Airship ship = AirshipBrain.ships().get(0);
                    preWipeSubLevelId[0] = ship.subLevel.getUniqueId();
                    preWipeAirpad[0] = ship.airpadAnchor;
                    for (var ae : ship.anchoredEntities) {
                        preWipeCrewUuids.add(ae.uuid());
                    }
                    if (preWipeCrewUuids.isEmpty()) {
                        helper.fail("crew didn't spawn pre-wipe");
                    }
                    beforeUnregister.accept(ship);
                })
                .thenExecute(() -> AirshipBrain.unregisterAll(helper.getLevel()))
                .thenExecute(() -> {
                    if (!AirshipBrain.ships().isEmpty()) {
                        helper.fail("unregisterAll didn't clear SHIPS");
                    }
                })
                .thenExecute(() -> {
                    int n = AirshipBrain.rehydrateLevel(helper.getLevel());
                    if (n != 1) {
                        helper.fail("rehydrateLevel returned " + n + ", expected 1");
                    }
                })
                .thenExecute(() -> {
                    if (AirshipBrain.ships().size() != 1) {
                        helper.fail("post-rehydrate ship count = " + AirshipBrain.ships().size());
                        return;
                    }
                    Airship ship = AirshipBrain.ships().get(0);
                    if (!ship.subLevel.getUniqueId().equals(preWipeSubLevelId[0])) {
                        helper.fail("subLevel id mismatch: pre=" + preWipeSubLevelId[0]
                                + " post=" + ship.subLevel.getUniqueId());
                    }
                    if (!ship.airpadAnchor.equals(preWipeAirpad[0])) {
                        helper.fail("airpad mismatch: pre=" + preWipeAirpad[0]
                                + " post=" + ship.airpadAnchor);
                    }
                    Set<UUID> postWipeCrewUuids = new HashSet<>();
                    for (var ae : ship.anchoredEntities) {
                        postWipeCrewUuids.add(ae.uuid());
                    }
                    if (!postWipeCrewUuids.equals(preWipeCrewUuids)) {
                        helper.fail("crew UUID set mismatch: pre=" + preWipeCrewUuids.size()
                                + " post=" + postWipeCrewUuids.size());
                    }
                    if (ship.state != expectedState) {
                        helper.fail("expected state=" + expectedState + " post-rehydrate, got "
                                + ship.state);
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "crew_defeat_shutdown")
    public static void crewDefeatShutdownDisengagesAndDeregisters(GameTestHelper helper) {
        TestSetup.reset(helper);

        final java.util.concurrent.atomic.AtomicReference<Airship> shipRef = new java.util.concurrent.atomic.AtomicReference<>();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    if (!AirshipLiftoffTrigger.activateAnchor(helper.getLevel(), anchorWorld)) {
                        helper.fail("activateAnchor returned false");
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty(),
                        "waiting for AirshipBrain.register"))
                .thenExecute(() -> {
                    Airship ship = AirshipBrain.ships().get(0);
                    shipRef.set(ship);
                    if (ship.anchoredEntities.isEmpty()) {
                        helper.fail("expected non-empty crew pre-kill");
                        return;
                    }
                    for (var ae : ship.anchoredEntities) {
                        var e = helper.getLevel().getEntity(ae.uuid());
                        if (e instanceof net.minecraft.world.entity.LivingEntity le) {
                            le.kill();
                        }
                    }
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    if (!AirshipBrain.ships().isEmpty()) {
                        helper.fail("expected ships() empty after crew defeat, got "
                                + AirshipBrain.ships().size());
                    }
                    Airship ship = shipRef.get();
                    if (ship.controls.isActive(ship)) {
                        helper.fail("controls still active after crew defeat");
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    /** Defeat strips the mcpirates stamp; rehydrator must skip the wreck. */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "rehydrate_skips_defeated_ship")
    public static void rehydrateSkipsDefeatedShip(GameTestHelper helper) {
        TestSetup.reset(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    if (!AirshipLiftoffTrigger.activateAnchor(helper.getLevel(), anchorWorld)) {
                        helper.fail("activateAnchor returned false");
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty(),
                        "waiting for AirshipBrain.register"))
                .thenExecute(() -> {
                    Airship ship = AirshipBrain.ships().get(0);
                    for (var ae : ship.anchoredEntities) {
                        var e = helper.getLevel().getEntity(ae.uuid());
                        if (e instanceof net.minecraft.world.entity.LivingEntity le) {
                            le.kill();
                        }
                    }
                })
                .thenIdle(2)
                .thenExecute(() -> AirshipBrain.unregisterAll(helper.getLevel()))
                .thenExecute(() -> {
                    int n = AirshipBrain.rehydrateLevel(helper.getLevel());
                    if (n != 0) {
                        helper.fail("rehydrateLevel returned " + n + ", expected 0 (stamp stripped)");
                    }
                    if (!AirshipBrain.ships().isEmpty()) {
                        helper.fail("expected ships() empty after rehydrate of stamp-stripped ship");
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "multi_ship_rehydrate")
    public static void multiShipRehydrate(GameTestHelper helper) {
        TestSetup.reset(helper);

        final java.util.Set<UUID> preWipeUuids = new HashSet<>();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos firstAnchor = TestSetup.findAnchor(helper);
                    if (firstAnchor == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    ServerLevel level = helper.getLevel();
                    var templateOpt = level.getStructureManager().get(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    MCPirates.MOD_ID, "airship_small"));
                    if (templateOpt.isEmpty()) {
                        helper.fail("airship_small template not loaded");
                        return;
                    }
                    AABB bb = helper.getBounds();
                    BlockPos secondOrigin = new BlockPos(
                            (int) Math.ceil(bb.maxX) + 5,
                            (int) Math.floor(bb.minY),
                            (int) Math.floor(bb.minZ));
                    templateOpt.get().placeInWorld(level, secondOrigin, secondOrigin,
                            new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                            level.getRandom(), 2);
                    java.util.List<BlockPos> anchors = TestSetup.findAnchorsInRadius(helper, firstAnchor, 64);
                    if (anchors.size() < 2) {
                        helper.fail("expected 2 anchors after manual placement, found " + anchors.size());
                        return;
                    }
                    for (BlockPos a : anchors) {
                        AirshipLiftoffTrigger.activateAnchor(helper.getLevel(), a);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        AirshipBrain.ships().size() == 2,
                        "waiting for 2 ships registered, got " + AirshipBrain.ships().size()))
                .thenExecute(() -> {
                    for (Airship a : AirshipBrain.ships()) {
                        preWipeUuids.add(a.subLevel.getUniqueId());
                    }
                    if (preWipeUuids.size() != 2) {
                        helper.fail("pre-wipe expected 2 distinct UUIDs, got " + preWipeUuids.size());
                    }
                })
                .thenExecute(() -> AirshipBrain.unregisterAll(helper.getLevel()))
                .thenExecute(() -> {
                    int n = AirshipBrain.rehydrateLevel(helper.getLevel());
                    if (n != 2) {
                        helper.fail("rehydrateLevel returned " + n + ", expected 2");
                    }
                    java.util.Set<UUID> postWipeUuids = new HashSet<>();
                    for (Airship a : AirshipBrain.ships()) {
                        postWipeUuids.add(a.subLevel.getUniqueId());
                    }
                    if (!postWipeUuids.equals(preWipeUuids)) {
                        helper.fail("UUID set mismatch: pre=" + preWipeUuids + " post=" + postWipeUuids);
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    private static void runAssemblyAndPursueTest(GameTestHelper helper,
                                                 String expectedKindName,
                                                 boolean expectCannonMounts) {
        TestSetup.reset(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in the " + expectedKindName + " arena");
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
                    if (AirshipBrain.ships().isEmpty()) return;
                    Airship ship = AirshipBrain.ships().get(0);
                    if (!expectedKindName.equals(ship.kind.name())) {
                        helper.fail("expected kind=" + expectedKindName
                                + " got=" + ship.kind.name());
                        return;
                    }
                    if (expectCannonMounts) {
                        if (ship.slCannonMounts.isEmpty()) {
                            helper.fail("no resolved cannon mounts");
                            return;
                        }
                        BlockPos slMount = ship.slCannonMounts.get(0);
                        BlockEntity be = ship.subLevel.getLevel().getBlockEntity(slMount);
                        if (!(be instanceof CannonMountBlockEntity)) {
                            helper.fail("cannon mount BE missing at " + slMount
                                    + " got " + (be == null ? "null" : be.getClass().getSimpleName()));
                            return;
                        }
                    } else if (!ship.slCannonMounts.isEmpty()) {
                        helper.fail("expected no cannon mounts for kind=" + expectedKindName
                                + " got " + ship.slCannonMounts.size());
                        return;
                    }
                })
                // Zombie is never added to the world — brain only reads getX/Y/Z/isAlive.
                .thenExecute(() -> {
                    if (AirshipBrain.ships().isEmpty()) return;
                    Airship ship = AirshipBrain.ships().get(0);
                    Vector3d shipPos = ship.subLevel.logicalPose().position();
                    BlockPos zPos = BlockPos.containing(shipPos.x + 12, shipPos.y, shipPos.z);
                    Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
                    if (zombie == null) {
                        helper.fail("EntityType.ZOMBIE.create returned null");
                        return;
                    }
                    zombie.moveTo(zPos.getX() + 0.5, zPos.getY(), zPos.getZ() + 0.5);
                    AirshipBrain.targetOverride = zombie;
                    ship.state = State.RETURN;
                    ship.stateEnteredTick = helper.getLevel().getGameTime();
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    if (AirshipBrain.ships().isEmpty()) return;
                    Airship ship = AirshipBrain.ships().get(0);
                    if (ship.state != State.PURSUE) {
                        helper.fail("did not transition to PURSUE; state=" + ship.state);
                        return;
                    }
                    if (!ship.controls.isActive(ship)) {
                        helper.fail("PURSUE reached but controls not active "
                                + "(diagnostics=" + ship.controls.diagnostics(ship) + ")");
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

}
