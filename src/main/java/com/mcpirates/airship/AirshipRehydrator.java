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
 * Re-register persisted SubLevels with the brain on restart / chunk reload. Source of
 * truth is {@code userDataTag.mcpirates} written by {@code activateShip} (the anchor
 * block doesn't survive assembly, so a BE stamp wouldn't work). Crew pillagers carry
 * {@code role}/{@code cannon_mount} stamps so we can rebuild PirateRole and the
 * cannon→cannoneer map without re-scanning seats.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipRehydrator {

    private AirshipRehydrator() {}

    /** Queued because {@code onSubLevelAdded} fires before {@code setUserDataTag};
     *  {@link #onServerTick} retries once the tag is in place. */
    private static final java.util.Set<java.util.UUID> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Covers galleon footprint + drift between save and rehydrate. */
    private static final double CREW_SCAN_RADIUS = 64.0;

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

    /** Brain SHIPS list is in-memory; this is how a saved flying ship comes back.
     *  Fresh assemblies register directly via {@link AirshipLiftoffTrigger}; the
     *  duplicate-skip below bails when the trigger beat the observer. */
    private static boolean tryRehydrate(ServerLevel parentLevel, SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel ssl)) return false;
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

        // SL-local positions: same delta formula activateShip used in world coords.
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

        // Sable writes world-coords back to entity.position() every frame, so saved
        // pillager NBT carries ship-world XYZ, not plot coords (~20M). Scan around the
        // pose centre, not the plot bbox.
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

        // MOORED ships come back as MOORED; otherwise HOVER if at airpad, else RETURN
        // (LIFTOFF's exit gates don't fire on a ship already at cruise altitude).
        AirshipBrain.State initialState;
        if (mcp.getBoolean("moored")) {
            initialState = AirshipBrain.State.MOORED;
        } else {
            double dx = shipWorldPos.x - (airpadAnchor.getX() + 0.5);
            double dz = shipWorldPos.z - (airpadAnchor.getZ() + 0.5);
            initialState = (dx * dx + dz * dz) < AirshipStateMachine.HOVER_RADIUS_SQ
                    ? AirshipBrain.State.HOVER
                    : AirshipBrain.State.RETURN;
        }

        Airship airship = new Airship(parentLevel, ssl, airpadAnchor, kind,
                slThrottleLevers, slBurnerPositions,
                slLeftClutch, slRightClutch,
                slCannonMounts, shipLocalForward,
                anchors, cannoneerByMount);
        airship.controls = kind.makeControls(airship, slPrimaryAnchor, rotation);
        AirshipBrain.register(airship, initialState);
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

    /** Inverse of logicalPose; returns absolute plot coords (~20M). */
    private static Vec3 worldToPlot(SubLevel subLevel, Vec3 worldPos) {
        return subLevel.logicalPose().transformPositionInverse(worldPos);
    }
}
