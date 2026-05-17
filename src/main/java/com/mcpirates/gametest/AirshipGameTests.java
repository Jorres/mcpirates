package com.mcpirates.gametest;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipBrain.State;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.AirshipRehydrator;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.kind.ClutchLevers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * GameTests for pirate-airship assembly + brain SubLevel-side effects. State-machine
 * transition logic is JUnit-covered in {@code AirshipBrainStateMachineTest}; these
 * tests cover what JUnit can't: the activation pipeline against
 * Create + Aeronautics + Sable + CBC, the lever writes {@code applyMovement} performs
 * in PURSUE, and lever-block resolution across NBT rotations.
 *
 * <p>All tests assign {@link Airship#state} directly to drive the brain past LIFTOFF
 * (arena is too small for physical liftoff) and call
 * {@link AirshipLiftoffTrigger#activateAnchor} to bypass the proximity scan.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirshipGameTests {

    private AirshipGameTests() {}

    /** Baseline: airship_small at rotation NONE, single cannon mount. */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_and_actuates_on_pursue")
    public static void assemblesAndActuatesOnPursue(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "airship_small", /*expectCannonMounts=*/ true);
    }

    /** Same NBT placed at rotation=CLOCKWISE_90. Verifies
     *  {@code detectRotationFromAnchor} picks the right rotation so the
     *  anchor-to-lever delta still resolves to the primary lever BE. */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_and_actuates_rotated_90", rotationSteps = 1)
    public static void assemblesAndActuatesRotated90(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "airship_small", /*expectCannonMounts=*/ true);
    }

    /** Different kind: crossbow_board. Primary anchor is a Create analog_lever
     *  (vs vanilla lever on airship_small), zero cannon mounts. Exercises the
     *  kind-dispatch path. */
    @GameTest(template = "crossbow_board", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_crossbow_board")
    public static void assemblesCrossbowBoardKindAndActuates(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "crossbow_board", /*expectCannonMounts=*/ false);
    }

    /**
     * On-foot routing: call {@code processNearbyAnchors} with
     * {@code playerOnAirship=false} 3 blocks east of the anchor. Asserts:
     * <ol>
     *   <li>Ground engagement registered for the anchor.</li>
     *   <li>At least one {@link Monster} spawned within 20 blocks.</li>
     *   <li>{@link AirshipBrain#ships()} stays empty (on-foot branch did NOT
     *       fall through to {@code activateAnchor}).</li>
     * </ol>
     *
     * <p>Drives the routing decision via an explicit boolean to
     * {@code processNearbyAnchors} so the test doesn't need to construct a real
     * (or mock) player for the on-foot path.
     */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "ground_combat_spawns")
    public static void groundCombatSpawnsForOnFootPlayer(GameTestHelper helper) {
        TestSetup.reset(helper);

        // Capture before assembly: activateAnchor moves the BE into the SubLevel, so
        // findAnchor stops working after the dormant assembly fires.
        final BlockPos[] anchorRef = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in the test arena");
                        return;
                    }
                    anchorRef[0] = anchorWorld;
                    AirshipLiftoffTrigger.processNearbyAnchors(
                            helper.getLevel(),
                            anchorWorld.getX() + 3.5,
                            anchorWorld.getZ() + 3.5,
                            /*playerOnAirship=*/ false);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    BlockPos anchorWorld = anchorRef[0];
                    if (anchorWorld == null) {
                        helper.fail("anchor capture failed");
                        return;
                    }
                    if (!AirshipLiftoffTrigger.hasGroundEngagement(anchorWorld)) {
                        helper.fail("no ground engagement registered at " + anchorWorld);
                        return;
                    }
                    int hostiles = helper.getLevel()
                            .getEntitiesOfClass(Monster.class,
                                    new AABB(anchorWorld).inflate(20))
                            .size();
                    if (hostiles < 1) {
                        helper.fail("ground engagement registered but no Monster entities spawned");
                        return;
                    }
                    // Dormant assembly: exactly one MOORED ship registered with no deck crew.
                    if (AirshipBrain.ships().size() != 1) {
                        helper.fail("expected 1 MOORED ship after on-foot trigger, got "
                                + AirshipBrain.ships().size());
                        return;
                    }
                    Airship ship = AirshipBrain.ships().get(0);
                    if (ship.state != State.MOORED) {
                        helper.fail("expected ship state=MOORED after dormant assembly, got "
                                + ship.state);
                        return;
                    }
                    if (!ship.anchoredEntities.isEmpty()) {
                        helper.fail("MOORED ship should have no deck crew, got "
                                + ship.anchoredEntities.size());
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    /**
     * Lifecycle: on-foot trigger → engagement registers → captain discarded →
     * engagement clears via {@link AirshipLiftoffTrigger#onEngagementMobLeave} →
     * return by air lifts the ship.
     */
    @GameTest(template = "airship_small", timeoutTicks = 600, setupTicks = 5,
              batch = "ground_combat_retreats_to_dormant")
    public static void groundCombatRetreatsToDormantThenAirArrivalLifts(GameTestHelper helper) {
        TestSetup.reset(helper);

        // Anchor BE moves into the SubLevel during dormant assembly, so cache the world
        // pos up front.
        final BlockPos[] anchorRef = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in the test arena");
                        return;
                    }
                    anchorRef[0] = anchorWorld;
                    AirshipLiftoffTrigger.processNearbyAnchors(
                            helper.getLevel(),
                            anchorWorld.getX() + 3.5,
                            anchorWorld.getZ() + 3.5,
                            /*playerOnAirship=*/ false);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    BlockPos anchorWorld = anchorRef[0];
                    if (!AirshipLiftoffTrigger.hasGroundEngagement(anchorWorld)) {
                        helper.fail("ground engagement did not register at " + anchorWorld);
                        return;
                    }
                    int hostiles = helper.getLevel()
                            .getEntitiesOfClass(Monster.class,
                                    new AABB(anchorWorld).inflate(20))
                            .size();
                    if (hostiles < 1) {
                        helper.fail("engagement registered but no defenders spawned");
                        return;
                    }
                    // Dormant assembly fired alongside ground combat — ship should be MOORED.
                    if (AirshipBrain.ships().size() != 1
                            || AirshipBrain.ships().get(0).state != State.MOORED) {
                        helper.fail("expected 1 MOORED ship after ground trigger; got "
                                + AirshipBrain.ships().size() + " ship(s) state="
                                + (AirshipBrain.ships().isEmpty()
                                    ? "—" : AirshipBrain.ships().get(0).state));
                    }
                })
                // Discard the captain — simulates vanilla checkDespawn firing.
                // discard() does not fire LivingDeathEvent, so DefeatedAirships is NOT
                // marked here; the ship stays MOORED + eligible for air-arrival promotion.
                .thenExecute(() -> {
                    BlockPos anchorWorld = anchorRef[0];
                    var captains = helper.getLevel()
                            .getEntitiesOfClass(Monster.class,
                                    new AABB(anchorWorld).inflate(40),
                                    m -> m.getTags().contains(MCPDataKeys.CAPTAIN_TAG));
                    if (captains.isEmpty()) {
                        helper.fail("no captain spawned for engagement at " + anchorWorld);
                        return;
                    }
                    captains.get(0).discard();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    BlockPos anchorWorld = anchorRef[0];
                    if (AirshipLiftoffTrigger.hasGroundEngagement(anchorWorld)) {
                        helper.fail("engagement still tracked at " + anchorWorld
                                + " after captain discard — leave-event handler didn't clear it");
                    }
                })
                // ── Step 3: return by air → MOORED → LIFTOFF promotion. ───────────
                .thenExecute(() -> {
                    BlockPos anchorWorld = anchorRef[0];
                    AirshipLiftoffTrigger.processNearbyAnchors(
                            helper.getLevel(),
                            anchorWorld.getX() + 3.5,
                            anchorWorld.getZ() + 3.5,
                            /*playerOnAirship=*/ true);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty()
                                && AirshipBrain.ships().get(0).state != State.MOORED,
                        "waiting for MOORED → LIFTOFF promotion after air arrival"))
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    /**
     * Simulates the server-restart path: assemble a ship, snapshot its identity,
     * wipe the brain registry as a restart would, then run the rehydrator and
     * assert the ship is back with the same SubLevel and crew UUIDs.
     *
     * <p>This is a *partial* simulation — gametests can't force an actual NBT
     * save/load cycle. What it does cover: ({@link MCPShipAnchorBlockEntity}'s
     * airpad+rotation stamps are present after assembly, the rehydrator's
     * delta-from-anchor formulas reproduce {@code activateShip}'s SL-local
     * positions, crew is found by airpad-anchor stamp, plot positions are
     * recomputable from {@code logicalPose}). It does NOT cover Sable's own
     * SubLevel save/load — that's outside the gametest framework's reach.
     */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "rehydrate_after_restart_hover")
    public static void rehydrateAtAirpadPicksHover(GameTestHelper helper) {
        // Ship hasn't moved since assembly → still at airpad → HOVER.
        runRehydrateTest(helper, ship -> {}, State.HOVER);
    }

    /** Move the SubLevel far from the airpad before unregister, so the rehydrator's
     *  airpad-proximity check picks RETURN over HOVER. */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "rehydrate_after_restart_return")
    public static void rehydrateAwayFromAirpadPicksReturn(GameTestHelper helper) {
        runRehydrateTest(helper, ship -> {
            // 32 blocks horizontal > HOVER_RADIUS (16) → rehydrator picks RETURN.
            Vector3d pos = ship.subLevel.logicalPose().position();
            pos.set(pos.x + 32, pos.y, pos.z);
        }, State.RETURN);
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
                    BlockPos anchorWorld = findAnchor(helper);
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
                    int n = AirshipRehydrator.rehydrateLevel(helper.getLevel());
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

    /**
     * Activate a ship, kill every anchored pillager, then assert on the next tick the
     * brain has deregistered the ship and disengaged both clutches (without touching
     * the throttle — defeated wreckage drifts on whatever lift it had).
     */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "crew_defeat_shutdown")
    public static void crewDefeatShutdownDisengagesAndDeregisters(GameTestHelper helper) {
        TestSetup.reset(helper);

        final java.util.concurrent.atomic.AtomicReference<Airship> shipRef = new java.util.concurrent.atomic.AtomicReference<>();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = findAnchor(helper);
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
                    // Kill every anchored pillager — captain + crew.
                    for (var ae : ship.anchoredEntities) {
                        var e = helper.getLevel().getEntity(ae.uuid());
                        if (e instanceof net.minecraft.world.entity.LivingEntity le) {
                            le.kill();
                        }
                    }
                })
                .thenIdle(2) // brain ticks once, detects no crew, disengages + deregisters
                .thenExecute(() -> {
                    if (!AirshipBrain.ships().isEmpty()) {
                        helper.fail("expected ships() empty after crew defeat, got "
                                + AirshipBrain.ships().size());
                    }
                    Airship ship = shipRef.get();
                    Level subLevelLevel = ship.subLevel.getLevel();
                    if (ClutchLevers.isEngaged(subLevelLevel, ship.slLeftClutchLever)) {
                        helper.fail("left clutch still engaged after crew defeat");
                    }
                    if (ClutchLevers.isEngaged(subLevelLevel, ship.slRightClutchLever)) {
                        helper.fail("right clutch still engaged after crew defeat");
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    /**
     * Defeat strips the mcpirates user-data stamp from the SubLevel, so the rehydrator
     * skips it entirely on a subsequent run — the wreck survives as a vanilla Sable
     * contraption with no further mcpirates control. Verifies that one-line cleanup.
     */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "rehydrate_skips_defeated_ship")
    public static void rehydrateSkipsDefeatedShip(GameTestHelper helper) {
        TestSetup.reset(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = findAnchor(helper);
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
                // Brain's defeat path strips the stamp + deregisters. Now run the rehydrator:
                // it should find no eligible SubLevels (stamp gone) and register nothing.
                .thenIdle(2)
                .thenExecute(() -> AirshipBrain.unregisterAll(helper.getLevel()))
                .thenExecute(() -> {
                    int n = AirshipRehydrator.rehydrateLevel(helper.getLevel());
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

    /**
     * Spawn two airship_smalls in the same arena (the second placed manually via
     * StructureTemplateManager 20 blocks offset), activate both, unregister all,
     * rehydrate. Asserts both ships come back with distinct SubLevel UUIDs and
     * the brain holds them independently.
     */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "multi_ship_rehydrate")
    public static void multiShipRehydrate(GameTestHelper helper) {
        TestSetup.reset(helper);

        final java.util.Set<UUID> preWipeUuids = new HashSet<>();

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos firstAnchor = findAnchor(helper);
                    if (firstAnchor == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in arena");
                        return;
                    }
                    // Place a SECOND airship_small structure 20 blocks east of the arena.
                    ServerLevel level = helper.getLevel();
                    var templateOpt = level.getStructureManager().get(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    MCPirates.MOD_ID, "airship_small"));
                    if (templateOpt.isEmpty()) {
                        helper.fail("airship_small template not loaded");
                        return;
                    }
                    BlockPos secondOrigin = firstAnchor.offset(20, -firstAnchor.getY() - 56, 0); // align Y to airpad
                    // Cleaner: just offset X; keep same Y/Z as the original template's bbox base.
                    AABB bb = helper.getBounds();
                    secondOrigin = new BlockPos(
                            (int) Math.ceil(bb.maxX) + 5,
                            (int) Math.floor(bb.minY),
                            (int) Math.floor(bb.minZ));
                    templateOpt.get().placeInWorld(level, secondOrigin, secondOrigin,
                            new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                            level.getRandom(), 2);
                    // Activate both anchors.
                    java.util.List<BlockPos> anchors = findAnchorsInRadius(helper, firstAnchor, 64);
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
                    int n = AirshipRehydrator.rehydrateLevel(helper.getLevel());
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

    /**
     * Shared body: activate via the production trigger, drive RETURN→PURSUE with
     * a faked target, assert PURSUE engaged and at least one clutch lever flipped.
     * Proves production-resolved SubLevel positions are valid blocks after
     * assembly and that {@code applyMovement} actually writes to them.
     */
    private static void runAssemblyAndPursueTest(GameTestHelper helper,
                                                 String expectedKindName,
                                                 boolean expectCannonMounts) {
        TestSetup.reset(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = findAnchor(helper);
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
                // Inject a faked target. The zombie is never added to the world —
                // the brain only reads getX/Y/Z/isAlive, all valid on a fresh entity.
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
                    Level slLevel = ship.subLevel.getLevel();
                    boolean leftEngaged = ClutchLevers.isEngaged(slLevel, ship.slLeftClutchLever);
                    boolean rightEngaged = ClutchLevers.isEngaged(slLevel, ship.slRightClutchLever);
                    if (!leftEngaged && !rightEngaged) {
                        BlockState left = slLevel.getBlockState(ship.slLeftClutchLever);
                        BlockState right = slLevel.getBlockState(ship.slRightClutchLever);
                        helper.fail("PURSUE reached but neither clutch engaged "
                                + "(L@" + ship.slLeftClutchLever + "=" + left.getBlock()
                                + " R@" + ship.slRightClutchLever + "=" + right.getBlock() + ")");
                    }
                })
                .thenExecute(() -> TestSetup.reset(helper))
                .thenSucceed();
    }

    /** Walk every BE in the test arena's bounding box, returning the world position of
     *  the first {@link MCPShipAnchorBlockEntity} encountered, or null. */
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

    /** Find all MCPShipAnchorBlockEntity instances in the level near {@code centre},
     *  within {@code radius}. Used by multi-ship tests that place a second template
     *  outside the gametest arena bounds. */
    private static java.util.List<BlockPos> findAnchorsInRadius(GameTestHelper helper, BlockPos centre, int radius) {
        java.util.List<BlockPos> anchors = new java.util.ArrayList<>();
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
