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
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On {@link ServerStartedEvent}, scans every {@link SubLevel} for an mcpirates
 * stamp in its user-data tag and re-registers the resulting {@link Airship}
 * with the brain. Without this, a ship that was flying at shutdown reloads as
 * a persisted SubLevel that nobody is ticking: clutch/throttle writes never
 * happen, crew lose their {@code sable$setPlotPosition} binding (it's a
 * {@code @Unique} mixin field, not in entity NBT), and rapier's accumulated
 * angular velocity spins the body unchecked.
 *
 * <p>State source of truth: {@link ServerSubLevel#getUserDataTag()}'s
 * {@code "mcpirates"} compound, written by {@code activateShip} after a
 * successful assembly. The ship_anchor block doesn't survive assembly (no
 * collision → BFS skips it), so we can't rely on a block-entity stamp.
 *
 * <p>Crew pillagers carry {@code mcpirates.role} + {@code mcpirates.cannon_mount}
 * so we can rebuild {@code PirateRole} and the {@code cannoneerByMount} map
 * without re-running the seat scan.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipRehydrator {

    private AirshipRehydrator() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            rehydrateLevel(level);
        }
    }

    /** Run rehydration against a single level. Public so gametests can drive it
     *  without faking a {@link ServerStartedEvent}. */
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

        List<BlockPos> slBurnerPositions = new ArrayList<>(slThrottleLevers.size());
        for (BlockPos slLever : slThrottleLevers) {
            BlockState ls = ssl.getLevel().getBlockState(slLever);
            BlockPos slBurner = HotAirBurners.findAdjacentBurner(ssl.getLevel(), slLever, ls);
            if (slBurner != null) slBurnerPositions.add(slBurner);
        }

        Direction shipForwardDir = rotation.rotate(kind.nbtForward());
        Vector3d shipLocalForward = new Vector3d(
                shipForwardDir.getStepX(), shipForwardDir.getStepY(), shipForwardDir.getStepZ());

        // Crew: scan SubLevel entities by airpad-anchor stamp.
        var plotBB = ssl.getPlot().getBoundingBox();
        AABB scanBox = new AABB(plotBB.minX(), plotBB.minY(), plotBB.minZ(),
                plotBB.maxX() + 1, plotBB.maxY() + 1, plotBB.maxZ() + 1).inflate(32);
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

        AirshipBrain.register(parentLevel, ssl, airpadAnchor, kind,
                slThrottleLevers, slBurnerPositions, slLeftClutch, slRightClutch, slCannonMounts,
                shipLocalForward, anchors, cannoneerByMount);
        MCPirates.LOGGER.info("rehydrate: re-registered {} (subLevel={}, airpad={}, rotation={}, crew={})",
                kind.name(), ssl.getUniqueId(), airpadAnchor, rotation, anchors.size());
        return true;
    }

    private static PirateRole resolveRole(String stamp, long now) {
        return switch (stamp) {
            case "cannoneer" -> CannoneerRole.INSTANCE;
            default -> new CrossbowmanRole(now);
        };
    }

    private static Vec3 worldToPlot(SubLevel subLevel, Vec3 worldPos) {
        Vector3d shipPos = subLevel.logicalPose().position();
        Vector3d delta = new Vector3d(
                worldPos.x - shipPos.x, worldPos.y - shipPos.y, worldPos.z - shipPos.z);
        Vector3d plot = subLevel.logicalPose().orientation()
                .transformInverse(delta, new Vector3d());
        return new Vec3(plot.x, plot.y, plot.z);
    }
}
