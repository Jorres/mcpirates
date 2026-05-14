package com.mcpirates.airship;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.AirshipKinds;
import com.mcpirates.airship.kind.HotAirBurners;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import com.mcpirates.pirates.roles.CannoneerRole;
import com.mcpirates.pirates.roles.CrossbowmanRole;
import com.mcpirates.pirates.roles.PirateRole;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-registers persisted SubLevels with the brain after restart. Triggered on
 * {@link ServerStartedEvent} — scans every container's already-loaded SubLevels and
 * attaches a {@link SubLevelObserver} so any SubLevel that loads lazily (chunks coming
 * into range) is rehydrated when it arrives. Without this, a ship that was flying at
 * shutdown comes back as a persisted SubLevel that nobody is ticking: clutch/throttle
 * writes never happen, crew lose their {@code sable$setPlotPosition} binding (it's a
 * {@code @Unique} mixin field, not in entity NBT), and rapier's accumulated angular
 * velocity spins the body unchecked.
 *
 * <p>State source of truth: {@link ServerSubLevel#getUserDataTag()}'s
 * {@code "mcpirates"} compound, written by {@code activateShip} after a successful
 * assembly. The ship_anchor block doesn't survive assembly (no collision → BFS skips
 * it), so we can't rely on a block-entity stamp.
 *
 * <p>Crew pillagers carry {@code mcpirates.role} + {@code mcpirates.cannon_mount} so
 * we can rebuild {@code PirateRole} and the {@code cannoneerByMount} map without
 * re-running the seat scan.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipRehydrator {

    private AirshipRehydrator() {}

    /** SubLevels that arrived via {@code onSubLevelAdded} but haven't been rehydrated yet
     *  (their userDataTag wasn't set at observer-fire time — see class doc). Processed on
     *  the next ServerTickEvent.Post by {@link #onServerTick}. */
    private static final java.util.Set<java.util.UUID> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Half-extent of the crew-scan AABB around the ship's world-rendered position.
     *  Covers galleon footprint (~12×15×28) plus margin for inter-tick drift. */
    private static final double CREW_SCAN_RADIUS = 64.0;

    /** Hook ServerStartedEvent to scan any preloaded SubLevels and attach observers for
     *  later arrivals. SubLevels loaded lazily via {@code SubLevelSerializer} fire
     *  {@code onSubLevelAdded} BEFORE {@code setUserDataTag}, so we queue them and let
     *  {@link #onServerTick} retry once the tag is in place. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (SubLevel sl : container.getAllSubLevels()) {
                if (!sl.isRemoved()) tryRehydrate(level, sl);
            }
            container.addObserver(new SubLevelObserver() {
                @Override public void onSubLevelAdded(SubLevel subLevel) {
                    PENDING.add(subLevel.getUniqueId());
                }
                @Override public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
                    PENDING.remove(subLevel.getUniqueId());
                }
            });
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (java.util.UUID uuid : PENDING) {
                SubLevel sl = container.getSubLevel(uuid);
                if (sl == null || sl.isRemoved()) {
                    PENDING.remove(uuid);
                    continue;
                }
                if (!(sl instanceof ServerSubLevel ssl)) {
                    PENDING.remove(uuid);
                    continue;
                }
                CompoundTag userTag = ssl.getUserDataTag();
                if (userTag == null) continue; // still loading — retry next tick
                PENDING.remove(uuid); // either rehydrates or fails terminally, either way done
                tryRehydrate(level, sl);
            }
        }
    }

    /** Public for GameTests. */
    public static int rehydrateLevel(ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;
        int registered = 0;
        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;
            if (tryRehydrate(level, sl)) registered++;
        }
        return registered;
    }

    private static boolean tryRehydrate(ServerLevel parentLevel, SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel ssl)) return false;
        // Skip ships already registered (the trigger may have registered this SubLevel
        // moments before our observer caught it — without this guard every fresh
        // assembly produces a duplicate brain entry).
        for (Airship existing : AirshipBrain.ships()) {
            if (existing.subLevelId.equals(ssl.getUniqueId())) return false;
        }
        CompoundTag userTag = ssl.getUserDataTag();
        if (userTag == null || !userTag.contains("mcpirates")) return false;
        CompoundTag mcp = userTag.getCompound("mcpirates");

        String kindName = mcp.getString("kind");
        AirshipKind kind = AirshipKinds.byName(kindName);
        if (kind == null) {
            MCPirates.LOGGER.warn("rehydrate: unknown kind '{}' on SubLevel {}",
                    kindName, ssl.getUniqueId());
            return false;
        }
        BlockPos airpadAnchor = BlockPos.of(mcp.getLong("airpad"));
        Rotation rotation = Rotation.values()[mcp.getInt("rotation")];
        BlockPos slPrimaryAnchor = BlockPos.of(mcp.getLong("slPrimaryAnchor"));

        // SubLevel-local positions from kind deltas. Rotation hasn't changed since
        // assembly, so the same delta formula that activateShip used in WORLD coords
        // gives correct SL-LOCAL coords here.
        List<BlockPos> slThrottleLevers = new ArrayList<>(kind.throttleLeverDeltas().size());
        for (BlockPos d : kind.throttleLeverDeltas()) {
            slThrottleLevers.add(slPrimaryAnchor.offset(d.rotate(rotation)));
        }
        BlockPos slLeftClutch = slPrimaryAnchor.offset(kind.leftClutchLeverDelta().rotate(rotation));
        BlockPos slRightClutch = slPrimaryAnchor.offset(kind.rightClutchLeverDelta().rotate(rotation));
        List<BlockPos> slCannonMounts = new ArrayList<>(kind.cannonMountDeltas().size());
        for (BlockPos d : kind.cannonMountDeltas()) {
            slCannonMounts.add(slPrimaryAnchor.offset(d.rotate(rotation)));
        }

        var plotBox = ssl.getPlot().getBoundingBox();
        List<BlockPos> slBurnerPositions = HotAirBurners.findAllInBox(
                ssl.getLevel(),
                plotBox.minX(), plotBox.minY(), plotBox.minZ(),
                plotBox.maxX(), plotBox.maxY(), plotBox.maxZ());

        Direction shipForwardDir = rotation.rotate(kind.nbtForward());
        Vector3d shipLocalForward = new Vector3d(
                shipForwardDir.getStepX(), shipForwardDir.getStepY(), shipForwardDir.getStepZ());

        // Crew: scan around the ship's WORLD-rendered position, not the plot box. Sable's
        // Entity tick mixin writes the world-rendered position back to entity.position()
        // every frame, so on save the pillagers' NBT carries world coords (~ship's
        // logical-pose XYZ) rather than plot coords (~20M). A plot-bbox scan would miss
        // them all. CREW_SCAN_RADIUS is generous enough to cover any ship's footprint
        // plus a margin for in-flight drift between save and rehydrate.
        Vector3d shipWorldPos = ssl.logicalPose().position();
        AABB scanBox = new AABB(
                shipWorldPos.x - CREW_SCAN_RADIUS, shipWorldPos.y - CREW_SCAN_RADIUS, shipWorldPos.z - CREW_SCAN_RADIUS,
                shipWorldPos.x + CREW_SCAN_RADIUS, shipWorldPos.y + CREW_SCAN_RADIUS, shipWorldPos.z + CREW_SCAN_RADIUS);
        long airpadLong = airpadAnchor.asLong();
        List<AnchoredEntity> anchors = new ArrayList<>();
        Map<BlockPos, UUID> cannoneerByMount = new HashMap<>();
        for (Pillager p : ssl.getLevel().getEntitiesOfClass(Pillager.class, scanBox)) {
            var data = p.getPersistentData();
            if (!data.contains(MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY)) continue;
            if (data.getLong(MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY) != airpadLong) continue;
            String roleStamp = data.getString(MCPDataKeys.CREW_ROLE_NBT_KEY);
            PirateRole role = resolveRole(roleStamp, parentLevel.getGameTime());
            Vec3 plotPos = worldToPlot(ssl, p.position());
            anchors.add(new AnchoredEntity(p.getUUID(), plotPos, role));
            if ("cannoneer".equals(roleStamp) && data.contains(MCPDataKeys.CREW_CANNON_MOUNT_NBT_KEY)) {
                BlockPos mount = BlockPos.of(data.getLong(MCPDataKeys.CREW_CANNON_MOUNT_NBT_KEY));
                cannoneerByMount.put(mount, p.getUUID());
            }
        }

        // Rehydrated ships are already in-flight. Start in HOVER if at the airpad, RETURN
        // otherwise — LIFTOFF is the "freshly climbing from the airpad" phase only and its
        // stabilized-exit gate doesn't fire for a ship already at cruise altitude.
        double dx = shipWorldPos.x - (airpadAnchor.getX() + 0.5);
        double dz = shipWorldPos.z - (airpadAnchor.getZ() + 0.5);
        AirshipBrain.State initialState = (dx * dx + dz * dz) < AirshipStateMachine.HOVER_RADIUS_SQ
                ? AirshipBrain.State.HOVER
                : AirshipBrain.State.RETURN;

        AirshipBrain.register(parentLevel, ssl, airpadAnchor, kind,
                slThrottleLevers, slBurnerPositions, slLeftClutch, slRightClutch, slCannonMounts,
                shipLocalForward, anchors, cannoneerByMount, initialState);
        MCPirates.LOGGER.info("rehydrate: re-registered {} (subLevel={}, airpad={}, rotation={}, crew={}, state={})",
                kind.name(), ssl.getUniqueId(), airpadAnchor, rotation, anchors.size(), initialState);
        return true;
    }

    private static PirateRole resolveRole(String stamp, long now) {
        return switch (stamp) {
            case "cannoneer" -> CannoneerRole.INSTANCE;
            default -> new CrossbowmanRole(now);
        };
    }

    /** World position → absolute plot coordinate (high ~20M range). The previous
     *  implementation returned only the pose-center-relative offset; Sable's tick
     *  mixin then couldn't resolve a SubLevel for that "plot pos" via getContaining
     *  and wiped the anchor back to null, leaving the pillager detached. */
    private static Vec3 worldToPlot(SubLevel subLevel, Vec3 worldPos) {
        return subLevel.logicalPose().transformPositionInverse(worldPos);
    }
}
