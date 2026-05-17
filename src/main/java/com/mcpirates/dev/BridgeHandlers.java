package com.mcpirates.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

/** Server-thread handlers for the Claude bridge. Pure data extraction — no mutations. */
public final class BridgeHandlers {

    private BridgeHandlers() {}

    public static JsonObject dispatch(String method, JsonObject params, MinecraftServer server) {
        return switch (method) {
            case "ping" -> ping();
            case "airship_brain_state" -> airshipBrainState(params);
            case "sublevel_inspect" -> sublevelInspect(params, server);
            default -> throw new IllegalArgumentException("unknown method: " + method);
        };
    }

    private static JsonObject ping() {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("server", "mcpirates-bridge");
        return r;
    }

    /**
     * Args: optional {@code lever} = [x,y,z] to filter to one ship.
     * Returns: {ships: [ {state, ticksInState, airpadAnchor, kind, subLevelId, pos, vel,
     * balloonCapacity, plateauRows, lastGoal, lastHeadingErrDeg, throttle, lift counts...} ]}.
     */
    private static JsonObject airshipBrainState(JsonObject params) {
        BlockPos filter = params.has("lever") ? readBlockPos(params.getAsJsonArray("lever")) : null;
        JsonArray ships = new JsonArray();
        long now = -1L;
        for (Airship a : AirshipBrain.ships()) {
            if (filter != null && !a.airpadAnchor.equals(filter)) continue;
            if (now < 0) now = a.parentLevel.getGameTime();
            ships.add(dumpAirship(a, now));
        }
        JsonObject r = new JsonObject();
        r.add("ships", ships);
        r.addProperty("now", now);
        return r;
    }

    private static JsonObject dumpAirship(Airship a, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("state", a.state.name());
        o.addProperty("ticksInState", now - a.stateEnteredTick);
        o.addProperty("kind", a.kind.name());
        o.addProperty("subLevelId", a.subLevelId.toString());
        o.add("airpadAnchor", writeBlockPos(a.airpadAnchor));
        o.add("shipLocalForward", writeVec(new Vector3d(a.shipLocalForward)));
        o.addProperty("balloonCapacity", a.balloonCapacity);
        o.addProperty("plateauRows", a.plateauTable == null ? 0 : a.plateauTable.size());
        o.addProperty("plateauTableCapacity", a.plateauTableCapacity);
        if (!Double.isNaN(a.lastGoalX)) {
            JsonObject goal = new JsonObject();
            goal.addProperty("x", a.lastGoalX);
            goal.addProperty("z", a.lastGoalZ);
            o.add("lastGoal", goal);
        }
        o.addProperty("lastHeadingErrDeg", a.lastHeadingErrDeg);
        o.addProperty("steadyTicks", a.steadyTicks);
        if (!Double.isNaN(a.liftoffStartY)) o.addProperty("liftoffStartY", a.liftoffStartY);
        o.addProperty("lastFireTick", a.lastFireTick);
        o.addProperty("lastTargetSeenTick", a.lastTargetSeenTick);
        o.addProperty("combatCursor", a.combatCursor);
        o.addProperty("cannonMounts", a.slCannonMounts.size());
        o.addProperty("throttleLevers", a.slThrottleLevers.size());
        o.addProperty("burners", a.slBurnerPositions.size());
        o.addProperty("anchoredEntities", a.anchoredEntities.size());

        // Live pose + velocity. Re-acquire SubLevel by UUID — see AirshipBrain.tickShip note.
        SubLevelContainer container = SubLevelContainer.getContainer(a.parentLevel);
        SubLevel live = container == null ? null : container.getSubLevel(a.subLevelId);
        if (live != null) {
            Vector3d pos = live.logicalPose().position();
            Quaterniond ori = live.logicalPose().orientation();
            o.add("pos", writeVec(pos));
            o.add("orientation", writeQuat(ori));
            if (live instanceof ServerSubLevel ssl) {
                RigidBodyHandle handle = RigidBodyHandle.of(ssl);
                if (handle != null) {
                    // Sable returns SI units (m/s, rad/s) — labeled explicitly here
                    // so consumers don't assume per-tick. See
                    // memory/feedback_sable_velocity_units.md.
                    o.add("linearVelocity_mPerSec", writeVec(handle.getLinearVelocity(new Vector3d())));
                    o.add("angularVelocity_radPerSec", writeVec(handle.getAngularVelocity(new Vector3d())));
                }
                if (ssl.getMassTracker() != null && !ssl.getMassTracker().isInvalid()) {
                    o.addProperty("mass", ssl.getMassTracker().getMass());
                }
            }
        } else {
            o.addProperty("subLevelLive", false);
        }
        return o;
    }

    /**
     * Args: {@code uuid} string, optional {@code dim} resource location (default overworld).
     * Returns: pose, velocity, mass, block count, entity count.
     */
    private static JsonObject sublevelInspect(JsonObject params, MinecraftServer server) {
        if (!params.has("uuid")) throw new IllegalArgumentException("missing uuid");
        UUID uuid = UUID.fromString(params.get("uuid").getAsString());
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            SubLevel sl = container.getSubLevel(uuid);
            if (sl == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid.toString());
            o.addProperty("dim", level.dimension().location().toString());
            o.add("pos", writeVec(sl.logicalPose().position()));
            o.add("orientation", writeQuat(sl.logicalPose().orientation()));
            if (sl instanceof ServerSubLevel ssl) {
                RigidBodyHandle handle = RigidBodyHandle.of(ssl);
                if (handle != null) {
                    // Sable returns SI units (m/s, rad/s) — labeled explicitly here
                    // so consumers don't assume per-tick. See
                    // memory/feedback_sable_velocity_units.md.
                    o.add("linearVelocity_mPerSec", writeVec(handle.getLinearVelocity(new Vector3d())));
                    o.add("angularVelocity_radPerSec", writeVec(handle.getAngularVelocity(new Vector3d())));
                }
                if (ssl.getMassTracker() != null && !ssl.getMassTracker().isInvalid()) {
                    o.addProperty("mass", ssl.getMassTracker().getMass());
                }
            }
            if (sl.getLevel() instanceof ServerLevel sLevel) {
                long count = 0;
                for (var ignored : sLevel.getEntities().getAll()) count++;
                o.addProperty("entityCount", count);
            }
            return o;
        }
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("found", false);
        return o;
    }

    private static BlockPos readBlockPos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static JsonArray writeBlockPos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX()); a.add(p.getY()); a.add(p.getZ());
        return a;
    }

    private static JsonArray writeVec(Vector3d v) {
        JsonArray a = new JsonArray();
        a.add(v.x); a.add(v.y); a.add(v.z);
        return a;
    }

    private static JsonArray writeQuat(Quaterniond q) {
        JsonArray a = new JsonArray();
        a.add(q.x); a.add(q.y); a.add(q.z); a.add(q.w);
        return a;
    }
}
