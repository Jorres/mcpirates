package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.airship.ships.AirshipKinds;
import com.mcpirates.airship.hardware.ThrottleLevers;
import com.mcpirates.pirates.CaptainSpawner;
import com.mcpirates.pirates.DefeatedAirships;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity;
import dev.simulated_team.simulated.multiloader.inventory.ItemInfoWrapper;
import dev.simulated_team.simulated.util.SimAssemblyHelper.AssemblyResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Proximity-driven lift-off for parked pirate ships. See {@code docs/design.md} for
 *  the pipeline and {@code docs/decisions.md} for rationale. */
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

    /** Re-entrancy guard so the proximity scanner can't fire a second assembly mid-yank. */
    private static final Set<BlockPos> ACTIVATING = ConcurrentHashMap.newKeySet();

    private AirshipLiftoffTrigger() {}

    /** Idempotent. Transient miss → false (no log); config error → WARN. */
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
            Rotation rotation = kind.detectRotation(level, anchorPos).orElse(null);
            if (rotation == null) return false; // chunk not primed
            BlockPos leverPos = kind.leverFromAnchor(rotation, anchorPos);
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
            kind.preassemble(level, leverPos, rotation);
            if (!spawnHoneyGlue(level, leverPos, rotation, kind)) return false;
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
                processNearbyAnchors(level, player.getX(), player.getZ());
            }
        }
    }

    /** Public for GameTest entry. */
    public static void processNearbyAnchors(ServerLevel level, double x, double z) {
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
                    activateAnchor(level, anchorPos);
                }
            }
        }
    }

    private static void activateShip(ServerLevel level, BlockPos pos, AirshipKind kind,
                                     Rotation rotation) {
        BlockState anchorState = level.getBlockState(pos);
        Direction connected = ThrottleLevers.leverConnectedDirection(anchorState);

        Layout worldLayout = kind.layoutAt(rotation, pos);

        MCPirates.LOGGER.info(
                "pirate trigger ({}) at {} (rotation={}): engines={} cannons={} left={} right={} throttles={}",
                kind.name(), pos, rotation, worldLayout.engines(), worldLayout.cannonMounts(),
                worldLayout.leftClutch(), worldLayout.rightClutch(), worldLayout.throttleLevers());

        // Step 1: fuel every engine
        for (BlockPos enginePos : worldLayout.engines()) {
            insertCoalIntoEngine(level, enginePos);
        }

        MCPirates.LOGGER.info("trigger ({}) at {}: {} throttle lever(s), waiting on brain",
                kind.name(), pos, worldLayout.throttleLevers().size());

        BlockPos assemblySeed = pos.relative(connected.getOpposite());
        AssemblyResult result = AirshipAssembler.assemble(level, assemblySeed);
        if (result == null) {
            MCPirates.LOGGER.warn("ship assembly failed; aborting startup at {}", pos);
            return;
        }
        SubLevel subLevel = result.subLevel();
        BlockPos offset = result.offset();
        BlockPos slPrimaryAnchorPos = pos.offset(offset);

        // Sim's assembler clears the world block at each contraption position but leaves
        // some BlockEntities orphaned in the chunk NBT (notably create:analog_lever).
        // On reload, vanilla `BlockEntity.validateBlockState` throws because block=AIR but
        // BE=analog_lever. Sweep every position from the world-frame layout and force-
        // remove any BE the assembler missed.
        scrubLeftoverBEs(level, kind.name(), pos, worldLayout);

        // Post-assembly: re-resolve cannon mounts in SL frame and trigger CBC assembly.
        Layout slLayout = kind.layoutAt(rotation, slPrimaryAnchorPos);
        List<BlockPos> slCannonMounts = new ArrayList<>(slLayout.cannonMounts().size());
        for (BlockPos slMount : slLayout.cannonMounts()) {
            BlockPos assembled = triggerCannonAssembly(subLevel.getLevel(), slMount);
            if (assembled != null) slCannonMounts.add(assembled);
        }
        if (slCannonMounts.size() != slLayout.cannonMounts().size()) {
            MCPirates.LOGGER.warn("({}) cannon assembly partial: {}/{} succeeded",
                    kind.name(), slCannonMounts.size(), slLayout.cannonMounts().size());
        }

        CaptainSpawner.CrewSpawnResult crew =
                CaptainSpawner.spawn(subLevel, pos, offset, rotation, kind, slCannonMounts);

        Airship airship = new Airship(level, subLevel, pos, kind, rotation,
                slPrimaryAnchorPos, slCannonMounts,
                crew.anchors(), crew.cannoneerByMount());
        Airship.rebuildActuators(airship);

        // Rehydrator's SubLevelObserver saw this allocate too; tryRehydrate skips
        // because the UUID is already registered.
        AirshipBrain.register(airship, AirshipBrain.State.LIFTOFF);
    }

    /** Remove orphaned BlockEntities at world positions that should have been moved into
     *  the SubLevel by Sim's assembler. The block at each pos is already AIR; the BE
     *  entry in the chunk NBT is the leak. */
    private static void scrubLeftoverBEs(ServerLevel level, String kindName,
                                         BlockPos leverPos, Layout worldLayout) {
        List<BlockPos> sweep = new ArrayList<>();
        sweep.add(leverPos);
        sweep.addAll(worldLayout.throttleLevers());
        sweep.add(worldLayout.leftClutch());
        sweep.add(worldLayout.rightClutch());
        sweep.addAll(worldLayout.engines());
        sweep.addAll(worldLayout.cannonMounts());
        int removed = 0;
        for (BlockPos p : sweep) {
            if (p == null) continue;
            if (level.getBlockState(p).isAir() && level.getBlockEntity(p) != null) {
                level.removeBlockEntity(p);
                removed++;
            }
        }
        if (removed > 0) {
            MCPirates.LOGGER.info(
                    "({}) scrubbed {} orphaned BE(s) left in world post-assembly",
                    kindName, removed);
        }
    }

    /** False if the section is HIDDEN (addFreshEntity skips startTracking → spatial
     *  queries miss the glue). Caller retries. */
    private static boolean spawnHoneyGlue(ServerLevel level, BlockPos anchorPos,
                                          Rotation rotation, AirshipKind kind) {
        Layout layout = kind.layoutAt(rotation, anchorPos);
        BlockPos a = layout.glueMin();
        BlockPos b = layout.glueMax();
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
            MCPirates.LOGGER.debug(
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
                    "CannonMountBlockEntity.assemble() failed at {}",
                    expectedMountPos, cause);
            return null;
        }
    }
}
