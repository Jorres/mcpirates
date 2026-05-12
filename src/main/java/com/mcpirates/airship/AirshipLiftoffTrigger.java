package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.AirshipKinds;
import com.mcpirates.airship.kind.ThrottleLevers;
import com.mcpirates.pirates.CaptainSpawner;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity;
import dev.simulated_team.simulated.multiloader.inventory.ItemInfoWrapper;
import dev.simulated_team.simulated.util.SimAssemblyHelper.AssemblyResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers dormant pirate airships and triggers their lift-off when a player gets within
 * {@link #PLAYER_CHUNK_RADIUS} chunks. The trigger fires once per ship — subsequent passes
 * skip via the kind's {@code readAnchorState() >= activatedAt()} check on the primary lever.
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
 *     <li>Set every throttle-equivalent lever to {@link AirshipKind#activatedAt()}.</li>
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
                // if (Sable.HELPER.getContaining(player) == null) continue;
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
                // Snapshot the BE map — activateShip yanks block entities out of the chunk
                // via the assembly path, which would corrupt a live iterator (NPE in fastutil).
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (!(be instanceof MCPShipAnchorBlockEntity anchorBe)) continue;
                    BlockPos anchorPos = be.getBlockPos();
                    // Cheap distance gate.
                    double pdx = anchorPos.getX() + 0.5 - player.getX();
                    double pdz = anchorPos.getZ() + 0.5 - player.getZ();
                    if (pdx * pdx + pdz * pdz > TRIGGER_DISTANCE_SQ) continue;

                    // Direct kind lookup — no heuristic matching.
                    AirshipKind kind = AirshipKinds.byName(anchorBe.getKindName());
                    if (kind == null) {
                        MCPirates.LOGGER.warn("ship anchor at {} has unknown kind '{}'",
                                anchorPos, anchorBe.getKindName());
                        continue;
                    }

                    // Find the primary lever by trying all four 90° rotations of the kind's
                    // anchor-to-lever delta. Exactly one rotation places the lever's BE on a
                    // BE class the kind recognises; that rotation is the ship's world rotation.
                    Rotation rotation = detectRotationFromAnchor(level, anchorPos, kind);
                    if (rotation == null) continue;  // chunk not loaded enough yet, retry

                    BlockPos leverPos = anchorPos.offset(kind.anchorToLeverDelta().rotate(rotation));
                    // Skip if the ship has already been activated (anchor state >= 10).
                    BlockEntity leverBe = level.getBlockEntity(leverPos);
                    if (leverBe == null || kind.readAnchorState(leverBe) >= kind.activatedAt()) {
                        continue;
                    }
                    // Confirm the envelope is loaded — same retry semantics as before:
                    // chunk not yet at ENTITY_TICKING → next pass.
                    if (!spawnHoneyGlue(level, leverPos, rotation, kind)) {
                        continue;
                    }
                    activateShip(level, leverPos, kind, rotation);
                }
            }
        }
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

        // Step 2: flip every throttle lever to TARGET_STATE
        int target = kind.activatedAt();
        for (BlockPos lvPos : throttleLeverPositions) {
            if (!ThrottleLevers.setState(level, lvPos, target)) {
                MCPirates.LOGGER.warn("({}) couldn't set throttle lever at {} to {}",
                        kind.name(), lvPos, target);
                return;
            }
        }
        MCPirates.LOGGER.info("activated {} throttle lever(s) at {} (state -> {})",
                throttleLeverPositions.size(), pos, target);

        // Step 3: assemble — honey glue was spawned earlier in this same pass.
        BlockPos assemblySeed = pos.relative(connected.getOpposite());
        AssemblyResult result = AirshipAssembler.assemble(level, assemblySeed);
        if (result == null) {
            MCPirates.LOGGER.warn("ship assembly failed; aborting startup at {}", pos);
            return;
        }
        dev.ryanhcode.sable.sublevel.SubLevel subLevel = result.subLevel();
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

        // Step 6: forward vector
        Direction shipForwardDir = rotation.rotate(kind.nbtForward());
        Vector3d shipLocalForward = new Vector3d(
                shipForwardDir.getStepX(), shipForwardDir.getStepY(), shipForwardDir.getStepZ());

        // Step 7: spawn captain + crewmate BEFORE registering with the brain so the brain
        // has the anchor list at hand and can re-assert plot positions every tick.
        List<CaptainSpawner.AnchoredEntity> anchors =
                CaptainSpawner.spawn(subLevel, pos, offset, rotation);

        // Step 8: hand off
        AirshipBrain.register(
                level, subLevel, pos, kind,
                slThrottleLevers, slLeftClutch, slRightClutch, slCannonMounts,
                shipLocalForward, anchors);
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
        net.minecraft.server.level.FullChunkStatus chunkStatus =
                level.getChunkSource().getChunkNow(
                        glue.chunkPosition().x, glue.chunkPosition().z) instanceof
                        net.minecraft.world.level.chunk.LevelChunk lc
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
