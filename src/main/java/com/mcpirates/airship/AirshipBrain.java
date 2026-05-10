package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-server-tick brain for assembled mcpirates pirate airships. Owns both the high-level
 * state machine (LIFTOFF → PURSUE → RETURN → HOVER) and the low-level actuation (toggling
 * propeller-clutch levers and the burner analog lever).
 *
 * <h2>Movement model</h2>
 * Two propellers, each gated by a Create clutch. The clutch lever <em>powered=true</em>
 * <strong>disengages</strong> the clutch (no rotation passes); <em>powered=false</em>
 * engages it. So our control logic tracks <code>leftEngaged</code> / <code>rightEngaged</code>
 * (intent) and writes <code>!engaged</code> to the lever blockstate.
 *
 * <p>Tank steering: both engaged → straight; left only → pivot RIGHT; right only → pivot
 * LEFT; neither → drift.
 *
 * <p>Altitude: the analog lever drives the burner. State 15 climbs hard, ~7 hovers, 0
 * descends. We bang-bang on altitude error within a hysteresis window to avoid lever
 * thrash.
 *
 * <h2>Cannon</h2>
 * Aim every 5 ticks and fire every {@link #FIRE_INTERVAL_TICKS} — but only while in
 * {@link State#PURSUE}. RETURN/HOVER/LIFTOFF: no aim, no fire (the pirate doesn't shoot
 * while disengaging).
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipBrain {

    /** Disengage if target leaves this radius (horizontal, from airpad anchor). */
    private static final double DISENGAGE_RANGE_SQ = (12 * 16) * (12 * 16);
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
    /** Cooldown between cannon shots. */
    private static final int FIRE_INTERVAL_TICKS = 200;
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

    private static final ResourceLocation POWDER_CHARGE_ID =
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "powder_charge");
    private static final ResourceLocation SOLID_SHOT_ID =
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "solid_shot");

    private static final List<Pirate> SHIPS = new CopyOnWriteArrayList<>();
    private static final java.util.Set<UUID> AIMED_ONCE = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<UUID> FIRED_ONCE = ConcurrentHashMap.newKeySet();

    private static Field cachedAnalogStateField;
    private static Field cachedMountedContraptionField;
    private static Field cachedCannonYawField;
    private static Field cachedCannonPitchField;

    private AirshipBrain() {}

    public enum State { LIFTOFF, PURSUE, RETURN, HOVER }

    public static void register(
            ServerLevel parentLevel,
            SubLevel subLevel,
            BlockPos airpadAnchor,
            BlockPos slAnalogLever,
            BlockPos slLeftClutchLever,
            BlockPos slRightClutchLever,
            BlockPos slCannonMount,
            Vector3d shipLocalForward) {
        Pirate p = new Pirate(parentLevel, subLevel, airpadAnchor,
                slAnalogLever, slLeftClutchLever, slRightClutchLever, slCannonMount,
                shipLocalForward);
        SHIPS.add(p);
        MCPirates.LOGGER.info(
                "registered pirate airship: subLevel={} anchor={} mount={} leftClutch={} rightClutch={} fwd=({},{},{})",
                subLevel.getUniqueId(), airpadAnchor, slCannonMount,
                slLeftClutchLever, slRightClutchLever,
                shipLocalForward.x, shipLocalForward.y, shipLocalForward.z);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        for (Pirate p : SHIPS) {
            if (!tickPirate(p, now)) {
                SHIPS.remove(p);
            }
        }
    }

    /** @return false if this pirate should be deregistered. */
    private static boolean tickPirate(Pirate p, long now) {
        Level subLevelLevel = p.subLevel.getLevel();
        if (subLevelLevel == null) {
            return false;
        }
        Vector3d shipPos = p.subLevel.logicalPose().position();

        ServerPlayer target = findEnemyPlayerOnAirship(p, shipPos);
        if (target != null) {
            p.lastTargetSeenTick = now;
        }

        // Track altitude steadiness during LIFTOFF so we know when the ship has
        // reached the physical ceiling for its envelope size.
        if (p.state == State.LIFTOFF) {
            if (!Double.isNaN(p.lastSampledY)) {
                if (Math.abs(shipPos.y - p.lastSampledY) < LIFTOFF_STEADY_DELTA) {
                    p.steadyTicks++;
                } else {
                    p.steadyTicks = 0;
                }
            }
            p.lastSampledY = shipPos.y;
        } else {
            p.steadyTicks = 0;
            p.lastSampledY = Double.NaN;
        }

        // State transitions
        State desired = decideNextState(p, target, shipPos, now);
        if (desired != p.state) {
            MCPirates.LOGGER.info(
                    "pirate {}: state {} → {} (target={}, y={}, distAirpad={})",
                    p.subLevel.getUniqueId(), p.state, desired,
                    target == null ? "none" : target.getName().getString(),
                    String.format("%.1f", shipPos.y),
                    String.format("%.1f", Math.sqrt(horizDistSq(shipPos, p.airpadAnchor))));
            p.state = desired;
            p.stateEnteredTick = now;
            applyMovement(p, target, shipPos, now);
            p.lastDecisionTick = now;
        } else if (now - p.lastDecisionTick >= DECISION_INTERVAL) {
            applyMovement(p, target, shipPos, now);
            p.lastDecisionTick = now;
        }

        // Cannon: aim + fire only while pursuing.
        if (p.state == State.PURSUE && target != null) {
            if (now % AIM_INTERVAL == 0) {
                aimCannon(p, target);
            }
            if (now - p.lastFireTick >= FIRE_INTERVAL_TICKS) {
                if (fireCannon(p)) {
                    p.lastFireTick = now;
                }
            }
        }

        if (DEBUG_OVERLAY && now % OVERLAY_ACTIONBAR_INTERVAL == 0) {
            writeDebugActionbar(p, target, shipPos);
        }
        return true;
    }

    // ───────────────────────────── State machine ─────────────────────────────

    private static State decideNextState(Pirate p, ServerPlayer target, Vector3d shipPos, long now) {
        long ticksInState = now - p.stateEnteredTick;
        // LIFTOFF ends when altitude has been steady (not climbing) for a sustained
        // window — i.e., the ship has hit its physical ceiling for the envelope size.
        boolean liftoffDone = p.steadyTicks >= LIFTOFF_STEADY_TICKS
                && ticksInState >= LIFTOFF_MIN_TICKS;
        boolean atAirpad = horizDistSq(shipPos, p.airpadAnchor) < HOVER_RADIUS_SQ;
        boolean targetLost = (now - p.lastTargetSeenTick) > LOST_TARGET_DEBOUNCE;
        boolean targetTooFar = target != null
                && horizDistSq(shipPos, target.position().x, target.position().z) > DISENGAGE_RANGE_SQ;

        return switch (p.state) {
            case LIFTOFF -> {
                if (!liftoffDone) yield State.LIFTOFF;
                yield (target != null && !targetTooFar) ? State.PURSUE : State.RETURN;
            }
            case PURSUE -> {
                if (targetLost || targetTooFar) yield State.RETURN;
                yield State.PURSUE;
            }
            case RETURN -> {
                // Re-engage if a target shows up
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

    private static void applyMovement(Pirate p, ServerPlayer target, Vector3d shipPos, long now) {
        Level subLevelLevel = p.subLevel.getLevel();

        // Pick a horizontal goal, or null if we're stationary (LIFTOFF, HOVER).
        double goalX = Double.NaN, goalZ = Double.NaN;
        switch (p.state) {
            case PURSUE -> {
                if (target != null) {
                    // Strafe in a circle around the target instead of flying straight
                    // at it. The cannon (which aims independently) can keep firing
                    // while the ship's hull stays tangent to the orbit. Avoids the
                    // "hover-and-oscillate" failure mode of point-and-stop pursuit.
                    double ftx = shipPos.x - target.getX();
                    double ftz = shipPos.z - target.getZ();
                    double r = Math.sqrt(ftx * ftx + ftz * ftz);
                    if (r < 0.01) {
                        // Degenerate: ship on top of target. Pick an arbitrary direction.
                        ftx = 1.0; ftz = 0.0; r = 1.0;
                    }
                    // CCW tangent unit vector at the ship's current angle around target.
                    double tanX = -ftz / r;
                    double tanZ = ftx / r;
                    // Radial unit pointing inward (toward target).
                    double radInX = -ftx / r;
                    double radInZ = -ftz / r;
                    // Blend tangent + radial correction. radialErr > 0: outside orbit,
                    // pull inward; < 0: inside orbit, push outward.
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
                goalX = p.airpadAnchor.getX() + 0.5;
                goalZ = p.airpadAnchor.getZ() + 0.5;
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
                double currentYaw = currentYawRadians(p);
                double err = normalizeRadians(targetYaw - currentYaw);
                headingErrDeg = Math.toDegrees(err);
                double absErrDeg = Math.abs(headingErrDeg);

                if (absErrDeg < HEADING_TOLERANCE_DEG) {
                    leftEngaged = true;
                    rightEngaged = true;
                } else if (err > 0) {
                    // Target is to our right (yaw increases). Tank-steer right: kill the
                    // right engine so the left side pushes the nose around clockwise.
                    leftEngaged = true;
                    rightEngaged = false;
                } else {
                    leftEngaged = false;
                    rightEngaged = true;
                }
            }
        }

        // Translate intent → lever blockstate (clutch is INVERTED: powered=true disengages).
        setLeverPowered(subLevelLevel, p.slLeftClutchLever, !leftEngaged);
        setLeverPowered(subLevelLevel, p.slRightClutchLever, !rightEngaged);

        // Throttle: bang-bang on altitude error relative to target/airpad,
        // with floor protection + stuck-on-terrain override.
        int throttle = chooseThrottle(p, p.state, shipPos, target, p.parentLevel, now);
        setAnalogLeverState(subLevelLevel, p.slAnalogLever, throttle);
        // Diagnostic: log throttle decisions every ~2s so we can verify the floor
        // protection / soft descent logic actually fires when expected.
        long bucket = System.currentTimeMillis() / 2000;
        if (bucket != p.lastThrottleLogBucket) {
            p.lastThrottleLogBucket = bucket;
            int groundUnderShip = p.parentLevel.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    (int) Math.floor(shipPos.x),
                    (int) Math.floor(shipPos.z));
            // Look-ahead max ground over the same samples chooseThrottle uses.
            Quaterniond orient2 = p.subLevel.logicalPose().orientation();
            Vector3d worldFwd2 = orient2.transform(new Vector3d(p.shipLocalForward), new Vector3d());
            double fLen = Math.sqrt(worldFwd2.x * worldFwd2.x + worldFwd2.z * worldFwd2.z);
            double fX = fLen > 0.001 ? worldFwd2.x / fLen : 0.0;
            double fZ = fLen > 0.001 ? worldFwd2.z / fLen : 0.0;
            int maxGround = groundUnderShip;
            for (int dist : GROUND_LOOKAHEAD_SAMPLES) {
                int sx = (int) Math.floor(shipPos.x + fX * dist);
                int sz = (int) Math.floor(shipPos.z + fZ * dist);
                int g = p.parentLevel.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        sx, sz);
                if (g > maxGround) maxGround = g;
            }
            double targetY = (p.state == State.PURSUE && target != null)
                    ? Math.max(target.getEyeY() + PURSUE_ALT_OFFSET, maxGround + MIN_ALT_ABOVE_GROUND + 2.0)
                    : Double.NaN;
            MCPirates.LOGGER.info(
                    "pirate {} throttle: state={} thr={} shipY={} groundUnder={} maxGroundAhead={} altAboveMax={} targetY={} dy={} stuckTicks={}",
                    p.subLevel.getUniqueId(), p.state, throttle,
                    String.format("%.1f", shipPos.y),
                    groundUnderShip, maxGround,
                    String.format("%.1f", shipPos.y - maxGround),
                    Double.isNaN(targetY) ? "—" : String.format("%.1f", targetY),
                    Double.isNaN(targetY) ? "—" : String.format("%.1f", shipPos.y - targetY),
                    p.stuckTicks);
        }

        // Record for the debug overlay.
        p.lastLeftEngaged = leftEngaged;
        p.lastRightEngaged = rightEngaged;
        p.lastThrottle = throttle;
        p.lastGoalX = goalX;
        p.lastGoalZ = goalZ;
        p.lastHeadingErrDeg = headingErrDeg;
    }

    /**
     * Throttle policy for the burner's analog lever (0..15). LIFTOFF runs flat out so
     * the ship reaches its envelope ceiling. In PURSUE we aim {@link #PURSUE_ALT_OFFSET}
     * blocks above the target so the cannon has firing arc. RETURN/HOVER hover at the
     * current altitude.
     *
     * <p>Two safety layers:
     * <ul>
     *     <li><b>Floor protection</b>: ground proximity checked via the world's
     *         {@code MOTION_BLOCKING} heightmap. If the ship is within
     *         {@link #MIN_ALT_ABOVE_GROUND} blocks of the highest solid block below it,
     *         throttle is forced to 15 regardless of state. This prevents both the
     *         "thr=0 slams into the ground" and the "lands on grass and can't take off
     *         again" cases.</li>
     *     <li><b>Soft descent</b>: when above target altitude we set throttle to a
     *         small-but-nonzero value so the ship sinks gradually instead of dropping
     *         like a stone.</li>
     * </ul>
     */
    private static final double PURSUE_ALT_OFFSET = 12.0;
    private static final double MIN_ALT_ABOVE_GROUND = 10.0;
    /** Equilibrium throttle — guess; PD control trims around this. */
    private static final int THROTTLE_HOVER = 9;
    /** Proportional gain: throttle units per block of altitude error. 0.3 means a
     *  10-block error pushes throttle 3 steps off hover. */
    private static final double THROTTLE_P_GAIN = 0.3;
    /** Derivative gain (lookahead in ticks): the ship's current vy is extrapolated
     *  this many ticks into the future to anticipate momentum. Larger = more damping
     *  but more sluggish recovery. */
    private static final double THROTTLE_VELOCITY_LOOKAHEAD_TICKS = 20.0;
    /** Within this Y delta two samples count as "no movement" for stuck detection. */
    private static final double STUCK_Y_EPSILON = 0.05;
    /** Stuck if shipY hasn't budged for this many ticks while we're trying to descend. */
    private static final int STUCK_TICKS_THRESHOLD = 30;
    /** Distances (blocks) ahead along the heading direction at which we sample terrain
     *  height for floor protection. The MAX over these + ship-center sample becomes
     *  the effective ground level, so cresting a slope doesn't crash the ship. */
    private static final int[] GROUND_LOOKAHEAD_SAMPLES = {0, 8, 16, 24, 32};

    /**
     * Throttle scales with altitude error so we descend faster when we're way above
     * target. Three safety overrides:
     *
     * <ul>
     *     <li><b>Look-ahead floor protection</b> — terrain height is sampled at the
     *         ship's XZ plus several points along the heading direction, so cresting
     *         a slope clamps us above the *highest* terrain in front of us, not the
     *         (possibly lower) terrain directly below.</li>
     *     <li><b>Ground-clamped target altitude</b> — for PURSUE the requested
     *         altitude is at least {@code maxGround + MIN_ALT_ABOVE_GROUND}. A target
     *         in a valley below a ridge can't ask the ship to dive into the ridge.</li>
     *     <li><b>Stuck override</b> — if shipY hasn't moved for {@link #STUCK_TICKS_THRESHOLD}
     *         ticks while we're commanding descent, the ship is resting on something the
     *         heightmap can't see. Force throttle 15 to break free.</li>
     * </ul>
     */
    private static int chooseThrottle(
            Pirate p, State state, Vector3d shipPos, ServerPlayer target, ServerLevel level, long now) {
        // Look-ahead ground sampling: take max heightmap value across ship XZ +
        // points along the current heading direction.
        Quaterniond orient = p.subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(p.shipLocalForward), new Vector3d());
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
            return 15; // floor protection — terrain within reach is too close
        }

        double targetY;
        switch (state) {
            case LIFTOFF -> { return 15; }
            case PURSUE -> {
                if (target == null) return 15;
                // Clamp PURSUE altitude above terrain. A player in a valley behind a
                // mountain shouldn't pull the ship into the mountain.
                targetY = Math.max(
                        target.getEyeY() + PURSUE_ALT_OFFSET,
                        maxGround + MIN_ALT_ABOVE_GROUND + 2.0);
            }
            case RETURN, HOVER -> targetY = Math.max(shipPos.y, maxGround + MIN_ALT_ABOVE_GROUND + 2.0);
            default -> { return 15; }
        }
        double dy = shipPos.y - targetY;

        // Estimate vertical velocity from the previous sample so we can damp it
        // (PD control). Aeronautics airships have noticeable inertia — without
        // this damping, throttle bang-bangs and the ship yo-yos around target alt.
        double vy = 0.0;
        if (!Double.isNaN(p.stuckCheckLastY) && p.stuckCheckLastTick > 0) {
            long dt = Math.max(1, now - p.stuckCheckLastTick);
            vy = (shipPos.y - p.stuckCheckLastY) / dt;
        }

        // Stuck detector — only count "trying to move but not" ticks.
        if (Math.abs(dy) > 3.0) {
            if (!Double.isNaN(p.stuckCheckLastY)
                    && Math.abs(shipPos.y - p.stuckCheckLastY) < STUCK_Y_EPSILON) {
                p.stuckTicks += (int) Math.max(1, now - p.stuckCheckLastTick);
            } else {
                p.stuckTicks = 0;
            }
        } else {
            p.stuckTicks = 0;
        }
        p.stuckCheckLastY = shipPos.y;
        p.stuckCheckLastTick = now;

        if (dy > 3.0 && p.stuckTicks > STUCK_TICKS_THRESHOLD
                && shipPos.y - maxGround < 15.0) {
            // Wedged on terrain the heightmap can't see — break free with full burn.
            // Only applies when we're actually *near* the ground; at envelope ceiling
            // (high altitude, stuck because there's no more buoyancy headroom) full
            // burn keeps us pinned at the ceiling instead of letting the PD descend.
            return 15;
        }

        // Predicted-error PD control: where will the ship be in the lookahead window
        // if its current velocity continues unchanged? Throttle proportionally to
        // close that predicted error.
        double predictedDy = dy + vy * THROTTLE_VELOCITY_LOOKAHEAD_TICKS;
        double throttleD = THROTTLE_HOVER - predictedDy * THROTTLE_P_GAIN;
        return (int) Math.round(Math.max(0, Math.min(15, throttleD)));
    }

    /**
     * Current yaw of the airship in WORLD frame, derived by transforming its local-forward
     * vector through the SubLevel's logical pose orientation. Local-forward depends on the
     * structure rotation that worldgen chose.
     */
    private static double currentYawRadians(Pirate p) {
        Quaterniond orient = p.subLevel.logicalPose().orientation();
        Vector3d worldFwd = orient.transform(new Vector3d(p.shipLocalForward), new Vector3d());
        return Math.atan2(-worldFwd.x, worldFwd.z);
    }

    // ───────────────────────────── Cannon ─────────────────────────────

    private static void aimCannon(Pirate p, ServerPlayer target) {
        Level subLevelLevel = p.subLevel.getLevel();
        if (!(subLevelLevel.getBlockEntity(p.slCannonMount) instanceof CannonMountBlockEntity mount)) {
            return;
        }
        // CBC stores the cannon's yaw in absolute MC world yaw (the mount BE is
        // initialized from its blockstate's HORIZONTAL_FACING.toYRot()), so we
        // must compute the aim in WORLD frame — not SubLevel-local. Going through
        // logicalPose().transformPositionInverse(target) gives SL-local coords
        // and matches CBC's stored yaw only when the SubLevel hasn't rotated;
        // once the ship turns during PURSUE→RETURN→PURSUE, the SL-local yaw
        // accumulates a shipPose offset and the cannon mis-aims.
        Vector3d cannonLocal = new Vector3d(
                p.slCannonMount.getX() + 0.5,
                p.slCannonMount.getY() + 0.5,
                p.slCannonMount.getZ() + 0.5);
        Vec3 cannonWorldV = p.subLevel.logicalPose().transformPosition(
                new Vec3(cannonLocal.x, cannonLocal.y, cannonLocal.z));

        double dx = target.getX() - cannonWorldV.x;
        double dy = target.getEyeY() - cannonWorldV.y;
        double dz = target.getZ() - cannonWorldV.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Create Big Cannons treats positive pitch as "barrel up" (opposite of vanilla),
        // so we don't negate atan2 here.
        float pitch = (float) Math.toDegrees(Math.atan2(dy, horiz));
        mount.setYaw(yaw);
        mount.setPitch(pitch);

        // Diagnostic: log aim state every ~2s so we can see whether the cannon
        // tracks correctly across PURSUE → RETURN → PURSUE state cycles.
        long bucket = System.currentTimeMillis() / 2000;
        boolean firstAim = AIMED_ONCE.add(p.subLevel.getUniqueId());
        if (firstAim || bucket != p.lastAimLogBucket) {
            p.lastAimLogBucket = bucket;
            float storedYaw = readCannonYaw(mount);
            float storedPitch = readCannonPitch(mount);
            double shipPoseYawDeg = Math.toDegrees(currentYawRadians(p));
            MCPirates.LOGGER.info(
                    "pirate {} {} aim: setYaw={} setPitch={} storedYaw={} storedPitch={} shipPoseYaw={}",
                    p.subLevel.getUniqueId(),
                    firstAim ? "first" : "tick",
                    String.format("%.1f", yaw),
                    String.format("%.1f", pitch),
                    String.format("%.1f", storedYaw),
                    String.format("%.1f", storedPitch),
                    String.format("%.1f", shipPoseYawDeg));
        }
    }

    private static boolean fireCannon(Pirate p) {
        Level subLevelLevel = p.subLevel.getLevel();
        if (!(subLevelLevel.getBlockEntity(p.slCannonMount) instanceof CannonMountBlockEntity mount)) {
            return false;
        }
        PitchOrientedContraptionEntity entity = getMountedContraption(mount);
        if (entity == null
                || !(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)) {
            return false;
        }
        loadIfNeeded(cannon);
        try {
            cannon.fireShot(p.parentLevel, entity);
            if (FIRED_ONCE.add(p.subLevel.getUniqueId())) {
                MCPirates.LOGGER.info("pirate {} first fire", p.subLevel.getUniqueId());
            }
            return true;
        } catch (Throwable th) {
            MCPirates.LOGGER.error("fireShot threw at {}: {}", p.slCannonMount, th.toString());
            return false;
        }
    }

    private static void loadIfNeeded(AbstractMountedCannonContraption cannon) {
        BlockState powder = blockState(POWDER_CHARGE_ID);
        BlockState shot = blockState(SOLID_SHOT_ID);
        if (powder == null || shot == null) return;

        int loaded = 0;
        for (BlockEntity be : cannon.presentBlockEntities.values()) {
            if (!(be instanceof SmartBlockEntity sbe)) continue;
            BigCannonBehavior beh = bigCannonBehavior(sbe);
            if (beh == null) continue;
            StructureBlockInfo current = beh.block();
            if (current != null && !current.state().isAir()) continue;

            BlockState toLoad = (loaded == 0) ? powder : shot;
            beh.loadBlock(new StructureBlockInfo(BlockPos.ZERO, toLoad, null));
            if (++loaded >= 2) return;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BigCannonBehavior bigCannonBehavior(SmartBlockEntity sbe) {
        BlockEntityBehaviour b = sbe.getBehaviour((BehaviourType) BigCannonBehavior.TYPE);
        return (b instanceof BigCannonBehavior bcb) ? bcb : null;
    }

    /** Diagnostic: read CBC's stored cannonYaw via reflection so we can compare to what
     *  we passed to {@link CannonMountBlockEntity#setYaw(float)}. Returns NaN if reflect fails. */
    private static float readCannonYaw(CannonMountBlockEntity mount) {
        try {
            if (cachedCannonYawField == null) {
                cachedCannonYawField = CannonMountBlockEntity.class.getDeclaredField("cannonYaw");
                cachedCannonYawField.setAccessible(true);
            }
            return cachedCannonYawField.getFloat(mount);
        } catch (ReflectiveOperationException e) {
            return Float.NaN;
        }
    }

    private static float readCannonPitch(CannonMountBlockEntity mount) {
        try {
            if (cachedCannonPitchField == null) {
                cachedCannonPitchField = CannonMountBlockEntity.class.getDeclaredField("cannonPitch");
                cachedCannonPitchField.setAccessible(true);
            }
            return cachedCannonPitchField.getFloat(mount);
        } catch (ReflectiveOperationException e) {
            return Float.NaN;
        }
    }

    /** Wraps degrees into (-180, 180] for cleaner offset display. */
    private static double normalizeDeg(double d) {
        d = ((d + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return d;
    }

    private static PitchOrientedContraptionEntity getMountedContraption(CannonMountBlockEntity mount) {
        try {
            if (cachedMountedContraptionField == null) {
                cachedMountedContraptionField =
                        CannonMountBlockEntity.class.getDeclaredField("mountedContraption");
                cachedMountedContraptionField.setAccessible(true);
            }
            return (PitchOrientedContraptionEntity) cachedMountedContraptionField.get(mount);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ───────────────────────────── Lever helpers ─────────────────────────────

    /** Set the analog lever (burner throttle) to {@code state} (0..15). Idempotent: skips
     *  the write if the state is already correct. Mirrors the neighbour-update ritual the
     *  AirshipLiftoffTrigger uses so the burner sees the change immediately. */
    private static void setAnalogLeverState(Level level, BlockPos pos, int state) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AnalogLeverBlockEntity lever)) return;
        if (lever.getState() == state) return;
        try {
            if (cachedAnalogStateField == null) {
                cachedAnalogStateField = AnalogLeverBlockEntity.class.getDeclaredField("state");
                cachedAnalogStateField.setAccessible(true);
            }
            cachedAnalogStateField.setInt(lever, state);
        } catch (ReflectiveOperationException e) {
            return;
        }
        lever.setChanged();
        BlockState bs = level.getBlockState(pos);
        Block block = bs.getBlock();
        level.updateNeighborsAt(pos, block);
        Direction connected = leverConnectedDirection(bs);
        level.updateNeighborsAt(pos.relative(connected.getOpposite()), block);
        level.sendBlockUpdated(pos, bs, bs, Block.UPDATE_ALL);
    }

    /** Toggle a vanilla lever's POWERED state, with the proper neighbour updates so adjacent
     *  Create kinetic blocks (clutches) see the redstone change. */
    private static void setLeverPowered(Level level, BlockPos pos, boolean powered) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LeverBlock leverBlock)) return;
        if (state.getValue(LeverBlock.POWERED) == powered) return;
        BlockState updated = state.setValue(LeverBlock.POWERED, powered);
        level.setBlock(pos, updated, Block.UPDATE_ALL);
        level.updateNeighborsAt(pos, leverBlock);
        Direction face = leverConnectedDirection(state);
        level.updateNeighborsAt(pos.relative(face.getOpposite()), leverBlock);
    }

    private static Direction leverConnectedDirection(BlockState state) {
        AttachFace face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
        return switch (face) {
            case CEILING -> Direction.DOWN;
            case FLOOR -> Direction.UP;
            case WALL -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
        };
    }

    // ───────────────────────────── Debug overlay ─────────────────────────────

    /**
     * Pushes a one-line state readout to the actionbar of the player nearest this airship.
     * Multiple airships → each player only sees the closest pirate's state.
     */
    private static void writeDebugActionbar(Pirate p, ServerPlayer target, Vector3d shipPos) {
        ServerPlayer closest = null;
        double bestSq = OVERLAY_RENDER_RADIUS * OVERLAY_RENDER_RADIUS;
        for (ServerPlayer sp : p.parentLevel.players()) {
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
                (p.lastLeftEngaged ? "L" : "_") + (p.lastRightEngaged ? "R" : "_");
        String goalStr = Double.isNaN(p.lastGoalX)
                ? "—"
                : String.format("(%.0f,%.0f)", p.lastGoalX, p.lastGoalZ);
        String targetStr = target == null
                ? "no target"
                : String.format("→%s d=%.0f",
                        target.getName().getString(),
                        Math.sqrt(horizDistSq(shipPos, target.getX(), target.getZ())));
        String liftoffStr = p.state == State.LIFTOFF
                ? String.format(" steady=%d/%d", p.steadyTicks, LIFTOFF_STEADY_TICKS)
                : "";

        ChatFormatting stateColor = switch (p.state) {
            case LIFTOFF -> ChatFormatting.YELLOW;
            case PURSUE  -> ChatFormatting.RED;
            case RETURN  -> ChatFormatting.AQUA;
            case HOVER   -> ChatFormatting.GREEN;
        };

        Component msg = Component.empty()
                .append(Component.literal("[" + p.state.name() + "] ").withStyle(stateColor))
                .append(Component.literal(String.format(
                        "pos=(%.0f,%.1f,%.0f) thr=%d %s goal=%s yaw_err=%.0f° %s%s",
                        shipPos.x, shipPos.y, shipPos.z,
                        p.lastThrottle,
                        engines,
                        goalStr,
                        p.lastHeadingErrDeg,
                        targetStr,
                        liftoffStr
                )));
        closest.displayClientMessage(msg, /*actionBar=*/true);
    }

    // ───────────────────────────── Targeting ─────────────────────────────

    /**
     * Closest-by-horizontal-distance player who is currently inside any SubLevel that isn't
     * ours. We don't filter further (e.g. require an envelope) — any player on a contraption
     * counts as a target for v1.
     */
    private static ServerPlayer findEnemyPlayerOnAirship(Pirate p, Vector3d shipPos) {
        ServerPlayer best = null;
        double bestSq = DISENGAGE_RANGE_SQ;
        for (ServerPlayer player : p.parentLevel.players()) {
            // TEMP: SubLevel filter disabled for creative-mode testing — any player in
            // range becomes a PURSUE target. Re-enable before ship.
            // SubLevel containing = dev.ryanhcode.sable.Sable.HELPER.getContaining(player);
            // if (containing == null || containing == p.subLevel) continue;
            double d2 = horizDistSq(shipPos, player.getX(), player.getZ());
            if (d2 < bestSq) {
                bestSq = d2;
                best = player;
            }
        }
        return best;
    }

    // ───────────────────────────── Math + utility ─────────────────────────────

    private static BlockState blockState(ResourceLocation id) {
        Block b = BuiltInRegistries.BLOCK.get(id);
        if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) return null;
        return b.defaultBlockState();
    }

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

    /** Mutable per-airship state. */
    private static final class Pirate {
        final ServerLevel parentLevel;
        final SubLevel subLevel;
        final BlockPos airpadAnchor;
        final BlockPos slAnalogLever;
        final BlockPos slLeftClutchLever;
        final BlockPos slRightClutchLever;
        final BlockPos slCannonMount;
        /** Ship "forward" (cannon-points-this-way) in SubLevel-local coords. Depends on
         *  the structure rotation chosen by jigsaw at placement time. */
        final Vector3d shipLocalForward;

        State state = State.LIFTOFF;
        long stateEnteredTick;
        long lastDecisionTick = Long.MIN_VALUE / 2;
        long lastFireTick = Long.MIN_VALUE / 2;
        long lastTargetSeenTick = Long.MIN_VALUE / 2;

        // Last actuator commands — pure debug, doesn't drive anything else.
        boolean lastLeftEngaged = false;
        boolean lastRightEngaged = false;
        int lastThrottle = 0;
        double lastGoalX = Double.NaN;
        double lastGoalZ = Double.NaN;
        double lastHeadingErrDeg = 0;

        // Altitude steady-state tracking for LIFTOFF: the brain doesn't aim for a fixed
        // altitude; instead it watches for the ship's y to stop increasing, which is
        // when it's hit the physical ceiling for its envelope size.
        double lastSampledY = Double.NaN;
        int steadyTicks = 0;

        // Stuck-on-ground detection: tracks how long shipY has remained ~unchanged.
        // The world heightmap doesn't always agree with where the SubLevel actually
        // sits (mountains, trees, off-by-one XZ between SubLevel pose and rendered
        // blocks), so we fall back on "shipY hasn't moved" as ground evidence.
        double stuckCheckLastY = Double.NaN;
        long stuckCheckLastTick = Long.MIN_VALUE;
        int stuckTicks = 0;

        // Diagnostic: rate-limit aim and throttle logs to one per ~2s wall-clock so
        // we can correlate state-cycle behavior without spamming the log.
        long lastAimLogBucket = -1;
        long lastThrottleLogBucket = -1;

        Pirate(ServerLevel parentLevel, SubLevel subLevel, BlockPos airpadAnchor,
               BlockPos slAnalogLever, BlockPos slLeftClutchLever, BlockPos slRightClutchLever,
               BlockPos slCannonMount, Vector3d shipLocalForward) {
            this.parentLevel = parentLevel;
            this.subLevel = subLevel;
            this.airpadAnchor = airpadAnchor;
            this.slAnalogLever = slAnalogLever;
            this.slLeftClutchLever = slLeftClutchLever;
            this.slRightClutchLever = slRightClutchLever;
            this.slCannonMount = slCannonMount;
            this.shipLocalForward = shipLocalForward;
        }
    }
}
