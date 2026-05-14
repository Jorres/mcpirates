package com.mcpirates.airship;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.AirshipKinds;
import com.mcpirates.airship.kind.HotAirBurners;
import com.mcpirates.airship.kind.ThrottleLevers;
import com.mcpirates.pirates.CaptainSpawner;
import com.mcpirates.pirates.DefeatedAirships;
import com.mcpirates.pirates.GroundCombatModule;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity;
import dev.simulated_team.simulated.multiloader.inventory.ItemInfoWrapper;
import dev.simulated_team.simulated.util.SimAssemblyHelper.AssemblyResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers dormant pirate airships and triggers their lift-off when a player gets within
 * {@link #PLAYER_CHUNK_RADIUS} chunks. The trigger fires once per ship — subsequent passes
 * skip already-triggered ships by the {@code !(be instanceof MCPShipAnchorBlockEntity)}
 * check on the world anchor BE (assembly moves it into the SubLevel, leaving air).
 *
 * <p><strong>How a ship is identified:</strong> every airship structure NBT contains one
 * {@link com.mcpirates.airship.anchor.MCPShipAnchorBlock} placed inside the hull by
 * {@code tools/build_ships.py}; its BE stores the kind name (e.g. {@code "airship_small"}).
 * The trigger scans chunk block-entity lists for {@link MCPShipAnchorBlockEntity} instances,
 * resolves the kind via {@link AirshipKinds#byName}, and from there knows exactly which
 * {@link AirshipKind} to use — no geometric guessing. The primary lever is derived from the
 * anchor's world position via {@link AirshipKind#anchorToLeverDelta()}.
 *
 * <p>On trigger we run the canonical startup:
 *
 * <ol>
 *     <li>Insert 64 coal into each portable engine the kind owns.</li>
 *     <li>Leave the throttle levers in their NBT-baked state. The structure NBT
 *         already has them at a positive value, so the burner ignites on assembly
 *         and the brain takes over on its first decision tick.</li>
 *     <li>Spawn the honey-glue body using the kind's bounding box.</li>
 *     <li>Assemble the ship into a Sable SubLevel via {@link AirshipAssembler}.</li>
 *     <li>Reflectively call {@code CannonMountBlockEntity.assemble()} on each mount.</li>
 *     <li>Spawn captain + crewmate.</li>
 *     <li>Hand over to {@link AirshipBrain}.</li>
 * </ol>
 *
 * <p><strong>Structure rotation.</strong> Jigsaw can rotate a ship piece 0/90/180/270°. We
 * detect rotation by trying all four rotations of the kind's {@link AirshipKind#anchorToLeverDelta
 * anchorToLeverDelta} and picking the one that lands on the kind's primary-lever BE.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipLiftoffTrigger {

    /** Runtime kill-switch for the auto-trigger. Set false (via
     *  {@code /mcpirates lift off}) when iterating on ship NBT designs in a flat test
     *  world — placed ships stay dormant indefinitely so they can be inspected and
     *  re-saved through structure blocks without lifting off. Defaults true so production
     *  ships still activate when a player approaches. */
    public static volatile boolean AUTO_LIFTOFF_ENABLED = true;

    public static boolean setAutoLiftoffEnabled(boolean enabled) {
        AUTO_LIFTOFF_ENABLED = enabled;
        return enabled;
    }

    private static final int TICK_INTERVAL = 10;          // ~0.5 s at 20 TPS
    /** Trigger when an enemy-on-airship is within this chunk radius of a dormant pirate
     *  anchor. 10 chunks ≈ 160 blocks. */
    private static final int PLAYER_CHUNK_RADIUS = 10;
    private static final double TRIGGER_DISTANCE_SQ = (10 * 16) * (10 * 16);

    private static Method cachedCannonAssembleMethod;
    private static Field cachedEntityManagerField;
    private static Field cachedSectionStorageField;
    private static Method cachedGetSectionMethod;
    private static Method cachedSectionGetStatusMethod;

    /** Ground-combat engagements, keyed by ship-anchor world position. Populated when an
     *  on-foot player approaches a dormant ship whose kind opts into ground combat.
     *  Cleared either by air-combat takeover ({@link #activateAnchor}) or by the captain
     *  leaving its level with reason KILLED/DISCARDED ({@link #onEngagementMobLeave}).
     *  Concurrent because the server tick can race against /mcpirates lift off paths.
     *
     *  <p>Defenders are non-persistent — vanilla {@code checkDespawn} handles cleanup
     *  when the player drifts away, and the captain-leave event is what tells us the
     *  encounter has ended. No retry sweep, no last-seen bookkeeping. */
    private static final Map<BlockPos, GroundCombatModule.GroundEngagement> GROUND_ENGAGEMENTS =
            new ConcurrentHashMap<>();

    /** In-flight activation guard. {@code activateAnchor} adds the anchor pos before
     *  starting assembly and removes it after (or on failure). A second concurrent
     *  call for the same anchor returns false immediately, so the half-second
     *  proximity scanner can't fire a second {@code activateShip} on a structure
     *  whose blocks are still being yanked into a SubLevel. */
    private static final Set<BlockPos> ACTIVATING = ConcurrentHashMap.newKeySet();

    /** True iff a ground-combat engagement is currently tracked for the given anchor.
     *  Read-only view of {@link #GROUND_ENGAGEMENTS}, intended for diagnostics
     *  ({@code /mcpirates status}) and integration tests that need to assert
     *  {@link #checkAroundPlayer}'s on-foot branch fired. */
    public static boolean hasGroundEngagement(BlockPos anchorPos) {
        return GROUND_ENGAGEMENTS.containsKey(anchorPos);
    }

    /** Despawn every tracked ground-combat defender in {@code level} and clear their map
     *  entries. Returns the count of entities actually removed (already-dead/removed
     *  attackers are skipped). Intended for dimension teardown and for GameTest setup
     *  (the map is JVM-static, so a test in a fresh batch would otherwise observe stale
     *  engagements from a previous test). */
    public static int clearGroundEngagements(ServerLevel level) {
        int removed = 0;
        for (GroundCombatModule.GroundEngagement e : GROUND_ENGAGEMENTS.values()) {
            removed += GroundCombatModule.SHARED.despawn(level, e);
        }
        GROUND_ENGAGEMENTS.clear();
        return removed;
    }

    private AirshipLiftoffTrigger() {}

    /**
     * Activate a single anchor: resolve kind + rotation, spawn honey glue, then run the full
     * liftoff sequence ({@link #activateShip}). Idempotent — already-activated anchors are
     * skipped silently, so it's safe to call every tick from {@link #checkAroundPlayer} as
     * well as one-shot from a GameTest.
     *
     * <p>Returns {@code true} only when activation actually fired. Returns {@code false}
     * (silently — caller will retry next tick) when rotation can't be detected yet, the
     * lever BE isn't loaded, the ship is already activated, or the honey-glue chunk section
     * is HIDDEN. Logs a warning when the anchor isn't an {@link MCPShipAnchorBlockEntity}
     * or the recorded kind name is unknown — those are configuration errors, not transient
     * chunk-loading state.
     */
    public static boolean activateAnchor(ServerLevel level, BlockPos anchorPos) {
        if (!ACTIVATING.add(anchorPos)) return false;   // already mid-assembly
        try {
            BlockEntity be = level.getBlockEntity(anchorPos);
            if (!(be instanceof MCPShipAnchorBlockEntity anchorBe)) {
                MCPirates.LOGGER.warn("activateAnchor: no ship anchor BE at {}", anchorPos);
                return false;
            }
            AirshipKind kind = AirshipKinds.byName(anchorBe.getKindName());
            if (kind == null) {
                MCPirates.LOGGER.warn("activateAnchor: unknown kind '{}' at {}",
                        anchorBe.getKindName(), anchorPos);
                return false;
            }
            Rotation rotation = detectRotationFromAnchor(level, anchorPos, kind);
            if (rotation == null) {
                // chunk not loaded enough yet to resolve the primary-lever BE — retry next pass
                return false;
            }
            BlockPos leverPos = anchorPos.offset(kind.anchorToLeverDelta().rotate(rotation));
            BlockEntity leverBe = level.getBlockEntity(leverPos);
            if (leverBe == null) {
                // lever BE not yet loaded — retry next pass
                return false;
            }
            // Defeated guard: if a captain stamped on this ship's lever pos was already
            // killed in a previous session, the ship is the player's prize. Don't lift
            // it off; that would respawn a captain and let the seal double-drop.
            if (DefeatedAirships.get(level).containsExact(leverPos)) {
                return false;
            }
            if (!spawnHoneyGlue(level, leverPos, rotation, kind)) {
                // glue deferred (chunk section HIDDEN) — spawnHoneyGlue already logged at info
                return false;
            }
            // Air combat preempts any pre-existing ground engagement for this anchor:
            // discard survivors so the deck crew doesn't double up with leftover defenders.
            GroundCombatModule.GroundEngagement engagement = GROUND_ENGAGEMENTS.remove(anchorPos);
            if (engagement != null) {
                int removed = GroundCombatModule.SHARED.despawn(level, engagement);
                MCPirates.LOGGER.info(
                        "ground combat: liftoff at anchor {} cleared {} surviving defender(s)",
                        anchorPos, removed);
            }
            activateShip(level, leverPos, kind, rotation);
            return true;
        } finally {
            ACTIVATING.remove(anchorPos);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % TICK_INTERVAL != 0) {
            return;
        }
        if (!AUTO_LIFTOFF_ENABLED) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                checkAroundPlayer(level, player);
            }
        }
    }

    /** Clear the engagement when its captain leaves the world with reason KILLED
     *  (player kill — CaptainDeath drops seal + marks defeated in parallel) or
     *  DISCARDED (vanilla checkDespawn). UNLOADED_TO_CHUNK is ignored: the captain
     *  is on disk and will return next reload, so clearing would orphan him. */
    @SubscribeEvent
    public static void onEngagementMobLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!entity.getTags().contains(MCPDataKeys.CAPTAIN_TAG)) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        UUID captainUuid = entity.getUUID();
        for (Map.Entry<BlockPos, GroundCombatModule.GroundEngagement> entry : GROUND_ENGAGEMENTS.entrySet()) {
            if (!entry.getValue().attackerUuids().contains(captainUuid)) continue;
            BlockPos anchorPos = entry.getKey();
            GROUND_ENGAGEMENTS.remove(anchorPos);
            MCPirates.LOGGER.info("ground combat: captain {} ({}) — engagement at {} cleared",
                    captainUuid, reason, anchorPos);
            return;
        }
    }

    private static void checkAroundPlayer(ServerLevel level, ServerPlayer player) {
        // Discriminator: a player flying their own airship sits on a SubLevel; a player
        // on the ground does not. Foot players summon the ground-combat module (the
        // "prize fight" — kill the defenders and the ship is yours); SubLevel players
        // trigger the full air-combat liftoff. See GroundCombatModule for the design.
        boolean playerOnAirship = Sable.HELPER.getContaining(player) != null;
        processNearbyAnchors(level, player.getX(), player.getZ(), playerOnAirship);
    }

    /**
     * Iterate dormant pirate anchors within {@link #TRIGGER_DISTANCE_SQ} of (x, z) and
     * dispatch each one — to {@link #activateAnchor} if the caller is on an airship, or
     * {@link #maybeSpawnGroundCombat} otherwise. Public because (a) gametests need to
     * exercise the on-foot routing without depending on a real {@code ServerPlayer}
     * (which would drag in Sable + the full join-event pipeline, where other mods'
     * handlers have crashed mock players), and (b) admin/debug commands could legitimately
     * want to trigger a manual sweep from a coordinate.
     */
    public static void processNearbyAnchors(ServerLevel level, double x, double z,
                                            boolean playerOnAirship) {
        ChunkPos centre = new ChunkPos(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
        for (int dx = -PLAYER_CHUNK_RADIUS; dx <= PLAYER_CHUNK_RADIUS; dx++) {
            for (int dz = -PLAYER_CHUNK_RADIUS; dz <= PLAYER_CHUNK_RADIUS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);
                if (chunk == null) {
                    continue;
                }
                // Snapshot the BE map — activateShip yanks block entities out of the chunk
                // via the assembly path, which would corrupt a live iterator (NPE in fastutil).
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (!(be instanceof MCPShipAnchorBlockEntity)) continue;
                    BlockPos anchorPos = be.getBlockPos();
                    double pdx = anchorPos.getX() + 0.5 - x;
                    double pdz = anchorPos.getZ() + 0.5 - z;
                    if (pdx * pdx + pdz * pdz > TRIGGER_DISTANCE_SQ) continue;
                    if (playerOnAirship) {
                        activateAnchor(level, anchorPos);
                    } else {
                        maybeSpawnGroundCombat(level, anchorPos);
                    }
                }
            }
        }
    }

    /**
     * Spawn the ground-combat module for this anchor if (a) the kind opts in, (b) we
     * haven't already engaged this anchor in this server lifetime, and (c) the anchor
     * resolves cleanly (kind known, rotation detectable). Idempotent on repeat calls.
     */
    private static void maybeSpawnGroundCombat(ServerLevel level, BlockPos anchorPos) {
        if (GROUND_ENGAGEMENTS.containsKey(anchorPos)) return;
        BlockEntity be = level.getBlockEntity(anchorPos);
        if (!(be instanceof MCPShipAnchorBlockEntity anchorBe)) return;
        AirshipKind kind = AirshipKinds.byName(anchorBe.getKindName());
        if (kind == null) return;
        if (kind.groundCombat().isEmpty()) return;
        Rotation rotation = detectRotationFromAnchor(level, anchorPos, kind);
        if (rotation == null) return; // chunks not primed; retry next pass
        // An already-triggered ship has its anchor BE moved into the SubLevel during
        // assembly, so the early `!(be instanceof MCPShipAnchorBlockEntity)` check
        // above already catches that case — no need for a lever-state cross-check.
        BlockPos leverPos = anchorPos.offset(kind.anchorToLeverDelta().rotate(rotation));
        // Defeated-ship guard: GROUND_ENGAGEMENTS is in-memory (wiped on restart),
        // but DefeatedAirships persists to SavedData. Without this check, walking
        // up to a previously-cleared ship in a new server session would re-spawn
        // its defenders.
        if (DefeatedAirships.get(level).containsExact(leverPos)) return;

        // Rebuild from world: if a captain stamped with this lever pos still exists
        // (chunks reloaded after a server restart, or the encounter outlived the
        // in-memory map for any other reason), adopt its UUID(s) instead of spawning
        // a duplicate captain. The leave-event handler then clears the engagement
        // when that existing captain dies or vanilla-despawns.
        long leverPosLong = leverPos.asLong();
        AABB scan = new AABB(leverPos).inflate(GroundCombatModule.LEASH_RADIUS + 16);
        List<UUID> existing = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, scan, m ->
                m.getTags().contains(MCPDataKeys.CAPTAIN_TAG)
                        && m.getPersistentData().getLong(MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY)
                                == leverPosLong)) {
            existing.add(mob.getUUID());
        }
        if (!existing.isEmpty()) {
            GROUND_ENGAGEMENTS.put(anchorPos,
                    new GroundCombatModule.GroundEngagement(Collections.unmodifiableList(existing)));
            MCPirates.LOGGER.info(
                    "ground combat: adopted {} existing captain(s) at anchor {}",
                    existing.size(), anchorPos);
            return;
        }

        GroundCombatModule module = kind.groundCombat().get();
        GroundCombatModule.GroundEngagement engagement = module.spawn(level, leverPos, rotation, kind);
        GROUND_ENGAGEMENTS.put(anchorPos, engagement);
    }

    /** Try each 90° rotation; the one whose anchor-to-lever delta resolves to a cell
     *  containing the kind's primary-anchor BE is the ship's rotation. Returns null if
     *  no rotation produces a match (chunk not yet primed — caller should retry). */
    private static Rotation detectRotationFromAnchor(ServerLevel level, BlockPos anchorPos, AirshipKind kind) {
        BlockPos delta = kind.anchorToLeverDelta();
        for (Rotation r : Rotation.values()) {
            BlockPos leverWorld = anchorPos.offset(delta.rotate(r));
            BlockEntity be = level.getBlockEntity(leverWorld);
            if (be != null && kind.isPrimaryAnchorBE(be)) {
                return r;
            }
        }
        return null;
    }

    /** Full lift-off sequence — generic over {@link AirshipKind}. */
    private static void activateShip(ServerLevel level, BlockPos pos, AirshipKind kind, Rotation rotation) {
        BlockState anchorState = level.getBlockState(pos);
        Direction connected = ThrottleLevers.leverConnectedDirection(anchorState);

        // Resolve world-frame positions for all NBT deltas the liftoff sequence touches.
        List<BlockPos> enginePositions = new ArrayList<>(kind.engineDeltas().size());
        for (BlockPos d : kind.engineDeltas()) enginePositions.add(pos.offset(d.rotate(rotation)));
        List<BlockPos> throttleLeverPositions = new ArrayList<>(kind.throttleLeverDeltas().size());
        for (BlockPos d : kind.throttleLeverDeltas()) throttleLeverPositions.add(pos.offset(d.rotate(rotation)));
        BlockPos leftClutchPos = pos.offset(kind.leftClutchLeverDelta().rotate(rotation));
        BlockPos rightClutchPos = pos.offset(kind.rightClutchLeverDelta().rotate(rotation));
        List<BlockPos> cannonMountPositions = new ArrayList<>(kind.cannonMountDeltas().size());
        for (BlockPos d : kind.cannonMountDeltas()) cannonMountPositions.add(pos.offset(d.rotate(rotation)));

        MCPirates.LOGGER.info(
                "pirate trigger ({}) at {} (rotation={}): engines={} cannons={} left={} right={} throttles={}",
                kind.name(), pos, rotation, enginePositions, cannonMountPositions,
                leftClutchPos, rightClutchPos, throttleLeverPositions);

        // Step 1: fuel every engine
        for (BlockPos enginePos : enginePositions) {
            insertCoalIntoEngine(level, enginePos);
        }

        // (No throttle-lever write here — the structure NBT bakes the lever at a
        // positive state so the burner ignites on its own. Brain reads the SubLevel's
        // burner state on its first decision tick and overwrites with the plateau pick.)
        MCPirates.LOGGER.info("trigger ({}) at {}: {} throttle lever(s), waiting on brain",
                kind.name(), pos, throttleLeverPositions.size());

        // Step 3: assemble — honey glue was spawned earlier in this same pass.
        BlockPos assemblySeed = pos.relative(connected.getOpposite());
        AssemblyResult result = AirshipAssembler.assemble(level, assemblySeed);
        if (result == null) {
            MCPirates.LOGGER.warn("ship assembly failed; aborting startup at {}", pos);
            return;
        }
        SubLevel subLevel = result.subLevel();
        BlockPos offset = result.offset();

        // Step 4: locate + assemble all cannon mounts
        List<BlockPos> slCannonMounts = new ArrayList<>(cannonMountPositions.size());
        for (BlockPos worldMount : cannonMountPositions) {
            BlockPos slMount = worldMount.offset(offset);
            BlockPos assembled = triggerCannonAssembly(subLevel.getLevel(), slMount);
            if (assembled != null) slCannonMounts.add(assembled);
        }
        if (slCannonMounts.size() != cannonMountPositions.size()) {
            MCPirates.LOGGER.warn("({}) cannon assembly partial: {}/{} succeeded",
                    kind.name(), slCannonMounts.size(), cannonMountPositions.size());
        }

        // Step 5: SubLevel-local positions for the brain
        List<BlockPos> slThrottleLevers = new ArrayList<>(throttleLeverPositions.size());
        for (BlockPos w : throttleLeverPositions) slThrottleLevers.add(w.offset(offset));
        BlockPos slLeftClutch = leftClutchPos.offset(offset);
        BlockPos slRightClutch = rightClutchPos.offset(offset);

        // Step 5b: scan the SubLevel plot for every Hot Air Burner. The brain writes the
        // same (volume, lever) to all burners and queries any one for balloon capacity,
        // so we don't need to pair them to specific levers.
        var plotBox = subLevel.getPlot().getBoundingBox();
        List<BlockPos> slBurnerPositions = HotAirBurners.findAllInBox(
                subLevel.getLevel(),
                plotBox.minX(), plotBox.minY(), plotBox.minZ(),
                plotBox.maxX(), plotBox.maxY(), plotBox.maxZ());
        if (slBurnerPositions.isEmpty()) {
            MCPirates.LOGGER.warn("({}) no Hot Air Burners found in SubLevel plot; lift control disabled",
                    kind.name());
        }

        // Step 5c: stamp kind/airpad/rotation/primary-anchor on the SubLevel's
        // userDataTag (Sable persists it across save/load). AirshipRehydrator reads
        // these on server start to re-register surviving ships with the brain —
        // without them, a ship that was flying at shutdown comes back un-controlled
        // (no clutch/throttle writes, no plot rebind for crew).
        BlockPos slPrimaryAnchorPos = pos.offset(offset);
        if (subLevel instanceof ServerSubLevel ssl) {
            CompoundTag userTag = ssl.getUserDataTag();
            if (userTag == null) userTag = new CompoundTag();
            CompoundTag mcp = new CompoundTag();
            mcp.putString("kind", kind.name());
            mcp.putLong("airpad", pos.asLong());
            mcp.putInt("rotation", rotation.ordinal());
            mcp.putLong("slPrimaryAnchor", slPrimaryAnchorPos.asLong());
            userTag.put("mcpirates", mcp);
            ssl.setUserDataTag(userTag);
        }

        // Step 6: forward vector
        Direction shipForwardDir = rotation.rotate(kind.nbtForward());
        Vector3d shipLocalForward = new Vector3d(
                shipForwardDir.getStepX(), shipForwardDir.getStepY(), shipForwardDir.getStepZ());

        // Step 7: scan seats, bind cannons to nearest seats, spawn the crew. Returns both
        // the anchor list (for the brain to drive per-pirate roles) and the cannon→
        // cannoneer UUID map (for combat behaviours to gate fire on isMountManned).
        CaptainSpawner.CrewSpawnResult crew =
                CaptainSpawner.spawn(subLevel, pos, offset, rotation, kind, slCannonMounts);

        // Step 8: register with the brain in LIFTOFF state. Fresh assemblies go through
        // the full LIFTOFF → PURSUE/RETURN/HOVER state-machine path. (The rehydrator's
        // SubLevelObserver also caught the allocate; its later tryRehydrate call sees
        // the UUID already registered here and skips.)
        AirshipBrain.register(
                level, subLevel, pos, kind,
                slThrottleLevers, slBurnerPositions, slLeftClutch, slRightClutch, slCannonMounts,
                shipLocalForward, crew.anchors(), crew.cannoneerByMount(),
                AirshipBrain.State.LIFTOFF);
    }

    /**
     * Spawn a {@link HoneyGlueEntity} covering the airship body, inflated generously so
     * rotation never clips. Returns false if the chunk section is HIDDEN — caller retries
     * once the chunk is promoted to ENTITY_TICKING.
     */
    private static boolean spawnHoneyGlue(ServerLevel level, BlockPos anchorPos,
                                          Rotation rotation, AirshipKind kind) {
        BlockPos a = anchorPos.offset(kind.glueMin().rotate(rotation));
        BlockPos b = anchorPos.offset(kind.glueMax().rotate(rotation));
        double minX = Math.min(a.getX(), b.getX());
        double minY = Math.min(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxX = Math.max(a.getX(), b.getX()) + 1.0;
        double maxY = Math.max(a.getY(), b.getY()) + 1.0;
        double maxZ = Math.max(a.getZ(), b.getZ()) + 1.0;
        AABB aabb = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        HoneyGlueEntity glue = new HoneyGlueEntity(level, aabb);
        boolean added = level.addFreshEntity(glue);
        String visibilityStr = readSectionVisibility(level, glue);
        FullChunkStatus chunkStatus =
                level.getChunkSource().getChunkNow(
                        glue.chunkPosition().x, glue.chunkPosition().z) instanceof
                        LevelChunk lc
                        ? lc.getFullStatus() : null;
        if ("HIDDEN".equals(visibilityStr)) {
            glue.discard();
            MCPirates.LOGGER.info(
                    "deferred honey glue spawn for {} {} (chunk=({}, {}) at status {}, section HIDDEN) — retry next pass",
                    kind.name(), anchorPos,
                    glue.chunkPosition().x, glue.chunkPosition().z, chunkStatus);
            return false;
        }
        MCPirates.LOGGER.info(
                "spawned runtime honey glue {} (uuid={}) for {} {} (added={}, removed={}, pos={}, chunk=({}, {}), sectionVisibility={}, chunkStatus={})",
                aabb, glue.getUUID(), kind.name(), anchorPos, added, glue.isRemoved(),
                glue.position(),
                glue.chunkPosition().x, glue.chunkPosition().z,
                visibilityStr, chunkStatus);
        return true;
    }

    /** Read EntitySection visibility (HIDDEN vs TICKING/TRACKED) via reflection through
     *  {@code ServerLevel.entityManager.sectionStorage.getSection(...)}. addFreshEntity into
     *  a HIDDEN section stores the entity but skips startTracking, so spatial queries never
     *  see it. */
    private static String readSectionVisibility(ServerLevel level,
                                                Entity entity) {
        try {
            if (cachedEntityManagerField == null) {
                cachedEntityManagerField = ServerLevel.class.getDeclaredField("entityManager");
                cachedEntityManagerField.setAccessible(true);
            }
            Object entityManager = cachedEntityManagerField.get(level);
            if (cachedSectionStorageField == null) {
                cachedSectionStorageField = entityManager.getClass().getDeclaredField("sectionStorage");
                cachedSectionStorageField.setAccessible(true);
            }
            Object sectionStorage = cachedSectionStorageField.get(entityManager);
            long sectionKey = SectionPos.asLong(entity.blockPosition());
            if (cachedGetSectionMethod == null) {
                cachedGetSectionMethod = sectionStorage.getClass().getDeclaredMethod("getSection", long.class);
                cachedGetSectionMethod.setAccessible(true);
            }
            Object section = cachedGetSectionMethod.invoke(sectionStorage, sectionKey);
            if (section == null) return "(no section)";
            if (cachedSectionGetStatusMethod == null) {
                cachedSectionGetStatusMethod = section.getClass().getDeclaredMethod("getStatus");
                cachedSectionGetStatusMethod.setAccessible(true);
            }
            return String.valueOf(cachedSectionGetStatusMethod.invoke(section));
        } catch (ReflectiveOperationException e) {
            return "(reflect-failed: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static void insertCoalIntoEngine(ServerLevel level, BlockPos enginePos) {
        BlockEntity be = level.getBlockEntity(enginePos);
        if (!(be instanceof PortableEngineBlockEntity engine)) {
            MCPirates.LOGGER.warn(
                    "expected PortableEngineBlockEntity at {} but found {}",
                    enginePos, be == null ? "null" : be.getClass().getSimpleName());
            return;
        }
        ItemStack coal = new ItemStack(Items.COAL, 64);
        int inserted = engine.inventory.insertGeneral(
                ItemInfoWrapper.generateFromStack(coal), 64, /*simulate=*/false);
        engine.notifyUpdate();
        MCPirates.LOGGER.info("fuelled engine at {} with {} coal", enginePos, inserted);
    }

    /** Reflectively call {@link CannonMountBlockEntity#assemble()}. Returns the position
     *  if assembly succeeded, otherwise null. */
    private static BlockPos triggerCannonAssembly(Level level, BlockPos expectedMountPos) {
        if (!(level.getBlockEntity(expectedMountPos) instanceof CannonMountBlockEntity mount)) {
            BlockState s = level.getBlockState(expectedMountPos);
            BlockEntity be = level.getBlockEntity(expectedMountPos);
            MCPirates.LOGGER.error(
                    "cannon mount not at expected {} (state={} BE={})",
                    expectedMountPos, s.getBlock(),
                    be == null ? "null" : be.getClass().getSimpleName());
            return null;
        }
        try {
            if (cachedCannonAssembleMethod == null) {
                cachedCannonAssembleMethod =
                        CannonMountBlockEntity.class.getDeclaredMethod("assemble");
                cachedCannonAssembleMethod.setAccessible(true);
            }
            cachedCannonAssembleMethod.invoke(mount);
            MCPirates.LOGGER.info("cannon assembled at SubLevel {}", expectedMountPos);
            return expectedMountPos;
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            MCPirates.LOGGER.error(
                    "CannonMountBlockEntity.assemble() failed at {}: {}",
                    expectedMountPos, cause.toString());
            return null;
        }
    }
}
