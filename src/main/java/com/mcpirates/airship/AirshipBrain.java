package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.airship.kind.AngleMath;
import com.mcpirates.airship.kind.ClutchLevers;
import com.mcpirates.airship.kind.HotAirBurners;
import com.mcpirates.airship.kind.LiftMath;
import com.mcpirates.airship.kind.LiftMath.LiftSetting;
import com.mcpirates.airship.kind.MovementBehavior;
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

/** Per-tick flight controller. State machine (LIFTOFF → PURSUE → RETURN → HOVER) +
 *  tank-steer via clutches + altitude via {@link PlateauTable}.
 *
 *  <p>Clutch lever {@code powered=true} <strong>disengages</strong> — brain writes
 *  {@code !engaged}. Tank-steer: both engaged → straight; one only → pivot the OTHER way. */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipBrain {

    /** |Δy/tick| below this counts as "not climbing" — sized over Sable's physics jitter. */
    private static final double LIFTOFF_STEADY_DELTA = 0.05;
    private static final double ARRIVAL_RADIUS_SQ = 12 * 12;
    /** Yaw tolerance (degrees) below which the brain engages both clutches to drive
     *  straight at the goal. Above it: one clutch only, pivoting the other way.
     *  Public so {@link com.mcpirates.airship.kind.OrbitMovement} can scale it for
     *  stuck-detect. */
    public static final double HEADING_TOLERANCE_DEG = 25.0;
    private static final int AIM_INTERVAL = 5;
    /** How often the brain re-decides steering (clutches, propeller reversal).
     *  Short so the controller can catch fast yaw rotation before the ship
     *  overshoots target heading — ramship rotation can reach ~10°/tick under
     *  counter-rotation, and the previous 10-tick interval was wider than the
     *  ship's full pivot-through-target sweep. */
    private static final int STEERING_DECISION_INTERVAL = 3;
    /** How often the brain re-decides lift (plateau-table pick → throttle +
     *  burner volume). Hot-air balloons are slow plants (multi-second response
     *  to a thrust change), so high-frequency lift updates would just be noise.
     *  The plateau pick already includes velocity damping
     *  ({@link #VELOCITY_LOOKAHEAD_TICKS}) for transient handling. */
    private static final int LIFT_DECISION_INTERVAL = 10;

    private static final boolean DEBUG_OVERLAY = true;
    private static final int OVERLAY_ACTIONBAR_INTERVAL = 10;
    private static final double OVERLAY_RENDER_RADIUS = 200.0;

    private static final boolean CANNON_AIM_ENABLED = true;

    /** {@code /mcpirates fire on|off}. Off by default. */
    public static volatile boolean CANNON_FIRE_ENABLED = false;

    public static boolean setFireEnabled(boolean enabled) {
        CANNON_FIRE_ENABLED = enabled;
        return enabled;
    }

    private static final List<Airship> SHIPS = new CopyOnWriteArrayList<>();

    /** Test seam — when non-null, every brain queries this entity instead of scanning
     *  {@code parentLevel.players()} in {@link #findEnemyPlayerOnAirship}. The brain's
     *  {@link #findEnemyShip} resolves the SubLevel naturally from the entity's
     *  {@code Sable.HELPER.getContaining(entity)}, so tests don't need a second override —
     *  pointing at an entity that rides a SubLevel (e.g. the victim's captain) gives both
     *  pieces of information in one. */
    public static volatile LivingEntity targetOverride = null;

    /** Immutable snapshot of the list; the {@link Airship} instances are still live. */
    public static List<Airship> ships() { return List.copyOf(SHIPS); }

    /** Drop every ship in {@code level} — GameTests need to clear JVM static state. */
    public static void unregisterAll(ServerLevel level) {
        SHIPS.removeIf(a -> a.parentLevel == level);
    }

    /** Drop a single ship from the brain registry without touching Sable / SubLevel.
     *  Used by GameTest scaffolding that activates a ship via the production path to
     *  get a SubLevel, then demotes it to a passive target by stripping brain control. */
    public static void unregister(Airship a) {
        SHIPS.remove(a);
    }

    /** Send {@code a} into {@link State#NAVIGATE} steering toward {@code (x, z)}. Y is
     *  ignored — altitude tracks cruiseRise like RETURN. Releases steering so the
     *  off→on edge re-engages Aeronautics' thrust contribution. */
    public static void navigateTo(Airship a, double x, double z) {
        a.navDestination = new Vector3d(x, 0.0, z);
        a.state = State.NAVIGATE;
        a.stateEnteredTick = a.parentLevel.getGameTime();
        a.controls.release(a);
    }

    private AirshipBrain() {}

    /** {@code MOORED} = ship is fully assembled into a SubLevel but parked on the airpad
     *  with no deck crew. Used when only the ground-combat module has fired (player on
     *  foot). The brain still ticks the SubLevel handle (so chunk reloads re-acquire it
     *  cleanly) but skips all movement / control writes; promotion to {@link #LIFTOFF}
     *  happens when a player arrives by airship — see
     *  {@link AirshipLiftoffTrigger#promoteMooredShipsForAirArrival}. */
    /** {@code NAVIGATE} = brain steers toward {@link Airship#navDestination} (a fixed XZ
     *  point) and idles on arrival. Externally entered — the auto state machine never
     *  enters or leaves it. Used by tests as a deterministic ship controller and (future)
     *  by scripted-patrol commands. */
    public enum State { LIFTOFF, PURSUE, RETURN, HOVER, MOORED, NAVIGATE }

    public static void register(
            ServerLevel parentLevel,
            SubLevel subLevel,
            BlockPos airpadAnchor,
            AirshipKind kind,
            List<BlockPos> slThrottleLevers,
            List<BlockPos> slBurnerPositions,
            BlockPos slLeftClutchLever,
            BlockPos slRightClutchLever,
            BlockPos slPrimaryAnchor,
            net.minecraft.world.level.block.Rotation rotation,
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
        // Without this, default 0 makes (now - stateEnteredTick) huge and LIFTOFF_MIN_TICKS no-ops.
        a.stateEnteredTick = parentLevel.getGameTime();
        // Steering controls: kind owns its hardware deltas + factory, hands us
        // back a fully-bound actuator. Brain never touches block positions
        // beyond what's on Airship itself.
        a.controls = kind.makeControls(a, slPrimaryAnchor, rotation);
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
        // Re-acquire by UUID each tick — Sable rehydrates a fresh object under the same
        // UUID after chunk reload; a cached ref becomes a frozen logicalPose() snapshot.
        SubLevelContainer container = SubLevelContainer.getContainer(a.parentLevel);
        if (container == null) {
            return false; // dimension teardown
        }
        SubLevel live = container.getSubLevel(a.subLevelId);
        if (live == null) {
            if (a.wasSubLevelLoaded) {
                MCPirates.LOGGER.info("ship {} ({}) SubLevel unloaded (holding-chunk)",
                        a.subLevelId, a.kind.name());
                a.wasSubLevelLoaded = false;
            }
            // Persisted to disk, not yet rehydrated — stay registered.
            return true;
        }
        if (!a.wasSubLevelLoaded) {
            MCPirates.LOGGER.info("ship {} ({}) SubLevel reloaded", a.subLevelId, a.kind.name());
            a.wasSubLevelLoaded = true;
        }
        if (live != a.subLevel) {
            a.subLevel = live;
        }
        Level subLevelLevel = a.subLevel.getLevel();
        if (subLevelLevel == null) {
            return true;
        }
        // Crew-defeat: shut the wreck down (clutches off, strip stamp) and deregister.
        // MOORED is exempt — deck crew spawns later on LIFTOFF promotion.
        if (a.state != State.MOORED && !a.isAnyCrewAlive()) {
            shutdownDerelict(a, subLevelLevel);
            MCPirates.LOGGER.info("ship {} ({}): crew defeated, brain deregistering",
                    a.subLevel.getUniqueId(), a.kind.name());
            return false;
        }
        // MOORED: parked on the airpad with no deck crew. Stay registered for the
        // eventual air-arrival promotion but skip targeting + movement writes.
        if (a.state == State.MOORED) {
            return true;
        }
        // Sable's @Unique plotPosition field isn't persisted; reapply on chunk-reload edges.
        reanchorEntities(a);
        Vector3d shipPos = a.subLevel.logicalPose().position();

        LivingEntity targetPlayer = findEnemyPlayerOnAirship(a, shipPos);
        SubLevel targetShip = findEnemyShip(a, targetPlayer);
        if (targetPlayer != null) {
            a.lastTargetSeenTick = now;
        }
        // Local alias for the rest of this method — most paths just need "is anything alive
        // out there for me to chase / shoot at?". State machine + combat still consume the
        // player; ram movement (and future ship-aware combat) consume the ship.
        LivingEntity target = targetPlayer;

        // State machine distinguishes "topped out" from "hasn't started rising" via
        // steady ticks + initial-Y rise (each alone is ambiguous).
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
                    "ship {} ({}): state {} → {}",
                    a.subLevel.getUniqueId(), a.kind.name(), a.state, desired);
            MCPirates.LOGGER.info(
                    "  └ target={} y={} distAirpad={}",
                    target == null ? "none" : target.getName().getString(),
                    String.format("%.1f", shipPos.y),
                    String.format("%.1f", Math.sqrt(horizDistSq(shipPos, a.airpadAnchor))));
            State previous = a.state;
            a.state = desired;
            a.stateEnteredTick = now;
            // Disengage on entry. The off→on edge refreshes Aeronautics' thrust contribution —
            // after a chunk reload props can visually spin without applying thrust until toggled.
            if (desired == State.PURSUE || desired == State.HOVER) {
                a.controls.release(a);
            }
            if (previous == State.PURSUE) {
                a.kind.movement().onExitPursue(a);
            }
            if (desired == State.PURSUE) {
                a.kind.movement().onEnterPursue(a, shipPos, targetPlayer, targetShip);
            }
            // Fresh state — force both subsystems to update immediately so
            // we don't carry the previous state's clutch/lift writes for up
            // to LIFT_DECISION_INTERVAL ticks.
            applySteering(a, targetPlayer, targetShip, shipPos);
            applyLift(a, target, shipPos);
            a.lastSteeringTick = now;
            a.lastLiftTick = now;
        } else {
            if (now - a.lastSteeringTick >= STEERING_DECISION_INTERVAL) {
                applySteering(a, targetPlayer, targetShip, shipPos);
                a.lastSteeringTick = now;
            }
            if (now - a.lastLiftTick >= LIFT_DECISION_INTERVAL) {
                applyLift(a, target, shipPos);
                a.lastLiftTick = now;
            }
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

    /** Compute the per-state XZ goal. {@code Double.NaN} = stationary (no goal).
     *  Shared by steering and (indirectly via {@link #applySteering}) the orbit
     *  decision hook. */
    private static MovementBehavior.Goal computeGoal(Airship a, Vector3d shipPos,
                                                     LivingEntity targetPlayer,
                                                     SubLevel targetShip, long now) {
        return switch (a.state) {
            case PURSUE -> a.kind.movement().computeGoal(a, shipPos, targetPlayer, targetShip, now);
            case RETURN -> new MovementBehavior.Goal(
                    a.airpadAnchor.getX() + 0.5, a.airpadAnchor.getZ() + 0.5);
            case NAVIGATE -> a.navDestination == null ? null
                    : new MovementBehavior.Goal(a.navDestination.x, a.navDestination.z);
            case LIFTOFF, HOVER, MOORED -> null;
        };
    }

    /** Steering pass — brain only knows <em>direction</em> (signed heading error).
     *  The kind's {@link com.mcpirates.airship.kind.ShipControls} translates that
     *  into clutch / propeller writes. No hardware blocks are touched at this
     *  level. Runs on the fast {@link #STEERING_DECISION_INTERVAL} cadence. */
    private static void applySteering(Airship a, LivingEntity targetPlayer, SubLevel targetShip,
                                      Vector3d shipPos) {
        MovementBehavior.Goal goal = computeGoal(a, shipPos, targetPlayer, targetShip,
                                                 a.parentLevel.getGameTime());
        double goalX = goal == null ? Double.NaN : goal.x();
        double goalZ = goal == null ? Double.NaN : goal.z();
        double headingErrDeg = 0;
        if (!Double.isNaN(goalX)) {
            double dx = goalX - shipPos.x;
            double dz = goalZ - shipPos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > ARRIVAL_RADIUS_SQ) {
                double targetYaw = Math.atan2(-dx, dz);
                double currentYaw = a.yawRadians();
                double err = AngleMath.normalizeRadians(targetYaw - currentYaw);
                headingErrDeg = Math.toDegrees(err);
                a.controls.applySteering(a, err);
            } else {
                a.controls.release(a);
            }
        } else {
            a.controls.release(a);
        }
        // Orbit-math intermediates for the overlay; clutch/throttle/burner state
        // lives in the SubLevel block states and is read back from there when needed.
        a.lastGoalX = goalX;
        a.lastGoalZ = goalZ;
        a.lastHeadingErrDeg = headingErrDeg;
        // Per-decision hook: orbit uses it for stuck-direction-flip; ram is a no-op.
        if (a.state == State.PURSUE && !Double.isNaN(goalX)) {
            a.kind.movement().onPursueDecision(a, headingErrDeg);
        }
    }

    /** Lift pass — plateau-pick the (lever, volume) setting and write it to every
     *  throttle lever + burner. Runs on the slow {@link #LIFT_DECISION_INTERVAL}
     *  cadence; hot-air balloons can't respond to high-frequency thrust changes
     *  anyway, and the plateau pick already absorbs transient velocity via
     *  {@link #VELOCITY_LOOKAHEAD_TICKS}. */
    private static void applyLift(Airship a, LivingEntity target, Vector3d shipPos) {
        Level subLevelLevel = a.subLevel.getLevel();
        int balloonCap = -1;
        for (BlockPos burner : a.slBurnerPositions) {
            int c = HotAirBurners.queryBalloonCapacity(subLevelLevel, burner);
            if (c > 0) { balloonCap = c; break; }
        }
        a.balloonCapacity = balloonCap;
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
                    ? Math.max(target.getEyeY() + a.kind.pursueAltOffset(), maxGroundAhead + a.kind.minAltAboveGround() + 2.0)
                    : cruiseY;
            ShipLog.snapshot(a, "throttle", lift, targetY);
        }
    }


    // The ground-clearance safety floor is per-kind — see
    // {@link com.mcpirates.airship.kind.AirshipKind#minAltAboveGround()}.
    /** Look-ahead horizon (ticks) for velocity-damped plateau pick. The picker
     *  receives {@code targetY - K·v.y} instead of {@code targetY}, biasing
     *  toward a row that brakes current vertical momentum.
     *
     *  <p>The plateau table itself encodes only steady-state equilibrium — at the
     *  picked altitude buoyancy = weight, so net force is zero. A ship moving
     *  through equilibrium with non-zero {@code v.y} therefore overshoots: there
     *  is no fast brake in the hot-air-balloon plant, only weak Aeronautics drag.
     *  Without this bias the controller is position-only and the ship oscillates
     *  around (or sails past) every target altitude change.
     *
     *  <p>{@code K=10} chosen so that the worst observed LIFTOFF→PURSUE
     *  transition (v.y≈+2.3 b/tick) maps to roughly a 23-block bias — enough to
     *  pick a sink-producing row well before the ship arrives at equilibrium.
     *  As {@code v.y} bleeds off the bias shrinks and the target relaxes back to
     *  the real value. Symmetric on descent: {@code v.y < 0} biases target
     *  upward, so the picker holds a *higher* equilibrium and the ship's sink
     *  brakes before it sails past from below. */
    public static final double VELOCITY_LOOKAHEAD_TICKS = 10.0;

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
     *  HOVER / RETURN aim for the kind's cruise altitude; PURSUE aims for the player. */
    private static LiftSetting chooseLiftSetting(
            Airship a, State state, Vector3d shipPos, LivingEntity target, int maxGround) {
        PlateauTable table = ensurePlateauTable(a, shipPos);
        // Chicken-and-egg breaker. Plateau table requires balloonCap > 0 (the rebuild
        // condition in ensurePlateauTable), balloonCap requires a balloon attached to the
        // burner, balloon attachment requires Aeronautics' canOutputGas() == true, and
        // canOutputGas() requires lever > 0. If we returned null here (or wrote nothing),
        // the burner never ignites on a fresh assembly because the structure NBT bakes
        // lever=0, so the loop never closes and the ship just sits at the airpad with no
        // lift. Writing any positive (lever, vol) breaks the cycle: burner outputs gas,
        // balloon attaches within a few ticks, ensurePlateauTable starts succeeding, and
        // the next decision tick replaces this with the real plateau pick.
        if (table == null || table.size() == 0) {
            return new LiftSetting(/*lever=*/10, LiftMath.BURNER_MIN_VOLUME);
        }

        double floor = maxGround + a.kind.minAltAboveGround() + 2.0;
        double cruiseY = a.airpadAnchor.getY() + a.kind.cruiseRise();
        double targetY = switch (state) {
            case LIFTOFF, RETURN, HOVER, NAVIGATE -> Math.max(cruiseY, floor);
            case PURSUE -> target == null
                    ? Math.max(cruiseY, floor)
                    : Math.max(target.getEyeY() + a.kind.pursueAltOffset(), floor);
            // tickShip short-circuits for MOORED before chooseLiftSetting is called;
            // this branch only exists to satisfy the compiler's exhaustiveness check.
            case MOORED -> throw new IllegalStateException("chooseLiftSetting unreachable for MOORED");
        };
        // Velocity-damped pick — see VELOCITY_LOOKAHEAD_TICKS for rationale.
        // The bias is *conditional*: only apply when the ship would overshoot
        // by extrapolating its current velocity {@code K} ticks ahead. Otherwise
        // we'd pull the equilibrium downward during LIFTOFF (ship far below
        // target with positive v.y, no overshoot risk) and choke the climb.
        // Floor the biased target so the brake can't drag us below the kind's
        // configured ground clearance.
        double vy = ShipLog.velocity(a).y;
        double projectedY = shipPos.y + VELOCITY_LOOKAHEAD_TICKS * vy;
        boolean overshootRising  = vy > 0 && projectedY > targetY;
        boolean overshootSinking = vy < 0 && projectedY < targetY;
        double biasedTargetY = (overshootRising || overshootSinking)
                ? Math.max(targetY - VELOCITY_LOOKAHEAD_TICKS * vy, floor)
                : targetY;
        PlateauTable.Row picked = table.pickClosest(biasedTargetY);
        // Stash for the throttle log so it reflects what was actually committed,
        // not a parallel recomputation that can diverge from the floor clamp.
        a.lastBiasedTargetY = biasedTargetY;
        a.lastPickedEquilibriumY = picked.equilibriumY();
        return picked.toLiftSetting();
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
        // mass/balloonVol unit trap (see ShipLog header) and the per-ship state line
        // both live in ShipLog.snapshot — same emitter as the per-tick throttle log,
        // just a different event tag.
        ShipLog.snapshot(a, "plateau-built");
        return a.plateauTable;
    }

    /** One-shot shutdown when the crew is wiped:
     *  <ol>
     *    <li>Release all propulsion via the kind's controls.</li>
     *    <li>Strip the {@code "mcpirates"} compound from the SubLevel's user-data tag —
     *        rehydrator looks for that stamp on startup; without it the SubLevel
     *        survives as a vanilla Sable contraption and we never touch it again.</li>
     *  </ol>
     *  Throttle / burner state is left as-is — dead crew can't change it, and the
     *  wreck drifts on whatever lift it had until balloon drains naturally. */
    private static void shutdownDerelict(Airship a, Level subLevelLevel) {
        a.controls.release(a);
        if (a.subLevel instanceof ServerSubLevel ssl) {
            net.minecraft.nbt.CompoundTag tag = ssl.getUserDataTag();
            if (tag != null && tag.contains("mcpirates")) {
                tag.remove("mcpirates");
                ssl.setUserDataTag(tag);
            }
        }
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
        String orbitStr = a.state == State.PURSUE ? a.kind.movement().debugOverlay(a) : "";

        ChatFormatting stateColor = switch (a.state) {
            case LIFTOFF  -> ChatFormatting.YELLOW;
            case PURSUE   -> ChatFormatting.RED;
            case RETURN   -> ChatFormatting.AQUA;
            case HOVER    -> ChatFormatting.GREEN;
            case MOORED   -> ChatFormatting.GRAY;
            case NAVIGATE -> ChatFormatting.GOLD;
        };

        Component msg = Component.empty()
                .append(Component.literal("[" + a.state.name() + ":" + a.kind.name() + "] ").withStyle(stateColor))
                .append(Component.literal(String.format(
                        "pos=(%.0f,%.1f,%.0f) thr=%d vol=%dm³ balloonVol=%s %s goal=%s yaw_err=%.0f° %s%s%s",
                        shipPos.x, shipPos.y, shipPos.z,
                        throttle,
                        burnerVolume,
                        ShipLog.balloonVol(a.balloonCapacity),
                        engines,
                        goalStr,
                        a.lastHeadingErrDeg,
                        targetStr,
                        liftoffStr,
                        orbitStr
                )));
        closest.displayClientMessage(msg, /*actionBar=*/true);
    }

    // ───────────────────────────── Targeting ─────────────────────────────

    private static LivingEntity findEnemyPlayerOnAirship(Airship a, Vector3d shipPos) {
        LivingEntity override = targetOverride;
        if (override != null) {
            return override.isAlive() ? override : null;
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

    /** Resolve the SubLevel (any Sable-tracked ship — mcpirates or vanilla Aeronautics)
     *  the target is riding. Returns null for player-on-foot or for self. */
    private static SubLevel findEnemyShip(Airship self, LivingEntity targetPlayer) {
        if (targetPlayer == null) return null;
        SubLevel containing = dev.ryanhcode.sable.Sable.HELPER.getContaining(targetPlayer);
        if (containing == null || containing == self.subLevel) return null;
        return containing;
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
