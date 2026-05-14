package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.AngleMath;
import com.mcpirates.airship.kind.ClutchLevers;
import com.mcpirates.airship.kind.HotAirBurners;
import com.mcpirates.airship.kind.LiftMath;
import com.mcpirates.airship.kind.LiftMath.LiftSetting;
import com.mcpirates.airship.kind.PlateauTable;
import com.mcpirates.airship.kind.ThrottleLevers;
import dev.eriksonn.aeronautics.index.AeroLiftingGasTypes;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import com.mcpirates.pirates.PirateBrain;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-server-tick brain for assembled pirate airships. Drives the state machine
 * (LIFTOFF → PURSUE → RETURN → HOVER), tank-steer via clutches, and altitude via a
 * precomputed {@link PlateauTable}. Ship-specific concerns live behind {@link AirshipKind}.
 *
 * <p>Clutch lever {@code powered=true} <strong>disengages</strong> — we write
 * {@code !engaged}. Tank-steer: both engaged → straight; left only → pivot RIGHT;
 * right only → pivot LEFT.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipBrain {

    /** Per-tick |Δy| below which the ship counts as "not climbing"; tuned to ignore
     *  Sable's physics jitter while still catching real ascent. */
    private static final double LIFTOFF_STEADY_DELTA = 0.05;
    private static final double ARRIVAL_RADIUS_SQ = 12 * 12;
    private static final double HEADING_TOLERANCE_DEG = 25.0;
    private static final int AIM_INTERVAL = 5;
    private static final int DECISION_INTERVAL = 10;

    /** Larger = wider orbits, smoother turns. Smaller = tighter, more aggressive. */
    private static final double ORBIT_LOOK_AHEAD = 18.0;
    /** Fraction of look-ahead borrowed from tangent to pull back to orbit radius. */
    private static final double ORBIT_RADIAL_GAIN = 0.8;

    private static final boolean DEBUG_OVERLAY = true;
    private static final int OVERLAY_ACTIONBAR_INTERVAL = 10;
    private static final double OVERLAY_RENDER_RADIUS = 200.0;

    private static final boolean CANNON_AIM_ENABLED = true;

    /** Runtime toggle (see {@link #setFireEnabled} / {@code /mcpirates fire on|off}).
     *  Off by default so test sessions aren't drowned in cannonballs. */
    public static volatile boolean CANNON_FIRE_ENABLED = false;

    public static boolean setFireEnabled(boolean enabled) {
        CANNON_FIRE_ENABLED = enabled;
        return enabled;
    }

    private static final List<Airship> SHIPS = new CopyOnWriteArrayList<>();

    /** Test seam: when non-null, replaces {@link #findEnemyPlayerOnAirship}'s player
     *  scan with this function. Volatile so tests can swap it without racing the tick. */
    public static volatile java.util.function.Function<Airship, LivingEntity> targetOverride = null;

    /** Immutable snapshot; returned {@link Airship}s are live and mutable. */
    public static List<Airship> ships() { return List.copyOf(SHIPS); }

    /** Drop every ship in {@code level}. GameTests share JVM static state across tests,
     *  so test N+1 would otherwise see ships left by test N. */
    public static void unregisterAll(ServerLevel level) {
        SHIPS.removeIf(a -> a.parentLevel == level);
    }

    private AirshipBrain() {}

    public enum State { LIFTOFF, PURSUE, RETURN, HOVER }

    public static void register(
            ServerLevel parentLevel,
            SubLevel subLevel,
            BlockPos airpadAnchor,
            AirshipKind kind,
            List<BlockPos> slThrottleLevers,
            List<BlockPos> slBurnerPositions,
            BlockPos slLeftClutchLever,
            BlockPos slRightClutchLever,
            List<BlockPos> slCannonMounts,
            Vector3d shipLocalForward,
            List<AnchoredEntity> anchoredEntities,
            java.util.Map<BlockPos, java.util.UUID> cannoneerByMount,
            State initialState) {
        Airship a = new Airship(parentLevel, subLevel, airpadAnchor, kind,
                slThrottleLevers, slBurnerPositions,
                slLeftClutchLever, slRightClutchLever,
                slCannonMounts, shipLocalForward, anchoredEntities, cannoneerByMount);
        a.state = initialState;
        // Without this, the default 0 makes (now - stateEnteredTick) resolve to millions
        // and the LIFTOFF_MIN_TICKS gate silently no-ops.
        a.stateEnteredTick = parentLevel.getGameTime();
        SHIPS.add(a);
        MCPirates.LOGGER.info(
                "registered pirate airship: kind={} subLevel={} state={} anchor={} mounts={} throttles={} burners={} clutches=({},{}) fwd=({},{},{}) anchoredEntities={} cannoneers={}",
                kind.name(), subLevel.getUniqueId(), initialState, airpadAnchor,
                slCannonMounts, slThrottleLevers, slBurnerPositions,
                slLeftClutchLever, slRightClutchLever,
                shipLocalForward.x, shipLocalForward.y, shipLocalForward.z,
                anchoredEntities.size(), cannoneerByMount.size());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        for (Airship a : SHIPS) {
            if (!tickShip(a, now)) {
                SHIPS.remove(a);
            }
        }
    }

    private static boolean tickShip(Airship a, long now) {
        // Re-acquire the live SubLevel by UUID each tick: Sable rehydrates a fresh
        // SubLevel object under the same UUID after chunk unload/reload, and a cached
        // reference becomes a frozen snapshot (logicalPose() stuck forever).
        SubLevelContainer container = SubLevelContainer.getContainer(a.parentLevel);
        if (container == null) {
            return false; // dimension is being torn down
        }
        SubLevel live = container.getSubLevel(a.subLevelId);
        if (live == null) {
            // Holding-chunk: persisted to disk, not yet rehydrated. Stay registered.
            return true;
        }
        if (live != a.subLevel) {
            a.subLevel = live;
        }
        Level subLevelLevel = a.subLevel.getLevel();
        if (subLevelLevel == null) {
            return true;
        }
        // Crew-defeat: when every anchored pirate is dead the ship is leaderless.
        // Cut lift + disengage clutches one final time so the wreck drifts down
        // instead of cruising on fossilised throttle state, then deregister.
        if (!a.isAnyCrewAlive()) {
            shutdownDerelict(a, subLevelLevel);
            MCPirates.LOGGER.info("ship {} ({}): crew defeated, brain deregistering",
                    a.subLevel.getUniqueId(), a.kind.name());
            return false;
        }
        // Sable's plot anchor lives in a @Unique mixin field that isn't serialised to
        // entity NBT, so chunk reload wipes it and the pillager freezes in mid-air.
        // Re-apply lazily (no-op during steady flight).
        reanchorEntities(a);
        Vector3d shipPos = a.subLevel.logicalPose().position();

        LivingEntity target = findEnemyPlayerOnAirship(a, shipPos);
        if (target != null) {
            a.lastTargetSeenTick = now;
        }

        // Steadiness + initial-Y: state machine needs both to distinguish "topped out"
        // from "hasn't started rising yet" (steady-tick check alone is ambiguous).
        if (a.state == State.LIFTOFF) {
            if (Double.isNaN(a.liftoffStartY)) {
                a.liftoffStartY = shipPos.y;
            }
            if (!Double.isNaN(a.lastSampledY)) {
                if (Math.abs(shipPos.y - a.lastSampledY) < LIFTOFF_STEADY_DELTA) {
                    a.steadyTicks++;
                } else {
                    a.steadyTicks = 0;
                }
            }
            a.lastSampledY = shipPos.y;
        } else {
            a.steadyTicks = 0;
            a.lastSampledY = Double.NaN;
            a.liftoffStartY = Double.NaN;
        }

        Vector3d targetPos = target == null ? null
                : new Vector3d(target.getX(), target.getY(), target.getZ());
        State desired = AirshipStateMachine.decideNextState(
                a.state, a.stateEnteredTick, a.steadyTicks, a.liftoffStartY,
                a.lastTargetSeenTick,
                shipPos, a.airpadAnchor.getX() + 0.5, a.airpadAnchor.getZ() + 0.5,
                targetPos, now,
                a.kind.liftoffMinRise());
        if (desired != a.state) {
            MCPirates.LOGGER.info(
                    "ship {} ({}): state {} → {} (target={}, y={}, distAirpad={})",
                    a.subLevel.getUniqueId(), a.kind.name(), a.state, desired,
                    target == null ? "none" : target.getName().getString(),
                    String.format("%.1f", shipPos.y),
                    String.format("%.1f", Math.sqrt(horizDistSq(shipPos, a.airpadAnchor))));
            a.state = desired;
            a.stateEnteredTick = now;
            // Disengage both on entry. PURSUE: applyMovement re-engages this tick — the
            // off→on edge also refreshes Aeronautics' thrust contribution (props can
            // visually spin while applying zero thrust after an idle chunk reload until
            // the clutch is toggled). HOVER: stay disengaged.
            if (desired == State.PURSUE || desired == State.HOVER) {
                setBothClutchesDisengaged(a);
            }
            applyMovement(a, target, shipPos, now);
            a.lastDecisionTick = now;
        } else if (now - a.lastDecisionTick >= DECISION_INTERVAL) {
            applyMovement(a, target, shipPos, now);
            a.lastDecisionTick = now;
        }

        if (CANNON_AIM_ENABLED && a.state == State.PURSUE && target != null) {
            if (now % AIM_INTERVAL == 0) {
                a.kind.combat().aim(a, target);
            }
            int interval = a.kind.combat().fireIntervalTicks();
            if (CANNON_FIRE_ENABLED && now - a.lastFireTick >= interval) {
                if (a.kind.combat().fire(a, target)) {
                    a.lastFireTick = now;
                }
            }
        }

        // After combat so pirates see settled flight state.
        PirateBrain.tickShip(a, target, now);

        if (DEBUG_OVERLAY && now % OVERLAY_ACTIONBAR_INTERVAL == 0) {
            writeDebugActionbar(a, target, shipPos);
        }
        return true;
    }

    private static void reanchorEntities(Airship a) {
        if (a.anchoredEntities.isEmpty()) return;
        for (AnchoredEntity ae : a.anchoredEntities) {
            net.minecraft.world.entity.Entity entity = a.parentLevel.getEntity(ae.uuid());
            if (entity == null || entity.isRemoved()) continue;
            EntityStickExtension stuck = (EntityStickExtension) entity;
            if (stuck.sable$getPlotPosition() == null) {
                stuck.sable$setPlotPosition(ae.plotPos());
                MCPirates.LOGGER.info(
                        "re-anchored {} ({}) to plotPos={} after reload",
                        ae.role().name(), ae.uuid(), ae.plotPos());
            }
        }
    }

    // ───────────────────────────── Movement ─────────────────────────────

    private static void applyMovement(Airship a, LivingEntity target, Vector3d shipPos, long now) {
        Level subLevelLevel = a.subLevel.getLevel();

        // Horizontal goal, NaN = stationary (LIFTOFF, HOVER).
        double goalX = Double.NaN, goalZ = Double.NaN;
        switch (a.state) {
            case PURSUE -> {
                if (target != null) {
                    // Orbit, not point-at: cannon aim is heading-independent, and a
                    // tangent path avoids the hover-and-oscillate failure of straight pursuit.
                    double ftx = shipPos.x - target.getX();
                    double ftz = shipPos.z - target.getZ();
                    double r = Math.sqrt(ftx * ftx + ftz * ftz);
                    if (r < 0.01) {
                        ftx = 1.0; ftz = 0.0; r = 1.0;
                    }
                    double tanX = -ftz / r;
                    double tanZ = ftx / r;
                    double radInX = -ftx / r;
                    double radInZ = -ftz / r;
                    double orbitRadius = a.kind.orbitRadius();
                    double radialErr = (r - orbitRadius) / orbitRadius;
                    double radialBlend = Math.max(-1.0, Math.min(1.0, radialErr)) * ORBIT_RADIAL_GAIN;
                    double dirX = tanX + radInX * radialBlend;
                    double dirZ = tanZ + radInZ * radialBlend;
                    double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
                    if (dirLen > 0.001) {
                        dirX /= dirLen; dirZ /= dirLen;
                    }
                    goalX = shipPos.x + dirX * ORBIT_LOOK_AHEAD;
                    goalZ = shipPos.z + dirZ * ORBIT_LOOK_AHEAD;
                }
            }
            case RETURN -> {
                goalX = a.airpadAnchor.getX() + 0.5;
                goalZ = a.airpadAnchor.getZ() + 0.5;
            }
            case LIFTOFF, HOVER -> { /* stay put */ }
        }

        boolean leftEngaged = false, rightEngaged = false;
        double headingErrDeg = 0;
        if (!Double.isNaN(goalX)) {
            double dx = goalX - shipPos.x;
            double dz = goalZ - shipPos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > ARRIVAL_RADIUS_SQ) {
                double targetYaw = Math.atan2(-dx, dz);
                double currentYaw = currentYawRadians(a);
                double err = AngleMath.normalizeRadians(targetYaw - currentYaw);
                headingErrDeg = Math.toDegrees(err);
                double absErrDeg = Math.abs(headingErrDeg);

                if (absErrDeg < HEADING_TOLERANCE_DEG) {
                    leftEngaged = true;
                    rightEngaged = true;
                } else if (err > 0) {
                    leftEngaged = true;
                    rightEngaged = false;
                } else {
                    leftEngaged = false;
                    rightEngaged = true;
                }
            }
        }

        // Clutch is INVERTED: powered=true disengages.
        ClutchLevers.setPowered(subLevelLevel, a.slLeftClutchLever, !leftEngaged);
        ClutchLevers.setPowered(subLevelLevel, a.slRightClutchLever, !rightEngaged);

        int balloonCap = -1;
        for (BlockPos burner : a.slBurnerPositions) {
            int c = HotAirBurners.queryBalloonCapacity(subLevelLevel, burner);
            if (c > 0) { balloonCap = c; break; }
        }
        a.balloonCapacity = balloonCap;
        int burnerCount = Math.max(1, a.slBurnerPositions.size());
        int maxGroundAhead = maxGroundAhead(a, shipPos);
        LiftSetting lift = chooseLiftSetting(a, a.state, shipPos, target, maxGroundAhead);
        if (lift != null) {
            for (BlockPos lever : a.slThrottleLevers) {
                ThrottleLevers.setState(subLevelLevel, lever, lift.lever());
            }
            for (BlockPos burner : a.slBurnerPositions) {
                HotAirBurners.setVolume(subLevelLevel, burner, lift.volume());
            }
        }
        long bucket = System.currentTimeMillis() / 2000;
        if (bucket != a.lastThrottleLogBucket) {
            a.lastThrottleLogBucket = bucket;
            double cruiseY = a.airpadAnchor.getY() + a.kind.cruiseRise();
            double targetY = (a.state == State.PURSUE && target != null)
                    ? Math.max(target.getEyeY() + PURSUE_ALT_OFFSET, maxGroundAhead + MIN_ALT_ABOVE_GROUND + 2.0)
                    : cruiseY;
            int plateauRows = a.plateauTable == null ? 0 : a.plateauTable.size();
            String liftStr = lift == null ? "—" : (lift.lever() + "/" + lift.volume()
                    + " T=" + String.format("%.1f", burnerCount * (double) lift.volume() * lift.lever() / 15.0));
            MCPirates.LOGGER.info(
                    "ship {} ({}) throttle: state={} lift={} plateauRows={} balloonCap={} shipY={} maxGroundAhead={} targetY={} dy={}",
                    a.subLevel.getUniqueId(), a.kind.name(), a.state, liftStr,
                    plateauRows, a.balloonCapacity,
                    String.format("%.1f", shipPos.y), maxGroundAhead,
                    String.format("%.1f", targetY),
                    String.format("%.1f", shipPos.y - targetY));
        }

        // Orbit-math intermediates for the overlay; clutch/throttle/burner state lives
        // in the SubLevel block states and is read back from there when needed.
        a.lastGoalX = goalX;
        a.lastGoalZ = goalZ;
        a.lastHeadingErrDeg = headingErrDeg;
    }

    /** PURSUE altitude = player.eye + this many blocks. */
    private static final double PURSUE_ALT_OFFSET = 12.0;
    /** Safety floor: below this height-above-terrain the brain bang-bangs to max lift
     *  so the ship can't be dragged down to where the cannon arc is cut off. */
    private static final double MIN_ALT_ABOVE_GROUND = 30.0;

    private static final int[] GROUND_LOOKAHEAD_SAMPLES = {0, 8, 16, 24, 32};

    /** Max heightmap value across the ship's XZ and points along its current heading.
     *  Lets the controller clamp altitude above the *highest* terrain it's about to
     *  cross, not just whatever's directly below. */
    private static int maxGroundAhead(Airship a, Vector3d shipPos) {
        Quaterniond orient = a.subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(a.shipLocalForward), new Vector3d());
        double fwdLen = Math.sqrt(worldFwd.x * worldFwd.x + worldFwd.z * worldFwd.z);
        double fwdX = fwdLen > 0.001 ? worldFwd.x / fwdLen : 0.0;
        double fwdZ = fwdLen > 0.001 ? worldFwd.z / fwdLen : 0.0;
        int maxGround = Integer.MIN_VALUE;
        for (int dist : GROUND_LOOKAHEAD_SAMPLES) {
            int sx = (int) Math.floor(shipPos.x + fwdX * dist);
            int sz = (int) Math.floor(shipPos.z + fwdZ * dist);
            int g = a.parentLevel.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    sx, sz);
            if (g > maxGround) maxGround = g;
        }
        return maxGround;
    }

    /** Picks the plateau row whose equilibrium altitude is closest to the target. LIFTOFF /
     *  HOVER / RETURN aim for the kind's cruise altitude; PURSUE aims for the player.
     *  Returns null while the balloon hasn't attached yet (caller skips the write; the
     *  trigger's activated-lever value keeps the burner ignited so the balloon attaches
     *  on its own). */
    private static LiftSetting chooseLiftSetting(
            Airship a, State state, Vector3d shipPos, LivingEntity target, int maxGround) {
        PlateauTable table = ensurePlateauTable(a, shipPos);
        if (table == null || table.size() == 0) return null;

        double floor = maxGround + MIN_ALT_ABOVE_GROUND + 2.0;
        double cruiseY = a.airpadAnchor.getY() + a.kind.cruiseRise();
        double targetY = switch (state) {
            case LIFTOFF, RETURN, HOVER -> Math.max(cruiseY, floor);
            case PURSUE -> target == null
                    ? Math.max(cruiseY, floor)
                    : Math.max(target.getEyeY() + PURSUE_ALT_OFFSET, floor);
        };
        return table.pickClosest(targetY).toLiftSetting();
    }

    /** Rebuilds only when {@link Airship#balloonCapacity} changes. Mass is sampled at
     *  build time and never refreshed — see {@link PlateauTable}. Null while the
     *  balloon hasn't attached or the mass tracker is missing. */
    private static PlateauTable ensurePlateauTable(Airship a, Vector3d shipPos) {
        int cap = a.balloonCapacity;
        if (cap <= 0) return null;
        if (a.plateauTable != null && a.plateauTableCapacity == cap) return a.plateauTable;
        if (!(a.subLevel instanceof ServerSubLevel ssl)) return null;
        MassData md = ssl.getMassTracker();
        if (md == null || md.isInvalid()) return null;
        double mass = md.getMass();
        double liftStrength = AeroLiftingGasTypes.DEFAULT_GAS.get().getLiftStrength();
        int nBurners = Math.max(1, a.slBurnerPositions.size());
        int vMaxPerBurner = Math.max(LiftMath.BURNER_MIN_VOLUME,
                Math.min(LiftMath.BURNER_MAX_VOLUME, cap / nBurners));
        a.plateauTable = PlateauTable.build(
                a.parentLevel, mass, liftStrength, nBurners, vMaxPerBurner, shipPos.x, shipPos.z);
        a.plateauTableCapacity = cap;
        MCPirates.LOGGER.info(
                "ship {} ({}): plateau table built rows={} mass={} cap={} N={} vMax={} yRange=[{}..{}]",
                a.subLevel.getUniqueId(), a.kind.name(), a.plateauTable.size(),
                String.format("%.1f", mass), cap, nBurners, vMaxPerBurner,
                String.format("%.1f", a.plateauTable.minY()),
                String.format("%.1f", a.plateauTable.maxY()));
        return a.plateauTable;
    }

    private static double currentYawRadians(Airship a) {
        Quaterniond orient = a.subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(a.shipLocalForward), new Vector3d());
        return Math.atan2(-worldFwd.x, worldFwd.z);
    }

    private static void setBothClutchesDisengaged(Airship a) {
        Level subLevelLevel = a.subLevel.getLevel();
        ClutchLevers.setPowered(subLevelLevel, a.slLeftClutchLever, /*powered=disengaged=*/true);
        ClutchLevers.setPowered(subLevelLevel, a.slRightClutchLever, /*powered=disengaged=*/true);
    }

    /** One-shot shutdown when the crew is wiped: disengage both clutches so the
     *  propellers stop. Throttle / burner state is left as-is — a dead crew can't
     *  change it, and the ship drifts at whatever lift it had. */
    private static void shutdownDerelict(Airship a, Level subLevelLevel) {
        ClutchLevers.setPowered(subLevelLevel, a.slLeftClutchLever, true);
        ClutchLevers.setPowered(subLevelLevel, a.slRightClutchLever, true);
    }

    // ───────────────────────────── Debug overlay ─────────────────────────────

    private static void writeDebugActionbar(Airship a, LivingEntity target, Vector3d shipPos) {
        ServerPlayer closest = null;
        double bestSq = OVERLAY_RENDER_RADIUS * OVERLAY_RENDER_RADIUS;
        for (ServerPlayer sp : a.parentLevel.players()) {
            double pdx = sp.getX() - shipPos.x;
            double pdz = sp.getZ() - shipPos.z;
            double d2 = pdx * pdx + pdz * pdz;
            if (d2 < bestSq) {
                bestSq = d2;
                closest = sp;
            }
        }
        if (closest == null) return;

        // SubLevel block state is source of truth; read helpers return "_"/0 sentinels
        // for missing blocks so the overlay can't crash mid-debug.
        Level subLevelLevel = a.subLevel.getLevel();
        String engines =
                (ClutchLevers.isEngaged(subLevelLevel, a.slLeftClutchLever) ? "L" : "_")
                        + (ClutchLevers.isEngaged(subLevelLevel, a.slRightClutchLever) ? "R" : "_");
        int throttle = a.slThrottleLevers.isEmpty() ? 0
                : ThrottleLevers.readState(subLevelLevel, a.slThrottleLevers.get(0));
        int burnerVolume = a.slBurnerPositions.isEmpty() ? 0
                : HotAirBurners.readVolume(subLevelLevel, a.slBurnerPositions.get(0));
        String goalStr = Double.isNaN(a.lastGoalX)
                ? "—"
                : String.format("(%.0f,%.0f)", a.lastGoalX, a.lastGoalZ);
        String targetStr = target == null
                ? "no target"
                : String.format("→%s d=%.0f",
                        target.getName().getString(),
                        Math.sqrt(horizDistSq(shipPos, target.getX(), target.getZ())));
        String liftoffStr = a.state == State.LIFTOFF
                ? String.format(" steady=%d/%d", a.steadyTicks, AirshipStateMachine.LIFTOFF_STEADY_TICKS)
                : "";

        ChatFormatting stateColor = switch (a.state) {
            case LIFTOFF -> ChatFormatting.YELLOW;
            case PURSUE  -> ChatFormatting.RED;
            case RETURN  -> ChatFormatting.AQUA;
            case HOVER   -> ChatFormatting.GREEN;
        };

        Component msg = Component.empty()
                .append(Component.literal("[" + a.state.name() + ":" + a.kind.name() + "] ").withStyle(stateColor))
                .append(Component.literal(String.format(
                        "pos=(%.0f,%.1f,%.0f) thr=%d vol=%dm³ cap=%s %s goal=%s yaw_err=%.0f° %s%s",
                        shipPos.x, shipPos.y, shipPos.z,
                        throttle,
                        burnerVolume,
                        a.balloonCapacity < 0 ? "—" : (a.balloonCapacity + "m³"),
                        engines,
                        goalStr,
                        a.lastHeadingErrDeg,
                        targetStr,
                        liftoffStr
                )));
        closest.displayClientMessage(msg, /*actionBar=*/true);
    }

    // ───────────────────────────── Targeting ─────────────────────────────

    private static LivingEntity findEnemyPlayerOnAirship(Airship a, Vector3d shipPos) {
        java.util.function.Function<Airship, LivingEntity> override = targetOverride;
        if (override != null) {
            return override.apply(a);
        }
        ServerPlayer best = null;
        double bestSq = AirshipStateMachine.DISENGAGE_RANGE_SQ;
        for (ServerPlayer player : a.parentLevel.players()) {
            // TEMP: SubLevel filter disabled for creative-mode testing. Re-enable before ship.
            // SubLevel containing = dev.ryanhcode.sable.Sable.HELPER.getContaining(player);
            // if (containing == null || containing == a.subLevel) continue;
            double d2 = horizDistSq(shipPos, player.getX(), player.getZ());
            if (d2 < bestSq) {
                bestSq = d2;
                best = player;
            }
        }
        return best;
    }

    // ───────────────────────────── Math + utility ─────────────────────────────

    private static double horizDistSq(Vector3d a, BlockPos b) {
        return horizDistSq(a, b.getX() + 0.5, b.getZ() + 0.5);
    }
    private static double horizDistSq(Vector3d a, double bx, double bz) {
        double dx = a.x - bx, dz = a.z - bz;
        return dx * dx + dz * dz;
    }

}
