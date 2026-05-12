package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.ClutchLevers;
import com.mcpirates.airship.kind.ThrottleLevers;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-server-tick brain for assembled mcpirates pirate airships. Owns the high-level
 * state machine (LIFTOFF → PURSUE → RETURN → HOVER), the throttle PD controller, and
 * tank-steer logic. Ship-specific concerns — lever positions, cannon arrangement, combat
 * behaviour — live behind {@link AirshipKind}.
 *
 * <h2>Movement model</h2>
 *
 * Two propellers, each gated by a Create clutch. The clutch lever {@code powered=true}
 * <strong>disengages</strong> the clutch; {@code powered=false} engages it. We track
 * {@code leftEngaged}/{@code rightEngaged} intent and write {@code !engaged} to the
 * lever state via {@link ClutchLevers#setPowered}.
 *
 * <p>Tank steering: both engaged → straight; left only → pivot RIGHT; right only → pivot
 * LEFT; neither → drift.
 *
 * <p>Altitude: a 0..15 "throttle" lever drives the burner. Two-burner ships expose two
 * such levers in {@link Airship#slThrottleLevers}, and we write the same state to both —
 * {@link ThrottleLevers#setState} dispatches by BE class so {@code create:analog_lever}
 * and {@code simulated:throttle_lever} both work.
 *
 * <h2>Combat</h2>
 *
 * Aim every {@link #AIM_INTERVAL} ticks and fire every {@link AirshipKind#combat()
 * combat().fireIntervalTicks()} — but only while in {@link State#PURSUE} and only via the
 * kind's combat module. Single-cannon, no-cannon and broadside designs all go through the
 * same {@link com.mcpirates.airship.kind.CombatBehavior} interface.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipBrain {

    /** Engage/disengage PURSUE if a target enters/leaves this horizontal radius. Was
     *  12 chunks (192 blocks = exactly vanilla render distance) — ship started
     *  pursuing the instant it rendered, which felt sudden. 8 chunks (128 blocks)
     *  gives roughly 64 blocks of "ship is visible, player can appreciate it" before
     *  combat engages. */
    private static final double DISENGAGE_RANGE_SQ = (8 * 16) * (8 * 16);
    /** Considered "at airpad" (HOVER) when within this horizontal range. */
    private static final double HOVER_RADIUS_SQ = 16 * 16;
    /** Per-tick altitude delta below which we count the ship as "not climbing". The
     *  chosen value (0.05 blocks/tick) ignores Sable's small physics jitter while still
     *  catching real ascent. */
    private static final double LIFTOFF_STEADY_DELTA = 0.05;
    /** Consecutive steady ticks before LIFTOFF concludes. ~2 s at 20 tps = 40 ticks. */
    private static final int LIFTOFF_STEADY_TICKS = 40;
    /** Hard floor on LIFTOFF duration so we don't bail out before the ship's even moved
     *  (at t=0 ship is stationary so steady-tick count is technically high immediately). */
    private static final int LIFTOFF_MIN_TICKS = 60;
    /** Move within this distance of horizontal goal → drift, no thrust. */
    private static final double ARRIVAL_RADIUS_SQ = 12 * 12;
    /** Heading error band where we go straight; outside: pivot. */
    private static final double HEADING_TOLERANCE_DEG = 25.0;
    /** Aim period during PURSUE. */
    private static final int AIM_INTERVAL = 5;
    /** Movement decision period (re-evaluate levers and throttle every N ticks). */
    private static final int DECISION_INTERVAL = 10;
    /** Ticks of "no target" before dropping out of PURSUE. */
    private static final int LOST_TARGET_DEBOUNCE = 60;

    /** PURSUE strafing radius — ship orbits the target at this XZ distance. */
    private static final double ORBIT_RADIUS = 25.0;
    /** How far ahead along the tangent we aim the ship's heading. Larger = wider
     *  orbits, smoother turns. Smaller = tighter, more aggressive turning. */
    private static final double ORBIT_LOOK_AHEAD = 18.0;
    /** Strength of radial correction (fraction of look-ahead borrowed from tangent
     *  to pull the ship back to the orbit radius). 1.0 = fully radial when one
     *  radius off. */
    private static final double ORBIT_RADIAL_GAIN = 0.8;

    /** Show actionbar overlay for active pirate ships. */
    private static final boolean DEBUG_OVERLAY = true;
    /** Push actionbar text every N ticks. */
    private static final int OVERLAY_ACTIONBAR_INTERVAL = 10;
    /** Don't bother sending actionbar to players further than this (blocks). */
    private static final double OVERLAY_RENDER_RADIUS = 200.0;

    /** Cannon aiming is always on so the cannon visually tracks the player during
     *  PURSUE. Cheap, no projectiles spawned, useful for visual telemetry. */
    private static final boolean CANNON_AIM_ENABLED = true;

    /** Cannon FIRING is gated behind a runtime toggle (see {@link #setFireEnabled}).
     *  Defaults to OFF so test sessions aren't interrupted by cannonball spam.
     *  Flip from chat with {@code /mcpirates fire on|off}. */
    public static volatile boolean CANNON_FIRE_ENABLED = false;

    public static boolean setFireEnabled(boolean enabled) {
        CANNON_FIRE_ENABLED = enabled;
        return enabled;
    }

    private static final List<Airship> SHIPS = new CopyOnWriteArrayList<>();

    private AirshipBrain() {}

    public enum State { LIFTOFF, PURSUE, RETURN, HOVER }

    public static void register(
            ServerLevel parentLevel,
            SubLevel subLevel,
            BlockPos airpadAnchor,
            AirshipKind kind,
            List<BlockPos> slThrottleLevers,
            BlockPos slLeftClutchLever,
            BlockPos slRightClutchLever,
            List<BlockPos> slCannonMounts,
            Vector3d shipLocalForward,
            List<AnchoredEntity> anchoredEntities) {
        Airship a = new Airship(parentLevel, subLevel, airpadAnchor, kind,
                slThrottleLevers, slLeftClutchLever, slRightClutchLever,
                slCannonMounts, shipLocalForward, anchoredEntities);
        SHIPS.add(a);
        MCPirates.LOGGER.info(
                "registered pirate airship: kind={} subLevel={} anchor={} mounts={} throttles={} clutches=({},{}) fwd=({},{},{}) anchoredEntities={}",
                kind.name(), subLevel.getUniqueId(), airpadAnchor,
                slCannonMounts, slThrottleLevers,
                slLeftClutchLever, slRightClutchLever,
                shipLocalForward.x, shipLocalForward.y, shipLocalForward.z,
                anchoredEntities.size());
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

    /** @return false if this ship should be deregistered. */
    private static boolean tickShip(Airship a, long now) {
        // Re-acquire the live SubLevel by UUID. Sable's PhysicsChunkTicketManager will
        // moveToUnloaded() the SubLevel when its chunks leave the player's ticking
        // range, which removes the SubLevel from the container and marks it removed.
        // A cached SubLevel reference becomes a frozen snapshot — logicalPose() keeps
        // returning the last-known position forever and the ship appears stuck. When
        // the chunks reload, a *fresh* SubLevel object is constructed under the same
        // UUID, so we look it up each tick and adopt the live one.
        SubLevelContainer container = SubLevelContainer.getContainer(a.parentLevel);
        if (container == null) {
            return false; // dimension is being torn down
        }
        SubLevel live = container.getSubLevel(a.subLevelId);
        if (live == null) {
            // Holding-chunk state: SubLevel persisted to disk but not yet rehydrated.
            // Stay registered, do nothing this tick — once a player re-enters range
            // the SubLevel will be reloaded with the same UUID and we'll resume.
            return true;
        }
        if (live != a.subLevel) {
            a.subLevel = live;
        }
        Level subLevelLevel = a.subLevel.getLevel();
        if (subLevelLevel == null) {
            return true;
        }
        // Re-assert plot-position anchoring on any pillagers (captain, crewmate) that
        // have lost it. Sable's plot anchor lives in a @Unique mixin field that is NOT
        // serialised to entity NBT — so the moment our captain's chunk unloads and
        // reloads, the pillager comes back with plotPosition == null and Sable's per-
        // tick rebind silently stops. Visually, the captain freezes in mid-air at the
        // spot it happened to occupy when the chunk last saved. We re-apply lazily:
        // only when plotPosition is observed null, so this is a no-op during steady
        // flight (the field stays populated between reloads).
        reanchorEntities(a);
        Vector3d shipPos = a.subLevel.logicalPose().position();

        ServerPlayer target = findEnemyPlayerOnAirship(a, shipPos);
        if (target != null) {
            a.lastTargetSeenTick = now;
        }

        // Track altitude steadiness during LIFTOFF so we know when the ship has
        // reached the physical ceiling for its envelope size.
        if (a.state == State.LIFTOFF) {
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
        }

        // State transitions
        State desired = decideNextState(a, target, shipPos, now);
        if (desired != a.state) {
            MCPirates.LOGGER.info(
                    "ship {} ({}): state {} → {} (target={}, y={}, distAirpad={})",
                    a.subLevel.getUniqueId(), a.kind.name(), a.state, desired,
                    target == null ? "none" : target.getName().getString(),
                    String.format("%.1f", shipPos.y),
                    String.format("%.1f", Math.sqrt(horizDistSq(shipPos, a.airpadAnchor))));
            a.state = desired;
            a.stateEnteredTick = now;
            // On any state transition, snap both clutches to disengaged. Entering
            // PURSUE: applyMovement on the same tick will re-engage whichever side(s)
            // tank-steering wants, and the off→on edge reliably refreshes Aeronautics'
            // thrust contribution (observed once that the propellers can be visually
            // spinning but applying zero thrust after an idle chunk reload — toggling
            // the clutch lever fixed it). Entering HOVER: stay disengaged, so an idle
            // ship parks deliberately rather than coasting on stale engagement.
            if (desired == State.PURSUE || desired == State.HOVER) {
                setBothClutchesDisengaged(a);
            }
            applyMovement(a, target, shipPos, now);
            a.lastDecisionTick = now;
        } else if (now - a.lastDecisionTick >= DECISION_INTERVAL) {
            applyMovement(a, target, shipPos, now);
            a.lastDecisionTick = now;
        }

        // Cannon: aim is always on so the barrel visibly tracks the player; firing
        // is gated on the runtime CANNON_FIRE_ENABLED toggle.
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

        if (DEBUG_OVERLAY && now % OVERLAY_ACTIONBAR_INTERVAL == 0) {
            writeDebugActionbar(a, target, shipPos);
        }
        return true;
    }

    /** See class doc — re-applies sable$plotPosition after chunk reload. */
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
                        ae.role(), ae.uuid(), ae.plotPos());
            }
        }
    }

    // ───────────────────────────── State machine ─────────────────────────────

    private static State decideNextState(Airship a, ServerPlayer target, Vector3d shipPos, long now) {
        long ticksInState = now - a.stateEnteredTick;
        boolean liftoffDone = a.steadyTicks >= LIFTOFF_STEADY_TICKS
                && ticksInState >= LIFTOFF_MIN_TICKS;
        boolean atAirpad = horizDistSq(shipPos, a.airpadAnchor) < HOVER_RADIUS_SQ;
        boolean targetLost = (now - a.lastTargetSeenTick) > LOST_TARGET_DEBOUNCE;
        boolean targetTooFar = target != null
                && horizDistSq(shipPos, target.position().x, target.position().z) > DISENGAGE_RANGE_SQ;

        return switch (a.state) {
            case LIFTOFF -> {
                if (!liftoffDone) yield State.LIFTOFF;
                yield (target != null && !targetTooFar) ? State.PURSUE : State.RETURN;
            }
            case PURSUE -> {
                if (targetLost || targetTooFar) yield State.RETURN;
                yield State.PURSUE;
            }
            case RETURN -> {
                if (target != null && !targetTooFar) yield State.PURSUE;
                yield atAirpad ? State.HOVER : State.RETURN;
            }
            case HOVER -> {
                if (target != null && !targetTooFar) yield State.PURSUE;
                yield State.HOVER;
            }
        };
    }

    // ───────────────────────────── Movement ─────────────────────────────

    private static void applyMovement(Airship a, ServerPlayer target, Vector3d shipPos, long now) {
        Level subLevelLevel = a.subLevel.getLevel();

        // Pick a horizontal goal, or null if we're stationary (LIFTOFF, HOVER).
        double goalX = Double.NaN, goalZ = Double.NaN;
        switch (a.state) {
            case PURSUE -> {
                if (target != null) {
                    // Strafe in a circle around the target instead of flying straight
                    // at it. Cannon aiming is independent of the ship's heading, so
                    // staying tangent keeps a clear firing arc while avoiding the
                    // "hover-and-oscillate" failure mode of point-and-stop pursuit.
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
                    double radialErr = (r - ORBIT_RADIUS) / ORBIT_RADIUS;
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
                double err = normalizeRadians(targetYaw - currentYaw);
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

        // Translate intent → lever blockstate (clutch is INVERTED: powered=true disengages).
        ClutchLevers.setPowered(subLevelLevel, a.slLeftClutchLever, !leftEngaged);
        ClutchLevers.setPowered(subLevelLevel, a.slRightClutchLever, !rightEngaged);

        // Throttle: PD on altitude error relative to target/airpad with floor protection.
        int throttle = chooseThrottle(a, a.state, shipPos, target, a.parentLevel, now);
        for (BlockPos lever : a.slThrottleLevers) {
            ThrottleLevers.setState(subLevelLevel, lever, throttle);
        }
        // Diagnostic: log throttle decisions every ~2s so we can verify the floor
        // protection / soft descent logic actually fires when expected.
        long bucket = System.currentTimeMillis() / 2000;
        if (bucket != a.lastThrottleLogBucket) {
            a.lastThrottleLogBucket = bucket;
            int groundUnderShip = a.parentLevel.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    (int) Math.floor(shipPos.x),
                    (int) Math.floor(shipPos.z));
            Quaterniond orient2 = a.subLevel.logicalPose().orientation();
            Vector3d worldFwd2 = orient2.transform(new Vector3d(a.shipLocalForward), new Vector3d());
            double fLen = Math.sqrt(worldFwd2.x * worldFwd2.x + worldFwd2.z * worldFwd2.z);
            double fX = fLen > 0.001 ? worldFwd2.x / fLen : 0.0;
            double fZ = fLen > 0.001 ? worldFwd2.z / fLen : 0.0;
            int maxGround = groundUnderShip;
            for (int dist : GROUND_LOOKAHEAD_SAMPLES) {
                int sx = (int) Math.floor(shipPos.x + fX * dist);
                int sz = (int) Math.floor(shipPos.z + fZ * dist);
                int g = a.parentLevel.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        sx, sz);
                if (g > maxGround) maxGround = g;
            }
            double targetY = (a.state == State.PURSUE && target != null)
                    ? Math.max(target.getEyeY() + PURSUE_ALT_OFFSET, maxGround + MIN_ALT_ABOVE_GROUND + 2.0)
                    : Double.NaN;
            MCPirates.LOGGER.info(
                    "ship {} ({}) throttle: state={} thr={} shipY={} groundUnder={} maxGroundAhead={} altAboveMax={} targetY={} dy={}",
                    a.subLevel.getUniqueId(), a.kind.name(), a.state, throttle,
                    String.format("%.1f", shipPos.y),
                    groundUnderShip, maxGround,
                    String.format("%.1f", shipPos.y - maxGround),
                    Double.isNaN(targetY) ? "—" : String.format("%.1f", targetY),
                    Double.isNaN(targetY) ? "—" : String.format("%.1f", shipPos.y - targetY));
        }

        a.lastLeftEngaged = leftEngaged;
        a.lastRightEngaged = rightEngaged;
        a.lastThrottle = throttle;
        a.lastGoalX = goalX;
        a.lastGoalZ = goalZ;
        a.lastHeadingErrDeg = headingErrDeg;
    }

    // PURSUE altitude target = player.eye + this offset (blocks above the player).
    private static final double PURSUE_ALT_OFFSET = 12.0;
    /** Hard floor: throttle is forced to 15 when within this many blocks of the heightmap.
     *  30 chosen over 10 so a player walking on flat terrain can't pull the ship down to
     *  ~12 blocks above ground — at that altitude the ship looks grounded and the cannon's
     *  firing arc is cut off. */
    private static final double MIN_ALT_ABOVE_GROUND = 30.0;
    private static final int THROTTLE_HOVER = 9;
    private static final double THROTTLE_P_GAIN = 0.3;
    private static final double THROTTLE_VELOCITY_LOOKAHEAD_TICKS = 20.0;
    private static final int[] GROUND_LOOKAHEAD_SAMPLES = {0, 8, 16, 24, 32};

    private static int chooseThrottle(
            Airship a, State state, Vector3d shipPos, ServerPlayer target, ServerLevel level, long now) {
        // Look-ahead ground sampling: max heightmap value across ship XZ + points along
        // current heading. Cresting a slope clamps us above the *highest* terrain ahead,
        // not the (possibly lower) terrain directly below.
        Quaterniond orient = a.subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(a.shipLocalForward), new Vector3d());
        double fwdLen = Math.sqrt(worldFwd.x * worldFwd.x + worldFwd.z * worldFwd.z);
        double fwdX = fwdLen > 0.001 ? worldFwd.x / fwdLen : 0.0;
        double fwdZ = fwdLen > 0.001 ? worldFwd.z / fwdLen : 0.0;
        int maxGround = Integer.MIN_VALUE;
        for (int dist : GROUND_LOOKAHEAD_SAMPLES) {
            int sx = (int) Math.floor(shipPos.x + fwdX * dist);
            int sz = (int) Math.floor(shipPos.z + fwdZ * dist);
            int g = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    sx, sz);
            if (g > maxGround) maxGround = g;
        }

        if (shipPos.y - maxGround < MIN_ALT_ABOVE_GROUND) {
            return 15;
        }

        double targetY;
        switch (state) {
            case LIFTOFF -> { return 15; }
            case PURSUE -> {
                if (target == null) return 15;
                targetY = Math.max(
                        target.getEyeY() + PURSUE_ALT_OFFSET,
                        maxGround + MIN_ALT_ABOVE_GROUND + 2.0);
            }
            case RETURN, HOVER -> targetY = Math.max(shipPos.y, maxGround + MIN_ALT_ABOVE_GROUND + 2.0);
            default -> { return 15; }
        }
        double dy = shipPos.y - targetY;

        double vy = 0.0;
        if (!Double.isNaN(a.lastYSample) && a.lastYSampleTick > 0) {
            long dt = Math.max(1, now - a.lastYSampleTick);
            vy = (shipPos.y - a.lastYSample) / dt;
        }
        a.lastYSample = shipPos.y;
        a.lastYSampleTick = now;

        double predictedDy = dy + vy * THROTTLE_VELOCITY_LOOKAHEAD_TICKS;
        double throttleD = THROTTLE_HOVER - predictedDy * THROTTLE_P_GAIN;
        return (int) Math.round(Math.max(0, Math.min(15, throttleD)));
    }

    /** Current yaw of the airship in WORLD frame. Derived by transforming its local-forward
     *  vector through the SubLevel's logical-pose orientation. */
    private static double currentYawRadians(Airship a) {
        Quaterniond orient = a.subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(a.shipLocalForward), new Vector3d());
        return Math.atan2(-worldFwd.x, worldFwd.z);
    }

    /** Force both clutches to DISENGAGED (lever powered=true). Called at state transitions
     *  (PURSUE entry resets so the new tank-steer decision starts clean; HOVER entry parks
     *  the ship deliberately rather than coasting on stale engagement). */
    private static void setBothClutchesDisengaged(Airship a) {
        Level subLevelLevel = a.subLevel.getLevel();
        ClutchLevers.setPowered(subLevelLevel, a.slLeftClutchLever, /*powered=disengaged=*/true);
        ClutchLevers.setPowered(subLevelLevel, a.slRightClutchLever, /*powered=disengaged=*/true);
    }

    // ───────────────────────────── Debug overlay ─────────────────────────────

    private static void writeDebugActionbar(Airship a, ServerPlayer target, Vector3d shipPos) {
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

        String engines =
                (a.lastLeftEngaged ? "L" : "_") + (a.lastRightEngaged ? "R" : "_");
        String goalStr = Double.isNaN(a.lastGoalX)
                ? "—"
                : String.format("(%.0f,%.0f)", a.lastGoalX, a.lastGoalZ);
        String targetStr = target == null
                ? "no target"
                : String.format("→%s d=%.0f",
                        target.getName().getString(),
                        Math.sqrt(horizDistSq(shipPos, target.getX(), target.getZ())));
        String liftoffStr = a.state == State.LIFTOFF
                ? String.format(" steady=%d/%d", a.steadyTicks, LIFTOFF_STEADY_TICKS)
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
                        "pos=(%.0f,%.1f,%.0f) thr=%d %s goal=%s yaw_err=%.0f° %s%s",
                        shipPos.x, shipPos.y, shipPos.z,
                        a.lastThrottle,
                        engines,
                        goalStr,
                        a.lastHeadingErrDeg,
                        targetStr,
                        liftoffStr
                )));
        closest.displayClientMessage(msg, /*actionBar=*/true);
    }

    // ───────────────────────────── Targeting ─────────────────────────────

    private static ServerPlayer findEnemyPlayerOnAirship(Airship a, Vector3d shipPos) {
        ServerPlayer best = null;
        double bestSq = DISENGAGE_RANGE_SQ;
        for (ServerPlayer player : a.parentLevel.players()) {
            // TEMP: SubLevel filter disabled for creative-mode testing — any player in
            // range becomes a PURSUE target. Re-enable before ship.
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

    private static double normalizeRadians(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
