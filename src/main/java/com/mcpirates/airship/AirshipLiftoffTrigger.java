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
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
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

/** Proximity-driven lift-off + ground-combat dispatch for dormant pirate ships. See
 *  {@code docs/design.md} for the pipeline and {@code docs/decisions.md} for rationale. */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipLiftoffTrigger {

    /** Toggled by {@code /mcpirates lift off}. */
    public static volatile boolean AUTO_LIFTOFF_ENABLED = true;

    public static boolean setAutoLiftoffEnabled(boolean enabled) {
        AUTO_LIFTOFF_ENABLED = enabled;
        return enabled;
    }

    private static final int TICK_INTERVAL = 10;          // ~0.5 s at 20 TPS
    /** 10 chunks ≈ 160 blocks. */
    private static final int PLAYER_CHUNK_RADIUS = 10;
    private static final double TRIGGER_DISTANCE_SQ = (10 * 16) * (10 * 16);

    // TODO: replace the per-tick chunk-BE scan in processNearbyAnchors with a static
    // anchor registry maintained by MCPShipAnchorBlockEntity.onLoad / setRemoved.
    // Current scan is O(loadedChunks×BEs); registry would be O(anchorsNearPlayer).

    private static Method cachedCannonAssembleMethod;
    private static Field cachedEntityManagerField;
    private static Field cachedSectionStorageField;
    private static Method cachedGetSectionMethod;
    private static Method cachedSectionGetStatusMethod;

    /** Concurrent: server tick races /mcpirates lift off. Cleared on air takeover or
     *  captain KILLED/DISCARDED. */
    private static final Map<BlockPos, GroundCombatModule.GroundEngagement> GROUND_ENGAGEMENTS =
            new ConcurrentHashMap<>();

    /** Re-entrancy guard so the proximity scanner can't fire a second assembly mid-yank. */
    private static final Set<BlockPos> ACTIVATING = ConcurrentHashMap.newKeySet();

    public static boolean hasGroundEngagement(BlockPos anchorPos) {
        return GROUND_ENGAGEMENTS.containsKey(anchorPos);
    }

    /** GameTest setup — map is JVM-static, leaks across tests. */
    public static int clearGroundEngagements(ServerLevel level) {
        int removed = 0;
        for (GroundCombatModule.GroundEngagement e : GROUND_ENGAGEMENTS.values()) {
            removed += GroundCombatModule.SHARED.despawn(level, e);
        }
        GROUND_ENGAGEMENTS.clear();
        return removed;
    }

    private AirshipLiftoffTrigger() {}

    /** Idempotent. Transient miss → false (no log); config error → WARN. */
    public static boolean activateAnchor(ServerLevel level, BlockPos anchorPos) {
        return activateAnchor(level, anchorPos, /*dormant=*/false);
    }

    /** {@code dormant=true}: assembles + fuels engines + stamps userDataTag but skips deck
     *  crew and registers as MOORED. {@link #promoteMooredShipsForAirArrival} later flips
     *  it to LIFTOFF as a pure state change. */
    public static boolean activateAnchor(ServerLevel level, BlockPos anchorPos, boolean dormant) {
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
            if (rotation == null) return false; // chunk not primed
            BlockPos leverPos = anchorPos.offset(kind.anchorToLeverDelta().rotate(rotation));
            BlockEntity leverBe = level.getBlockEntity(leverPos);
            if (leverBe == null) return false;
            // Anchor has no collision → assembly BFS skips it → world-side BE survives.
            // Without this guard the proximity scanner re-enters on a ghost lever.
            for (Airship existing : AirshipBrain.ships()) {
                if (existing.parentLevel == level && existing.airpadAnchor.equals(leverPos)) {
                    return false;
                }
            }
            // Defeated ship is the player's prize — don't respawn the captain.
            if (DefeatedAirships.get(level).containsExact(leverPos)) {
                return false;
            }
            if (!spawnHoneyGlue(level, leverPos, rotation, kind)) return false;
            // Air takeover drops surviving ground defenders; dormant path keeps them.
            if (!dormant) {
                GroundCombatModule.GroundEngagement engagement = GROUND_ENGAGEMENTS.remove(anchorPos);
                if (engagement != null) {
                    int removed = GroundCombatModule.SHARED.despawn(level, engagement);
                    MCPirates.LOGGER.info(
                            "ground combat: liftoff at anchor {} cleared {} surviving defender(s)",
                            anchorPos, removed);
                }
            }
            activateShip(level, leverPos, kind, rotation, dormant);
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

    /** Clear on KILLED/DISCARDED only — UNLOADED_TO_CHUNK returns the captain later. */
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
        // On-SubLevel = air, on-foot = ground. Sable.HELPER.getContaining keys off the
        // SubLevel's static plot pos, not its flying world pos, so we point-test the
        // player's world coords against each SubLevel's world-frame bounding box.
        SubLevel containing = findSubLevelByWorldBounds(level, player.getX(), player.getY(), player.getZ());
        boolean playerOnAirship = containing != null;
        processNearbyAnchors(level, player.getX(), player.getZ(), playerOnAirship);
    }

    /** Public for GameTest entry. */
    public static void processNearbyAnchors(ServerLevel level, double x, double z,
                                            boolean playerOnAirship) {
        // Promote MOORED ships first — their anchor BEs have moved into SubLevels and
        // wouldn't be found by the chunk scan; order also prevents double-activation.
        if (playerOnAirship) {
            promoteMooredShipsForAirArrival(level, x, z);
        }
        ChunkPos centre = new ChunkPos(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
        for (int dx = -PLAYER_CHUNK_RADIUS; dx <= PLAYER_CHUNK_RADIUS; dx++) {
            for (int dz = -PLAYER_CHUNK_RADIUS; dz <= PLAYER_CHUNK_RADIUS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);
                if (chunk == null) {
                    continue;
                }
                // Snapshot: assembly mutates the BE map mid-iteration.
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

    /** MOORED→LIFTOFF: late crew spawn + drop ground defenders. */
    private static void promoteMooredShipsForAirArrival(ServerLevel level, double x, double z) {
        for (Airship a : AirshipBrain.ships()) {
            if (a.parentLevel != level) continue;
            if (a.state != AirshipBrain.State.MOORED) continue;
            BlockPos airpad = a.airpadAnchor;
            double pdx = airpad.getX() + 0.5 - x;
            double pdz = airpad.getZ() + 0.5 - z;
            if (pdx * pdx + pdz * pdz > TRIGGER_DISTANCE_SQ) continue;
            // Defeated ship is the player's prize — leave MOORED.
            if (DefeatedAirships.get(level).containsExact(a.airpadAnchor)) continue;
            promoteMooredToLiftoff(level, a);
        }
    }

    private static void promoteMooredToLiftoff(ServerLevel level, Airship a) {
        AirshipKind kind = a.kind;
        // Re-detect from chunks fails — anchor BE moved into the SubLevel; read the
        // stamp the dormant assembly left on userDataTag.
        Rotation rotation;
        BlockPos slPrimaryAnchor;
        if (a.subLevel instanceof ServerSubLevel ssl
                && ssl.getUserDataTag() != null
                && ssl.getUserDataTag().contains("mcpirates")) {
            CompoundTag mcp = ssl.getUserDataTag().getCompound("mcpirates");
            rotation = Rotation.values()[mcp.getInt("rotation")];
            slPrimaryAnchor = BlockPos.of(mcp.getLong("slPrimaryAnchor"));
        } else {
            MCPirates.LOGGER.warn(
                    "promoteMooredToLiftoff: SubLevel {} ({}) missing mcpirates stamp — cannot spawn deck crew",
                    a.subLevel.getUniqueId(), kind.name());
            return;
        }
        GroundCombatModule.GroundEngagement engagement = GROUND_ENGAGEMENTS.remove(a.airpadAnchor);
        if (engagement != null) {
            int removed = GroundCombatModule.SHARED.despawn(level, engagement);
            MCPirates.LOGGER.info(
                    "ground combat: MOORED→LIFTOFF promotion at anchor {} cleared {} surviving defender(s)",
                    a.airpadAnchor, removed);
        }
        BlockPos offset = slPrimaryAnchor.subtract(a.airpadAnchor);
        CaptainSpawner.CrewSpawnResult crew = CaptainSpawner.spawn(
                a.subLevel, a.airpadAnchor, offset, rotation, kind, a.slCannonMounts);
        a.installCrew(crew.anchors(), crew.cannoneerByMount());
        a.state = AirshipBrain.State.LIFTOFF;
        a.stateEnteredTick = level.getGameTime();
        // Clear MOORED stamp so rehydrate restores in-flight.
        if (a.subLevel instanceof ServerSubLevel sslAfter && sslAfter.getUserDataTag() != null) {
            CompoundTag userTag = sslAfter.getUserDataTag();
            CompoundTag mcp = userTag.getCompound("mcpirates");
            mcp.putBoolean("moored", false);
            userTag.put("mcpirates", mcp);
            sslAfter.setUserDataTag(userTag);
        }
        MCPirates.LOGGER.info(
                "ship {} ({}): MOORED → LIFTOFF after air arrival, deck crew={} cannoneers={}",
                a.subLevel.getUniqueId(), kind.name(),
                crew.anchors().size(), crew.cannoneerByMount().size());
    }

    /** Idempotent. Spawns defenders if the kind opts in and the ship is unbeaten. */
    private static void maybeSpawnGroundCombat(ServerLevel level, BlockPos anchorPos) {
        if (GROUND_ENGAGEMENTS.containsKey(anchorPos)) return;
        BlockEntity be = level.getBlockEntity(anchorPos);
        if (!(be instanceof MCPShipAnchorBlockEntity anchorBe)) return;
        AirshipKind kind = AirshipKinds.byName(anchorBe.getKindName());
        if (kind == null) return;
        if (kind.groundCombat().isEmpty()) return;
        Rotation rotation = detectRotationFromAnchor(level, anchorPos, kind);
        if (rotation == null) return;
        BlockPos leverPos = anchorPos.offset(kind.anchorToLeverDelta().rotate(rotation));
        // GROUND_ENGAGEMENTS is in-memory; DefeatedAirships persists across restarts.
        if (DefeatedAirships.get(level).containsExact(leverPos)) return;

        // Adopt captain that survived a restart while we lost the in-memory engagement.
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

        // Assemble dormant first so defenders surround a real Sable contraption. Bail
        // on assembly failure — otherwise we'd leave crew around a non-liftable hull.
        if (!activateAnchor(level, anchorPos, /*dormant=*/true)) {
            MCPirates.LOGGER.info(
                    "ground combat: dormant assembly deferred at anchor {} — retry next pass",
                    anchorPos);
            return;
        }

        GroundCombatModule module = kind.groundCombat().get();
        GroundCombatModule.GroundEngagement engagement = module.spawn(level, leverPos, rotation, kind);
        GROUND_ENGAGEMENTS.put(anchorPos, engagement);
    }

    /** Try each 90° rotation; match = anchor→lever delta hits the primary-anchor BE.
     *  Null = chunk not primed; caller should retry. */
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

    /** Full assembly; {@code dormant=true} skips deck-crew spawn (registers as MOORED). */
    private static void activateShip(ServerLevel level, BlockPos pos, AirshipKind kind,
                                     Rotation rotation, boolean dormant) {
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

        MCPirates.LOGGER.info("trigger ({}) at {}: {} throttle lever(s), waiting on brain",
                kind.name(), pos, throttleLeverPositions.size());

        BlockPos assemblySeed = pos.relative(connected.getOpposite());
        AssemblyResult result = AirshipAssembler.assemble(level, assemblySeed);
        if (result == null) {
            MCPirates.LOGGER.warn("ship assembly failed; aborting startup at {}", pos);
            return;
        }
        SubLevel subLevel = result.subLevel();
        BlockPos offset = result.offset();

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

        List<BlockPos> slThrottleLevers = new ArrayList<>(throttleLeverPositions.size());
        for (BlockPos w : throttleLeverPositions) slThrottleLevers.add(w.offset(offset));
        BlockPos slLeftClutch = leftClutchPos.offset(offset);
        BlockPos slRightClutch = rightClutchPos.offset(offset);

        // Plot scan, not pair-by-adjacency — galleons wire levers far from burners.
        var plotBox = subLevel.getPlot().getBoundingBox();
        List<BlockPos> slBurnerPositions = HotAirBurners.findAllInBox(
                subLevel.getLevel(),
                plotBox.minX(), plotBox.minY(), plotBox.minZ(),
                plotBox.maxX(), plotBox.maxY(), plotBox.maxZ());
        if (slBurnerPositions.isEmpty()) {
            MCPirates.LOGGER.warn("({}) no Hot Air Burners found in SubLevel plot; lift control disabled",
                    kind.name());
        }

        // userDataTag is persisted by Sable — AirshipRehydrator reads it on restart.
        // moored=true tells rehydrate it's a dormant ship, not a derelict.
        BlockPos slPrimaryAnchorPos = pos.offset(offset);
        if (subLevel instanceof ServerSubLevel ssl) {
            CompoundTag userTag = ssl.getUserDataTag();
            if (userTag == null) userTag = new CompoundTag();
            CompoundTag mcp = new CompoundTag();
            mcp.putString("kind", kind.name());
            mcp.putLong("airpad", pos.asLong());
            mcp.putInt("rotation", rotation.ordinal());
            mcp.putLong("slPrimaryAnchor", slPrimaryAnchorPos.asLong());
            mcp.putBoolean("moored", dormant);
            userTag.put("mcpirates", mcp);
            ssl.setUserDataTag(userTag);
        }

        Direction shipForwardDir = rotation.rotate(kind.nbtForward());
        Vector3d shipLocalForward = new Vector3d(
                shipForwardDir.getStepX(), shipForwardDir.getStepY(), shipForwardDir.getStepZ());

        CaptainSpawner.CrewSpawnResult crew = dormant
                ? CaptainSpawner.CrewSpawnResult.empty()
                : CaptainSpawner.spawn(subLevel, pos, offset, rotation, kind, slCannonMounts);

        Airship airship = new Airship(level, subLevel, pos, kind,
                slThrottleLevers, slBurnerPositions,
                slLeftClutch, slRightClutch,
                slCannonMounts, shipLocalForward,
                crew.anchors(), crew.cannoneerByMount());

        // Rehydrator's SubLevelObserver saw this allocate too; tryRehydrate skips
        // because the UUID is already registered.
        AirshipBrain.register(airship, slPrimaryAnchorPos, rotation,
                dormant ? AirshipBrain.State.MOORED : AirshipBrain.State.LIFTOFF);
    }

    /** False if the section is HIDDEN (addFreshEntity skips startTracking → spatial
     *  queries miss the glue). Caller retries. */
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

    /** Reflective read of EntitySection status. */
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

    /** SubLevel whose world-frame bbox contains (x, y, z), or null.
     *  Unlike {@code Sable.HELPER.getContaining} this works for riders of a moving
     *  contraption (HELPER keys off the static plot chunk). O(N) — fine for our N. */
    private static SubLevel findSubLevelByWorldBounds(ServerLevel level, double x, double y, double z) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        for (SubLevel sl : container.getAllSubLevels()) {
            var b = sl.boundingBox();
            if (x >= b.minX() && x < b.maxX()
                    && y >= b.minY() && y < b.maxY()
                    && z >= b.minZ() && z < b.maxZ()) {
                return sl;
            }
        }
        return null;
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

    /** Reflectively invokes CBC's package-private {@code assemble()}. */
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
