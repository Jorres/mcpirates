package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.pirates.CaptainSpawner;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity;
import dev.simulated_team.simulated.multiloader.inventory.ItemInfoWrapper;
import dev.simulated_team.simulated.util.SimAssemblyHelper.AssemblyResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Discovers dormant pirate airships and triggers their lift-off when an enemy player on a
 * SubLevel (their own airship) gets within {@link #PLAYER_CHUNK_RADIUS} chunks. The trigger
 * fires once per airship — subsequent passes skip via {@link AnalogLeverBlockEntity#getState()}
 * &gt;= {@link #TARGET_STATE}.
 *
 * <p>On trigger we run the canonical startup:
 *
 * <ol>
 *     <li>Insert 64 coal into the {@code simulated:portable_engine} below the lever.</li>
 *     <li>Set the {@code create:analog_lever}'s state to 10 (burner-on).</li>
 *     <li>Assemble the ship into a Sable SubLevel via {@link AirshipAssembler}.</li>
 *     <li>Reflectively call {@code CannonMountBlockEntity.assemble()} on the mount.</li>
 *     <li>Hand over to {@link AirshipBrain} which runs the state machine + movement.</li>
 * </ol>
 *
 * <p><strong>Structure rotation.</strong> The airship piece is placed by jigsaw, which can
 * rotate it 0/90/180/270° around vertical. We detect the rotation by reading the analog
 * lever's world {@code FACING} blockstate (NBT defines it as EAST), then rotate every
 * structure-local delta by the same amount before adding to the lever's world position.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipLiftoffTrigger {

    private static final int TARGET_STATE = 10;
    private static final int TICK_INTERVAL = 10;          // ~0.5s at 20 TPS
    /** Trigger when an enemy-on-airship is within this chunk radius of a dormant pirate
     *  lever. 10 chunks ≈ 160 blocks. */
    private static final int PLAYER_CHUNK_RADIUS = 10;
    private static final double TRIGGER_DISTANCE_SQ = (10 * 16) * (10 * 16);
    private static final int ENVELOPE_SCAN_RADIUS = 4;    // confirms it's a real airship

    /** NBT facing of the analog lever in the airship template. Used to determine rotation. */
    private static final Direction NBT_LEVER_FACING = Direction.EAST;

    // All offsets below are deltas from the trigger lever, NOT absolute NBT positions.
    // The airship NBT is allowed to grow/shift independently of this file (more air
    // padding, vertical shifts, etc.); only the relative geometry of components-around-
    // the-lever must stay constant for these constants to keep working.
    //
    //   engine        → 1 block below the lever
    //   cannon mount  → 3 below, 4 ahead (toward the cannon-facing side)
    //   left  clutch  → 2 left, 1 below, 2 back
    //   right clutch  → 2 right, 1 below, 2 back
    private static final BlockPos ENGINE_DELTA       = new BlockPos(0, -1, 0);
    private static final BlockPos CANNON_MOUNT_DELTA = new BlockPos(0, -3, -4);
    private static final BlockPos LEFT_CLUTCH_DELTA  = new BlockPos(-2, -1, 2);
    private static final BlockPos RIGHT_CLUTCH_DELTA = new BlockPos(2, -1, 2);

    // Honey-glue body bounds, *inclusive* block coords expressed as deltas from the
    // lever. Covers the actual ship hull (no pad, no surrounding air padding). Update
    // these only when the ship body itself changes shape relative to the lever.
    private static final BlockPos GLUE_MIN_DELTA = new BlockPos(-2, -3, -5);
    private static final BlockPos GLUE_MAX_DELTA = new BlockPos(2, 4, 4);

    private static Field cachedStateField;
    private static Method cachedCannonAssembleMethod;
    private static Field cachedEntityManagerField;
    private static Field cachedSectionStorageField;
    private static Method cachedGetSectionMethod;
    private static Method cachedSectionGetStatusMethod;

    /**
     * Reads the EntitySection visibility for the section the entity sits in, via
     * reflection through {@code ServerLevel.entityManager.sectionStorage.getSection(...)}.
     * If the section is HIDDEN, addFreshEntity stores the entity in section storage but
     * never calls {@code startTracking}, so subsequent {@code getEntity(uuid)} returns
     * null and {@code getEntitiesOfClass} skips the section.
     */
    private static String readSectionVisibility(ServerLevel level,
                                                net.minecraft.world.entity.Entity entity) {
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
            long sectionKey = net.minecraft.core.SectionPos.asLong(entity.blockPosition());
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

    private AirshipLiftoffTrigger() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % TICK_INTERVAL != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                // Trigger only fires for players who are themselves aboard a SubLevel
                // (i.e., flying their own airship or any contraption). On-foot players
                // don't wake up pirate ships — that's the gameplay design.
                // TEMP: filter disabled for creative-mode testing. Re-enable before ship.
                // if (Sable.HELPER.getContaining(player) == null) {
                //     continue;
                // }
                checkAroundPlayer(level, player);
            }
        }
    }

    private static void checkAroundPlayer(ServerLevel level, ServerPlayer player) {
        ChunkPos centre = player.chunkPosition();
        for (int dx = -PLAYER_CHUNK_RADIUS; dx <= PLAYER_CHUNK_RADIUS; dx++) {
            for (int dz = -PLAYER_CHUNK_RADIUS; dz <= PLAYER_CHUNK_RADIUS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);
                if (chunk == null) {
                    continue;
                }
                // Snapshot: activateLever() yanks airship BEs out of the chunk via the
                // assembly path, which would corrupt a live iterator (NPE in fastutil).
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (!(be instanceof AnalogLeverBlockEntity lever)) {
                        continue;
                    }
                    if (lever.getState() >= TARGET_STATE) {
                        continue; // already activated, skip
                    }
                    BlockPos pos = lever.getBlockPos();
                    double pdx = pos.getX() + 0.5 - player.getX();
                    double pdz = pos.getZ() + 0.5 - player.getZ();
                    double distSq = pdx * pdx + pdz * pdz; // horizontal only
                    if (distSq > TRIGGER_DISTANCE_SQ) {
                        continue;
                    }
                    if (!isAirshipLever(level, pos)) {
                        continue;
                    }
                    // spawnHoneyGlue returns false if the airship's chunk section is
                    // HIDDEN (chunk not yet at ENTITY_TICKING — entity would be added
                    // but invisible to spatial queries). We retry next pass; the player
                    // will eventually get close enough that the chunk gets promoted.
                    Rotation rotation = detectRotation(level.getBlockState(pos));
                    if (!spawnHoneyGlue(level, pos, rotation)) {
                        continue;
                    }
                    activateLever(level, pos, lever);
                }
            }
        }
    }

    private static boolean isAirshipLever(ServerLevel level, BlockPos centre) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -ENVELOPE_SCAN_RADIUS; dx <= ENVELOPE_SCAN_RADIUS; dx++) {
            for (int dy = -ENVELOPE_SCAN_RADIUS; dy <= ENVELOPE_SCAN_RADIUS; dy++) {
                for (int dz = -ENVELOPE_SCAN_RADIUS; dz <= ENVELOPE_SCAN_RADIUS; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if ("aeronautics".equals(id.getNamespace()) && id.getPath().contains("envelope")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Full lift-off sequence: detect rotation → fuel engine → flip analog lever → ship
     * assemble → cannon assemble → register with brain.
     */
    private static void activateLever(ServerLevel level, BlockPos pos, AnalogLeverBlockEntity lever) {
        BlockState bs = level.getBlockState(pos);
        Direction connected = leverConnectedDirection(bs);

        // Determine the rotation jigsaw applied to this airship piece by comparing the
        // analog lever's world FACING to its NBT FACING. All structure-local deltas get
        // rotated by the same amount.
        Rotation rotation = detectRotation(bs);
        BlockPos enginePos      = pos.offset(ENGINE_DELTA.rotate(rotation));
        BlockPos cannonMountPos = pos.offset(CANNON_MOUNT_DELTA.rotate(rotation));
        BlockPos leftClutchPos  = pos.offset(LEFT_CLUTCH_DELTA.rotate(rotation));
        BlockPos rightClutchPos = pos.offset(RIGHT_CLUTCH_DELTA.rotate(rotation));

        MCPirates.LOGGER.info(
                "pirate trigger at lever {} (rotation={}): engine={} mount={} left={} right={}",
                pos, rotation, enginePos, cannonMountPos, leftClutchPos, rightClutchPos);

        // Step 1: fuel
        insertCoalIntoEngine(level, enginePos);

        // Step 2: flip lever
        if (!setAnalogLeverState(lever, pos)) {
            return;
        }
        Block leverBlock = bs.getBlock();
        level.updateNeighborsAt(pos, leverBlock);
        level.updateNeighborsAt(pos.relative(connected.getOpposite()), leverBlock);
        level.sendBlockUpdated(pos, bs, bs, Block.UPDATE_ALL);
        MCPirates.LOGGER.info("activated airship lever at {} (state -> {})", pos, TARGET_STATE);

        // Step 3: assemble — honey glue was spawned earlier in the same pass.
        BlockPos assemblySeed = pos.relative(connected.getOpposite());
        AssemblyResult result = AirshipAssembler.assemble(level, assemblySeed);
        if (result == null) {
            MCPirates.LOGGER.warn("ship assembly failed; aborting startup at {}", pos);
            return;
        }
        SubLevel subLevel = result.subLevel();
        BlockPos offset = result.offset();

        // Step 4: locate + assemble cannon.
        BlockPos slCannonMount = triggerCannonAssembly(
                subLevel.getLevel(), cannonMountPos.offset(offset));
        if (slCannonMount == null) {
            return;
        }

        // Step 5: hand off to brain. Forward in SubLevel-local space follows from the
        // structure rotation applied to NBT cannon facing. Cannon mount sits at NBT z=1
        // with the body at z=1..9, so the cannon points toward NORTH (-Z) — the ship's
        // "forward" is the cannon-pointing direction.
        Direction shipForwardDir = rotation.rotate(Direction.NORTH);
        Vector3d shipLocalForward = new Vector3d(
                shipForwardDir.getStepX(), shipForwardDir.getStepY(), shipForwardDir.getStepZ());
        // Step 6: spawn the pirate captain + crewmate into the SubLevel — see
        // CaptainSpawner for why this happens after assembly. The captain's death
        // drops the bounty seal that the sheriff villager trades for emeralds.
        // Spawn BEFORE registering with the brain so the brain has the anchor list
        // at hand and can re-assert plot positions every tick (Sable's plot anchor
        // is in-memory only; it doesn't survive chunk unload/reload).
        java.util.List<com.mcpirates.pirates.CaptainSpawner.AnchoredEntity> anchors =
                CaptainSpawner.spawn(subLevel, pos, offset, rotation);

        AirshipBrain.register(
                level,
                subLevel,
                pos,
                pos.offset(offset),
                leftClutchPos.offset(offset),
                rightClutchPos.offset(offset),
                slCannonMount,
                shipLocalForward,
                anchors);
    }

    /** Compute the rotation applied at jigsaw placement by comparing world vs NBT facing. */
    private static Rotation detectRotation(BlockState lever) {
        Direction worldFacing;
        try {
            worldFacing = lever.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
        } catch (IllegalArgumentException e) {
            return Rotation.NONE;
        }
        if (worldFacing == NBT_LEVER_FACING) return Rotation.NONE;
        if (worldFacing == NBT_LEVER_FACING.getClockWise()) return Rotation.CLOCKWISE_90;
        if (worldFacing == NBT_LEVER_FACING.getOpposite()) return Rotation.CLOCKWISE_180;
        if (worldFacing == NBT_LEVER_FACING.getCounterClockWise()) return Rotation.COUNTERCLOCKWISE_90;
        return Rotation.NONE;
    }

    private static boolean setAnalogLeverState(AnalogLeverBlockEntity lever, BlockPos pos) {
        try {
            if (cachedStateField == null) {
                cachedStateField = AnalogLeverBlockEntity.class.getDeclaredField("state");
                cachedStateField.setAccessible(true);
            }
            cachedStateField.setInt(lever, TARGET_STATE);
        } catch (ReflectiveOperationException e) {
            MCPirates.LOGGER.error("failed to mutate AnalogLeverBlockEntity.state at {}", pos, e);
            return false;
        }
        lever.setChanged();
        return true;
    }

    /**
     * Spawn a {@link HoneyGlueEntity} covering the airship body. NBT-baked entities are
     * unreliable through jigsaw worldgen — entities placed via {@code addEntitiesToWorld}
     * during structure-step generation don't consistently end up in the server's spatial
     * index by the time the trigger fires. Spawning at trigger time is deterministic.
     */
    /**
     * Spawn a {@link HoneyGlueEntity} covering the airship body, generously inflated so
     * that 90°/180° rotation never clips the front of the ship by an off-by-one block.
     * Falls back to mutating the assembler's honeyGlueCache reflectively because
     * {@code level.getEntitiesOfClass} doesn't see entities added in the same server
     * tick — the entity manager inserts them into the section storage immediately, but
     * the spatial-index iterator filters by section visibility, and freshly spawned
     * entities aren't yet visible to AABB queries until the next tick.
     */
    /** @return true if the glue was spawned into a TICKING/TRACKED section (i.e., is
     *  queryable by spatial lookups). False if the entity section was HIDDEN — the
     *  caller should retry next pass once the chunk has been promoted to ENTITY_TICKING. */
    private static boolean spawnHoneyGlue(ServerLevel level, BlockPos leverPos, Rotation rotation) {
        BlockPos a = leverPos.offset(GLUE_MIN_DELTA.rotate(rotation));
        BlockPos b = leverPos.offset(GLUE_MAX_DELTA.rotate(rotation));
        // Build the AABB by min/maxing rotated corners and adding +1 to the max
        // so the half-open AABB covers the inclusive block coords (block at world
        // pos N has center N.5, AABB.contains needs maxN > N.5 → maxN ≥ N+1).
        double minX = Math.min(a.getX(), b.getX());
        double minY = Math.min(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxX = Math.max(a.getX(), b.getX()) + 1.0;
        double maxY = Math.max(a.getY(), b.getY()) + 1.0;
        double maxZ = Math.max(a.getZ(), b.getZ()) + 1.0;
        AABB aabb = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        HoneyGlueEntity glue = new HoneyGlueEntity(level, aabb);
        boolean added = level.addFreshEntity(glue);
        // Read section visibility. If the chunk hasn't reached ENTITY_TICKING yet,
        // chunkVisibility[chunkKey] is unset (defaults to HIDDEN) so the freshly
        // created section is HIDDEN. addFreshEntity reports success but the entity
        // is invisible to spatial queries and getEntity(uuid) — BFS would never
        // find the glue, so we abort and retry once the chunk gets promoted.
        String visibilityStr = readSectionVisibility(level, glue);
        net.minecraft.server.level.FullChunkStatus chunkStatus =
                level.getChunkSource().getChunkNow(
                        glue.chunkPosition().x, glue.chunkPosition().z) instanceof
                        net.minecraft.world.level.chunk.LevelChunk lc
                        ? lc.getFullStatus() : null;
        if ("HIDDEN".equals(visibilityStr)) {
            glue.discard();
            MCPirates.LOGGER.info(
                    "deferred honey glue spawn for lever {} (chunk=({}, {}) at status {}, section HIDDEN) — will retry next pass",
                    leverPos, glue.chunkPosition().x, glue.chunkPosition().z, chunkStatus);
            return false;
        }
        MCPirates.LOGGER.info(
                "spawned runtime honey glue {} (uuid={}) for lever {} (added={}, removed={}, pos={}, chunk=({}, {}), sectionVisibility={}, chunkStatus={})",
                aabb, glue.getUUID(), leverPos, added, glue.isRemoved(),
                glue.position(),
                glue.chunkPosition().x, glue.chunkPosition().z,
                visibilityStr, chunkStatus);
        return true;
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

    /**
     * Reflectively call {@link CannonMountBlockEntity#assemble()} at the SubLevel position
     * derived from the lever's world pos + cannon-mount delta rotated by the detected
     * jigsaw rotation. Returns the position if the mount was found and assembly succeeded,
     * otherwise null.
     */
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

    private static Direction leverConnectedDirection(BlockState state) {
        AttachFace face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
        return switch (face) {
            case CEILING -> Direction.DOWN;
            case FLOOR -> Direction.UP;
            case WALL -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
        };
    }
}
