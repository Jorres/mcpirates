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
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

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
public final class AirshipGameTests {

    private AirshipGameTests() {}

    /** Baseline: airship_small at rotation NONE, single cannon mount. */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_and_actuates_on_pursue")
    @PrefixGameTestTemplate(false)
    public static void assemblesAndActuatesOnPursue(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "airship_small", /*expectCannonMounts=*/ true);
    }

    /** Same NBT placed at rotation=CLOCKWISE_90. Verifies
     *  {@code detectRotationFromAnchor} picks the right rotation so the
     *  anchor-to-lever delta still resolves to the primary lever BE. */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_and_actuates_rotated_90", rotationSteps = 1)
    @PrefixGameTestTemplate(false)
    public static void assemblesAndActuatesRotated90(GameTestHelper helper) {
        runAssemblyAndPursueTest(helper, "airship_small", /*expectCannonMounts=*/ true);
    }

    /** Different kind: crossbow_board. Primary anchor is a Create analog_lever
     *  (vs vanilla lever on airship_small), zero cannon mounts. Exercises the
     *  kind-dispatch path. */
    @GameTest(template = "crossbow_board", timeoutTicks = 400, setupTicks = 5,
              batch = "assembles_crossbow_board")
    @PrefixGameTestTemplate(false)
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
     * <p>Skips the join pipeline (mock ServerPlayer creation) which crashes
     * under this modpack — drives the routing decision via an explicit boolean
     * to {@code processNearbyAnchors} instead.
     */
    @GameTest(template = "airship_small", timeoutTicks = 400, setupTicks = 5,
              batch = "ground_combat_spawns")
    @PrefixGameTestTemplate(false)
    public static void groundCombatSpawnsForOnFootPlayer(GameTestHelper helper) {
        TestSetup.reset(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in the test arena");
                        return;
                    }
                    AirshipLiftoffTrigger.processNearbyAnchors(
                            helper.getLevel(),
                            anchorWorld.getX() + 3.5,
                            anchorWorld.getZ() + 3.5,
                            /*playerOnAirship=*/ false);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("anchor went missing");
                        return;
                    }
                    if (!AirshipLiftoffTrigger.hasGroundEngagement(anchorWorld)) {
                        helper.fail("no ground engagement registered at " + anchorWorld);
                        return;
                    }
                    int hostiles = helper.getLevel()
                            .getEntitiesOfClass(Monster.class,
                                    new net.minecraft.world.phys.AABB(anchorWorld).inflate(20))
                            .size();
                    if (hostiles < 1) {
                        helper.fail("ground engagement registered but no Monster entities spawned");
                        return;
                    }
                    if (!AirshipBrain.ships().isEmpty()) {
                        helper.fail("ship registered despite playerOnAirship=false (got "
                                + AirshipBrain.ships().size() + " ships)");
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
    @PrefixGameTestTemplate(false)
    public static void groundCombatRetreatsToDormantThenAirArrivalLifts(GameTestHelper helper) {
        TestSetup.reset(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    if (anchorWorld == null) {
                        helper.fail("no MCPShipAnchorBlockEntity in the test arena");
                        return;
                    }
                    AirshipLiftoffTrigger.processNearbyAnchors(
                            helper.getLevel(),
                            anchorWorld.getX() + 3.5,
                            anchorWorld.getZ() + 3.5,
                            /*playerOnAirship=*/ false);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    if (!AirshipLiftoffTrigger.hasGroundEngagement(anchorWorld)) {
                        helper.fail("ground engagement did not register at " + anchorWorld);
                        return;
                    }
                    int hostiles = helper.getLevel()
                            .getEntitiesOfClass(Monster.class,
                                    new net.minecraft.world.phys.AABB(anchorWorld).inflate(20))
                            .size();
                    if (hostiles < 1) {
                        helper.fail("engagement registered but no defenders spawned");
                    }
                })
                // Discard the captain — simulates vanilla checkDespawn firing.
                .thenExecute(() -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    var captains = helper.getLevel()
                            .getEntitiesOfClass(Monster.class,
                                    new net.minecraft.world.phys.AABB(anchorWorld).inflate(40),
                                    m -> m.getTags().contains(MCPDataKeys.CAPTAIN_TAG));
                    if (captains.isEmpty()) {
                        helper.fail("no captain spawned for engagement at " + anchorWorld);
                        return;
                    }
                    captains.get(0).discard();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    if (AirshipLiftoffTrigger.hasGroundEngagement(anchorWorld)) {
                        helper.fail("engagement still tracked at " + anchorWorld
                                + " after captain discard — leave-event handler didn't clear it");
                    }
                })
                // ── Step 3: return by air. ─────────────────────────────────────────
                .thenExecute(() -> {
                    BlockPos anchorWorld = findAnchor(helper);
                    AirshipLiftoffTrigger.processNearbyAnchors(
                            helper.getLevel(),
                            anchorWorld.getX() + 3.5,
                            anchorWorld.getZ() + 3.5,
                            /*playerOnAirship=*/ true);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        !AirshipBrain.ships().isEmpty(),
                        "waiting for AirshipBrain.register after air arrival"))
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
              batch = "rehydrate_after_restart")
    @PrefixGameTestTemplate(false)
    public static void rehydrateRestoresFlyingShipAfterBrainWipe(GameTestHelper helper) {
        TestSetup.reset(helper);

        final java.util.UUID[] preWipeSubLevelId = new java.util.UUID[1];
        final java.util.Set<java.util.UUID> preWipeCrewUuids = new java.util.HashSet<>();
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
                    java.util.Set<java.util.UUID> postWipeCrewUuids = new java.util.HashSet<>();
                    for (var ae : ship.anchoredEntities) {
                        postWipeCrewUuids.add(ae.uuid());
                    }
                    if (!postWipeCrewUuids.equals(preWipeCrewUuids)) {
                        helper.fail("crew UUID set mismatch: pre=" + preWipeCrewUuids.size()
                                + " post=" + postWipeCrewUuids.size());
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
                    org.joml.Vector3d shipPos = ship.subLevel.logicalPose().position();
                    BlockPos zPos = BlockPos.containing(shipPos.x + 12, shipPos.y, shipPos.z);
                    Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
                    if (zombie == null) {
                        helper.fail("EntityType.ZOMBIE.create returned null");
                        return;
                    }
                    zombie.moveTo(zPos.getX() + 0.5, zPos.getY(), zPos.getZ() + 0.5);
                    AirshipBrain.targetOverride = a -> zombie.isAlive() ? zombie : null;
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
        net.minecraft.world.phys.AABB bb = helper.getBounds();
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
