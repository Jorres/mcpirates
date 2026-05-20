package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipBrain.State;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
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

    @GameTest(template = "firecracker", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_firecracker")
    public static void assemblesFirecrackerKindAndActuates(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "firecracker", false);
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

    /** Envelope destruction → ship sinks → CRASHING → CRASHED → crew dismounts as
     *  ground mobs. Touches the realistic crash pipeline end-to-end (no state forcing). */
    @GameTest(template = "airship_small", timeoutTicks = 2400, setupTicks = 5,
              batch = "envelope_destruction_crashes_ship", skyAccess = true)
    public static void envelopeDestructionCrashesShipAndDismountsCrew(GameTestHelper helper) {
        TestSetup.reset(helper);

        final java.util.concurrent.atomic.AtomicReference<Airship> shipRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final org.joml.Vector3d[] wreckCenter = new org.joml.Vector3d[1];
        final int[] envelopeBlocksStripped = new int[1];

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = TestSetup.findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    if (!AirshipLiftoffTrigger.activateAnchor(helper.getLevel(), anchorWorld)) {
                        helper.fail("activateAnchor returned false at " + anchorWorld);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty(),
                        "waiting for ship to register"))
                .thenExecute(() -> shipRef.set(AirshipBrain.ships().get(0)))
                // Crash detector arms once the ship has been AGL ≥ 15 — required gate before
                // ground-contact tracking begins. Without this wait, stripping the envelope
                // mid-LIFTOFF would land the wreck before the detector is armed.
                .thenWaitUntil(() -> {
                    Airship a = shipRef.get();
                    helper.assertTrue(a != null && a.crashArmed,
                            "waiting for crashArmed; state="
                                    + (a == null ? "—" : a.state.name()));
                })
                // Punch a few random holes in the envelope — mimics what a couple of cannon
                // hits would do in actual combat. Balloon becomes invalid (open shape) →
                // lift drops → ship descends. Deterministic seed so the test isn't flaky.
                .thenExecute(() -> {
                    Airship a = shipRef.get();
                    net.minecraft.world.level.Level slLevel = a.subLevel.getLevel();
                    if (slLevel == null) {
                        helper.fail("SubLevel level is null");
                        return;
                    }
                    net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> envelopeTag =
                            net.minecraft.tags.TagKey.create(
                                    net.minecraft.core.registries.Registries.BLOCK,
                                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                            "aeronautics", "envelope"));
                    var pb = a.subLevel.getPlot().getBoundingBox();
                    java.util.List<BlockPos> envelopeBlocks = new java.util.ArrayList<>();
                    for (BlockPos p : BlockPos.betweenClosed(
                            pb.minX(), pb.minY(), pb.minZ(),
                            pb.maxX(), pb.maxY(), pb.maxZ())) {
                        if (slLevel.getBlockState(p).is(envelopeTag)) {
                            envelopeBlocks.add(p.immutable());
                        }
                    }
                    if (envelopeBlocks.isEmpty()) {
                        helper.fail("no envelope blocks found in SubLevel plot — crash trigger absent");
                        return;
                    }
                    java.util.Collections.shuffle(envelopeBlocks, new java.util.Random(42));
                    int toRemove = Math.min(3, envelopeBlocks.size());
                    for (int i = 0; i < toRemove; i++) {
                        slLevel.setBlock(envelopeBlocks.get(i),
                                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                                3);
                    }
                    envelopeBlocksStripped[0] = toRemove;
                    MCPirates.LOGGER.info(
                            "[crash-test] punched {} hole(s) in envelope (of {} total blocks) at {}",
                            toRemove, envelopeBlocks.size(),
                            envelopeBlocks.subList(0, toRemove));
                })
                // Capture wreck XZ center once the detector picks up the descent. We grab the
                // bbox NOW (not later) because tickShip deregisters on CRASHED, after which the
                // Airship handle keeps working but log-frame bounds may drift if we wait.
                .thenWaitUntil(() -> {
                    Airship a = shipRef.get();
                    helper.assertTrue(a != null
                                    && (a.state == State.CRASHING || a.state == State.CRASHED),
                            "waiting for CRASHING/CRASHED after envelope strip; state="
                                    + (a == null ? "—" : a.state.name()));
                })
                .thenExecute(() -> {
                    Airship a = shipRef.get();
                    var bb = a.subLevel.boundingBox();
                    wreckCenter[0] = new org.joml.Vector3d(
                            (bb.minX() + bb.maxX()) / 2.0,
                            (bb.minY() + bb.maxY()) / 2.0,
                            (bb.minZ() + bb.maxZ()) / 2.0);
                    MCPirates.LOGGER.info("[crash-test] wreck center captured at ({}, {}, {})",
                            String.format("%.1f", wreckCenter[0].x),
                            String.format("%.1f", wreckCenter[0].y),
                            String.format("%.1f", wreckCenter[0].z));
                })
                // CRASHED entry runs dismount + returns false from tickShip → brain deregisters.
                .thenWaitUntil(() -> helper.assertTrue(
                        AirshipBrain.ships().isEmpty(),
                        "waiting for brain deregister after CRASHED"))
                // Dismount ring radius = 6; widen search to 24 to catch any pillager that landed
                // outside the strict ring (terrain pick fallback, etc.).
                .thenExecute(() -> {
                    var wc = wreckCenter[0];
                    net.minecraft.world.phys.AABB searchBox = new net.minecraft.world.phys.AABB(
                            wc.x - 24, wc.y - 24, wc.z - 24,
                            wc.x + 24, wc.y + 24, wc.z + 24);
                    int vindicators = helper.getLevel().getEntitiesOfClass(
                            net.minecraft.world.entity.monster.Vindicator.class, searchBox).size();
                    int pillagers = helper.getLevel().getEntitiesOfClass(
                            net.minecraft.world.entity.monster.Pillager.class, searchBox).size();
                    MCPirates.LOGGER.info(
                            "[crash-test] post-dismount mobs near wreck: vindicators={} pillagers={} (envelope-blocks-stripped={})",
                            vindicators, pillagers, envelopeBlocksStripped[0]);
                    if (vindicators + pillagers == 0) {
                        helper.fail("no ground mobs found near wreck after CRASHED — dismount didn't fire?");
                    }
                    if (vindicators == 0) {
                        helper.fail("captain should always swap to Vindicator; got 0 vindicators");
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    /** Verifies the natural-rider migration: pillagers were dropped from the plot-pin model
     *  and now ride the SubLevel via Sable's automatic trackingSubLevel collision tracking.
     *  Assertions: ship navigates ≥20 blocks horizontally; every spawned crew UUID is still
     *  alive and inside an inflated SubLevel bbox after the move (i.e. didn't fall off). */
    @GameTest(template = "airship_small", timeoutTicks = 1500, setupTicks = 5,
              batch = "crew_rides_along_during_flight")
    public static void crewRidesAlongDuringFlight(GameTestHelper helper) {
        TestSetup.reset(helper);

        final java.util.concurrent.atomic.AtomicReference<Airship> shipRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Set<UUID> initialCrewUuids = new HashSet<>();
        final Vector3d initialShipPos = new Vector3d();

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
                        helper.fail("expected non-empty crew right after assembly");
                        return;
                    }
                    for (var ae : ship.anchoredEntities) {
                        initialCrewUuids.add(ae.uuid());
                    }
                    initialShipPos.set(ship.subLevel.logicalPose().position());
                    MCPirates.LOGGER.info(
                            "[crew-rides-test] {} pillager(s) spawned; ship at ({},{},{})",
                            initialCrewUuids.size(),
                            initialShipPos.x, initialShipPos.y, initialShipPos.z);
                })
                // Clear LIFTOFF before issuing NAVIGATE — the auto state machine ignores
                // navDestination while LIFTOFF is still in progress.
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    helper.assertTrue(
                            ship.state != State.LIFTOFF,
                            "waiting for LIFTOFF to clear (state=" + ship.state + ")");
                })
                .thenExecute(() -> {
                    Airship ship = shipRef.get();
                    Vector3d pos = ship.subLevel.logicalPose().position();
                    double destX = pos.x + 40.0;
                    double destZ = pos.z;
                    // Force-load the chunks along the navigate path. Without this the
                    // ship's SubLevel deactivates when it crosses the test arena's chunk
                    // boundary and stops ticking — passes in isolation (arena at origin)
                    // but fails in the full suite (arena offset thousands of blocks).
                    // See ramshipInterceptsMovingTarget for the same pattern.
                    ServerLevel level = helper.getLevel();
                    ChunkPos startChunk = new ChunkPos(new BlockPos((int) pos.x, 0, (int) pos.z));
                    ChunkPos destChunk = new ChunkPos(new BlockPos((int) destX, 0, (int) destZ));
                    for (ChunkPos centre : new ChunkPos[]{startChunk, destChunk}) {
                        for (int dx = -4; dx <= 4; dx++) {
                            for (int dz = -4; dz <= 4; dz++) {
                                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                            }
                        }
                    }
                    AirshipBrain.navigateTo(ship, destX, destZ);
                    MCPirates.LOGGER.info(
                            "[crew-rides-test] NAVIGATE to ({}, {}); current ship pos ({},{},{})",
                            destX, destZ, pos.x, pos.y, pos.z);
                })
                // Wait until the ship has actually travelled ≥20 horizontal blocks.
                .thenWaitUntil(() -> {
                    Airship ship = shipRef.get();
                    Vector3d pos = ship.subLevel.logicalPose().position();
                    double dx = pos.x - initialShipPos.x;
                    double dz = pos.z - initialShipPos.z;
                    double distSq = dx * dx + dz * dz;
                    helper.assertTrue(
                            distSq >= 20 * 20,
                            "waiting for ship to travel ≥20 blocks (now "
                                    + String.format("%.1f", Math.sqrt(distSq)) + ")");
                })
                // The actual assertion: every pillager UUID we recorded at spawn is still
                // alive AND its world position sits inside an inflated SubLevel bbox. Sable's
                // rideTick TAIL injection kicks seated entities to world coords each tick,
                // so e.getX/Y/Z is world-frame.
                .thenExecute(() -> {
                    Airship ship = shipRef.get();
                    var bb = ship.subLevel.boundingBox();
                    double minX = bb.minX() - 5, minY = bb.minY() - 5, minZ = bb.minZ() - 5;
                    double maxX = bb.maxX() + 5, maxY = bb.maxY() + 5, maxZ = bb.maxZ() + 5;
                    Vector3d shipPosNow = ship.subLevel.logicalPose().position();
                    MCPirates.LOGGER.info(
                            "[crew-rides-test] post-NAVIGATE ship at ({},{},{}); bbox=({},{},{})..({},{},{})",
                            shipPosNow.x, shipPosNow.y, shipPosNow.z,
                            bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());

                    int alive = 0, onShip = 0, missing = 0, deadOrRemoved = 0, fellOff = 0;
                    java.util.List<String> failures = new java.util.ArrayList<>();
                    for (UUID uuid : initialCrewUuids) {
                        net.minecraft.world.entity.Entity e = helper.getLevel().getEntity(uuid);
                        if (e == null) {
                            missing++;
                            failures.add("crew " + uuid + " entity missing from level");
                            continue;
                        }
                        if (e.isRemoved() || !e.isAlive()) {
                            deadOrRemoved++;
                            failures.add("crew " + uuid + " dead/removed at ("
                                    + e.getX() + "," + e.getY() + "," + e.getZ() + ")");
                            continue;
                        }
                        alive++;
                        double x = e.getX(), y = e.getY(), z = e.getZ();
                        boolean inside = x >= minX && x <= maxX
                                && y >= minY && y <= maxY
                                && z >= minZ && z <= maxZ;
                        if (inside) {
                            onShip++;
                            MCPirates.LOGGER.info(
                                    "[crew-rides-test] ✓ crew {} at ({},{},{}) inside bbox (riding={})",
                                    uuid, String.format("%.2f", x),
                                    String.format("%.2f", y), String.format("%.2f", z),
                                    e.getVehicle() != null);
                        } else {
                            fellOff++;
                            failures.add("crew " + uuid + " outside bbox at ("
                                    + String.format("%.2f", x) + ","
                                    + String.format("%.2f", y) + ","
                                    + String.format("%.2f", z) + ") riding="
                                    + (e.getVehicle() != null));
                        }
                    }
                    MCPirates.LOGGER.info(
                            "[crew-rides-test] summary: {} initial → alive={} onShip={} missing={} deadOrRemoved={} fellOff={}",
                            initialCrewUuids.size(), alive, onShip, missing, deadOrRemoved, fellOff);
                    if (!failures.isEmpty()) {
                        helper.fail(failures.size() + " crew failed ride-along check: "
                                + String.join("; ", failures));
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
