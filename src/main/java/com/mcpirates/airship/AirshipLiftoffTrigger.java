package com.mcpirates.airship;

import com.mcpirates.MCPirates;
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

    // Structure-local offsets from the trigger lever (analog_lever) in NBT-space:
    //   analog_lever  → (7, 6, 5)
    //   engine        → (7, 5, 5)   delta (0, -1, 0)
    //   cannon mount  → (7, 3, 1)   delta (0, -3, -4)
    //   left  clutch  → (5, 5, 7)   delta (-2, -1, 2)
    //   right clutch  → (9, 5, 7)   delta (2, -1, 2)
    private static final BlockPos ENGINE_DELTA       = new BlockPos(0, -1, 0);
    private static final BlockPos CANNON_MOUNT_DELTA = new BlockPos(0, -3, -4);
    private static final BlockPos LEFT_CLUTCH_DELTA  = new BlockPos(-2, -1, 2);
    private static final BlockPos RIGHT_CLUTCH_DELTA = new BlockPos(2, -1, 2);

    private static Field cachedStateField;
    private static Method cachedCannonAssembleMethod;

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

        // Step 3: spawn the runtime honey-glue (this tick), then defer the rest of the
        // pipeline by one tick so the entity is actually queryable.
        //
        // Why deferred? `level.addFreshEntity` returns true synchronously, but the entity
        // isn't yet visible to spatial queries via `level.getEntitiesOfClass(...)`. Section
        // visibility / entity-tick wiring catches up on the next tick boundary. If we run
        // SimAssemblyHelper synchronously here, its `addInitialHoneyGlue` query returns
        // empty, the BFS finds no glue connections, and the contraption ends up containing
        // only the burner (the seed) and the lever (pulled along by brittle-attachment).
        // Confirmed empirically: the diagnostic log "post-spawn glue query: 0 entities"
        // proved the entity is invisible to spatial queries on the same tick it's spawned.
        //
        // `MinecraftServer.execute(Runnable)` queues a TickTask that runs at the start of
        // the next tick, in `runAllTasks()` before `tickChildren`. By then the spawn has
        // been through the entity-section update cycle and the spatial query works.
        spawnAirshipHoneyGlue(level, pos);

        BlockPos assemblySeed = pos.relative(connected.getOpposite());
        // Capture-by-final for the lambda. Rotation handling happened above; the deferred
        // call only runs the world-mutating assembly + brain registration.
        final BlockPos finalCannonMountPos = cannonMountPos;
        final BlockPos finalLeftClutchPos = leftClutchPos;
        final BlockPos finalRightClutchPos = rightClutchPos;
        final Rotation finalRotation = rotation;
        level.getServer().execute(() -> finishAssembly(
                level, pos, assemblySeed, finalCannonMountPos,
                finalLeftClutchPos, finalRightClutchPos, finalRotation));
    }

    /** Deferred-by-one-tick continuation of activateLever — see comment there for why. */
    private static void finishAssembly(
            ServerLevel level, BlockPos leverPos, BlockPos seed,
            BlockPos cannonMountPos, BlockPos leftClutchPos, BlockPos rightClutchPos,
            Rotation rotation) {
        AssemblyResult result = AirshipAssembler.assemble(level, seed);
        if (result == null) {
            MCPirates.LOGGER.warn("ship assembly failed; aborting startup at {}", leverPos);
            return;
        }
        SubLevel subLevel = result.subLevel();
        BlockPos offset = result.offset();

        scanAssembledSubLevel(subLevel, offset);

        BlockPos slCannonMount = triggerCannonAssembly(
                subLevel.getLevel(), cannonMountPos.offset(offset));
        if (slCannonMount == null) {
            slCannonMount = cannonMountPos.offset(offset);
        }

        Direction shipForwardDir = rotation.rotate(Direction.SOUTH);
        Vector3d shipLocalForward = new Vector3d(
                shipForwardDir.getStepX(), shipForwardDir.getStepY(), shipForwardDir.getStepZ());
        AirshipBrain.register(
                level,
                subLevel,
                leverPos,
                leverPos.offset(offset),
                leftClutchPos.offset(offset),
                rightClutchPos.offset(offset),
                slCannonMount,
                shipLocalForward);
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
     * Walk every loaded chunk in the SubLevel's plot and log the non-air blocks. Used to
     * diagnose partial-assembly bugs where the BFS picks up only a few blocks instead of
     * the full ship body. The output answers the question "what actually got moved?".
     */
    private static void scanAssembledSubLevel(SubLevel subLevel, BlockPos offset) {
        Level slLevel = subLevel.getLevel();
        if (slLevel == null) return;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        java.util.List<String> samples = new java.util.ArrayList<>();
        int total = 0;
        for (var chunk : subLevel.getPlot().getLoadedChunks()) {
            var bounds = chunk.getBoundingBox();
            if (bounds == null) continue;
            int chunkMinX = chunk.getPos().getMinBlockX();
            int chunkMinZ = chunk.getPos().getMinBlockZ();
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        BlockPos p = new BlockPos(chunkMinX + x, y, chunkMinZ + z);
                        BlockState s = slLevel.getBlockState(p);
                        if (s.isAir()) continue;
                        total++;
                        String name = s.getBlock().toString();
                        counts.merge(name, 1, Integer::sum);
                        if (samples.size() < 6) {
                            samples.add(p + " " + name);
                        }
                    }
                }
            }
        }
        MCPirates.LOGGER.info(
                "SubLevel post-assembly scan: {} non-air blocks, types={}, samples={}",
                total, counts, samples);
    }

    private static void spawnAirshipHoneyGlue(ServerLevel level, BlockPos leverPos) {
        AABB bb = new AABB(
                leverPos.getX() - 7.0, leverPos.getY() - 3.0, leverPos.getZ() - 7.0,
                leverPos.getX() + 8.0, leverPos.getY() + 8.0, leverPos.getZ() + 8.0);
        HoneyGlueEntity glue = new HoneyGlueEntity(level, bb);
        boolean added = level.addFreshEntity(glue);
        MCPirates.LOGGER.info(
                "spawned runtime honey-glue (added={}) covering {} actualBounds={} pos={}",
                added, bb, glue.getBoundingBox(), glue.position());
        // Diagnose: does Aeronautics' assembly query path actually find this entity?
        // SimAssemblyContraption.addInitialHoneyGlue uses
        // level.getEntitiesOfClass(HoneyGlueEntity.class, span(seed, seed).inflate(range)).
        // Mimic that here so we know if the entity is queryable + contains the seed.
        java.util.List<HoneyGlueEntity> nearby = level.getEntitiesOfClass(
                HoneyGlueEntity.class, bb.inflate(2));
        MCPirates.LOGGER.info("post-spawn glue query: {} entities found near seed", nearby.size());
        BlockPos seed = leverPos.above(); // ceiling lever's support = burner = assembly seed
        for (HoneyGlueEntity e : nearby) {
            MCPirates.LOGGER.info(
                    "  - bounds={}, contains(burner)={}, contains(lever)={}",
                    e.getBoundingBox(), e.contains(seed), e.contains(leverPos));
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

    /**
     * Look up the cannon mount BE at the precisely-computed SubLevel position. The expected
     * position is derived from the analog lever's world position + the structure-local
     * cannon-mount delta, rotated by the jigsaw rotation we detected from the lever's
     * facing. With the math right, this should always match exactly. We retain a tiny ±1
     * fallback as a sanity net for off-by-one issues — a hit at non-zero delta logs at WARN
     * so we'll notice if the rotation math regresses.
     */
    private static BlockPos locateCannonMount(Level level, BlockPos expected) {
        BlockEntity direct = level.getBlockEntity(expected);
        if (direct instanceof CannonMountBlockEntity) {
            return expected;
        }
        BlockState directState = level.getBlockState(expected);
        MCPirates.LOGGER.warn(
                "cannon mount NOT at expected {} (state={} BE={}); scanning ±1 — "
                + "if this hits, rotation math is off by one",
                expected, directState.getBlock(),
                direct == null ? "null" : direct.getClass().getSimpleName());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    cursor.set(expected.getX() + dx, expected.getY() + dy, expected.getZ() + dz);
                    if (level.getBlockEntity(cursor) instanceof CannonMountBlockEntity) {
                        BlockPos found = cursor.immutable();
                        MCPirates.LOGGER.warn(
                                "cannon mount found at {} (delta from expected: {},{},{}) — "
                                + "fix this in the rotation math",
                                found, dx, dy, dz);
                        return found;
                    }
                }
            }
        }
        MCPirates.LOGGER.error(
                "cannon mount not found within ±1 of {} — assembly probably broke "
                + "(check honey-glue AABB / structure rotation handling)", expected);
        return null;
    }

    private static BlockPos triggerCannonAssembly(Level level, BlockPos expectedMountPos) {
        BlockPos actualPos = locateCannonMount(level, expectedMountPos);
        if (actualPos == null) {
            return null;
        }
        if (!(level.getBlockEntity(actualPos) instanceof CannonMountBlockEntity mount)) {
            return null;
        }
        try {
            if (cachedCannonAssembleMethod == null) {
                cachedCannonAssembleMethod =
                        CannonMountBlockEntity.class.getDeclaredMethod("assemble");
                cachedCannonAssembleMethod.setAccessible(true);
            }
            cachedCannonAssembleMethod.invoke(mount);
            MCPirates.LOGGER.info("cannon assembled at SubLevel {}", actualPos);
            return actualPos;
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            MCPirates.LOGGER.error(
                    "CannonMountBlockEntity.assemble() failed at {}: {}",
                    actualPos, cause.toString());
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
