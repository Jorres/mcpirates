package com.mcpirates.airship;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.physics.AngleMath;
import com.mcpirates.airship.interfaces.MovementBehavior;
import com.mcpirates.airship.physics.PlateauTable;
import com.mcpirates.airship.physics.PlateauTable.LiftSetting;
import dev.eriksonn.aeronautics.index.AeroLiftingGasTypes;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import com.mcpirates.pirates.PirateBrain;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
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
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Below: both clutches engaged (straight). Above: pivot. Public so OrbitMovement can scale. */
    public static final double HEADING_TOLERANCE_DEG = 25.0;
    private static final int AIM_INTERVAL = 5;
    /** Short — ramship pivot rate (~10°/tick) requires fast clutch re-evaluation. */
    private static final int STEERING_DECISION_INTERVAL = 3;
    /** Hot-air balloons respond on second-scale, so high-freq lift updates are noise.
     *  Velocity damping ({@link #VELOCITY_LOOKAHEAD_TICKS}) covers transients. */
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

    /** Test seam — when non-null, all brains target this entity. {@link #findEnemyShip}
     *  reads the SubLevel from the override naturally, so a single override covers both. */
    public static volatile LivingEntity targetOverride = null;

    /** Immutable snapshot of the list; the {@link Airship} instances are still live. */
    public static List<Airship> ships() { return List.copyOf(SHIPS); }

    /** Drop every ship in {@code level} — GameTests need to clear JVM static state. */
    public static void unregisterAll(ServerLevel level) {
        SHIPS.removeIf(a -> a.parentLevel == level);
    }

    /** NAVIGATE toward {@code (x, z)}; altitude tracks cruiseRise like RETURN. */
    public static void navigateTo(Airship a, double x, double z) {
        a.navDestination = new Vector3d(x, 0.0, z);
        transitionState(a, State.NAVIGATE, a.parentLevel.getGameTime());
        a.controls.release(a);
    }

    private AirshipBrain() {}

    /** {@code MOORED}: assembled but parked, ground-combat-only; promoted to LIFTOFF on
     *  air arrival ({@link AirshipLiftoffTrigger#promoteMooredShipsForAirArrival}).<br>
     *  {@code NAVIGATE}: externally-driven XZ target; auto state machine never enters it. */
    public enum State { LIFTOFF, PURSUE, RETURN, HOVER, MOORED, NAVIGATE }

    /** Register a fresh ship: stamps initial state + flushes NBT. Used by the liftoff trigger. */
    public static void register(Airship a, State initialState) {
        a.state = initialState;
        // Without this, default 0 makes (now - stateEnteredTick) huge and LIFTOFF_MIN_TICKS no-ops.
        a.stateEnteredTick = a.parentLevel.getGameTime();
        SHIPS.add(a);
        a.persist();
        logRegistered(a, "registered");
    }

    /** Register a ship deserialised from NBT — preserves saved {@code state} +
     *  {@code stateEnteredTick}. Used by the rehydration path below. */
    public static void registerRehydrated(Airship a) {
        SHIPS.add(a);
        logRegistered(a, "rehydrated");
    }

    private static void logRegistered(Airship a, String verb) {
        MCPirates.LOGGER.info(
                "{} pirate airship: kind={} subLevel={} state={} anchor={} mounts={} fwd=({},{},{}) anchoredEntities={} cannoneers={}",
                verb, a.kind.name(), a.subLevel.getUniqueId(), a.state, a.airpadAnchor,
                a.slCannonMounts,
                a.shipLocalForward.x, a.shipLocalForward.y, a.shipLocalForward.z,
                a.anchoredEntities.size(), a.cannoneerByMount.size());
    }

    /** Single write path for {@code state} + {@code stateEnteredTick}; flushes NBT. */
    public static void transitionState(Airship a, State next, long now) {
        a.state = next;
        a.stateEnteredTick = now;
        a.persist();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Use per-level getGameTime (persists across server restarts). Every other
        // tick source in the brain (register, transitionState, navigateTo, persist)
        // uses getGameTime, and a saved world has gameTime != getTickCount on boot —
        // mixing the two makes ticksInState negative and breaks the LIFTOFF gate.
        for (Airship a : SHIPS) {
            if (!tickShip(a, a.parentLevel.getGameTime())) {
                SHIPS.remove(a);
            }
        }
    }

    /** Flush per-ship NBT so per-tick telemetry isn't lost on clean shutdown, then
     *  clear the JVM-static list. Without the clear, single-player world quits leave
     *  zombie Airships pointing at the destroyed ServerLevel; opening any later world
     *  (same JVM) starts with stale ships that {@link #unregisterAll}'s level-equality
     *  filter can't purge. {@link #onServerStarted} re-registers from NBT. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Airship a : SHIPS) {
            if (a.state == State.MOORED) continue;  // dormant — nothing transient worth saving
            a.persist();
        }
        SHIPS.clear();
        PENDING_REHYDRATE.clear();
    }

    // ───────────────────────────── Rehydration ─────────────────────────────
    // Re-register persisted SubLevels with the brain on restart / chunk reload. Reads
    // everything from userDataTag.mcpirates written by Airship.persist(); no world
    // rescan. Fresh assemblies register directly via AirshipLiftoffTrigger; the
    // duplicate-skip in tryRehydrate bails when the trigger beat the observer.

    /** Queued because {@code onSubLevelAdded} fires before {@code setUserDataTag};
     *  {@link #drainPendingRehydrates} retries once the tag is in place. */
    private static final Set<UUID> PENDING_REHYDRATE = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (SubLevel sl : container.getAllSubLevels()) {
                if (!sl.isRemoved()) tryRehydrate(level, sl);
            }
            container.addObserver(new SubLevelObserver() {
                @Override public void onSubLevelAdded(SubLevel subLevel) {
                    PENDING_REHYDRATE.add(subLevel.getUniqueId());
                }
                @Override public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
                    PENDING_REHYDRATE.remove(subLevel.getUniqueId());
                }
            });
        }
    }

    @SubscribeEvent
    public static void drainPendingRehydrates(ServerTickEvent.Post event) {
        if (PENDING_REHYDRATE.isEmpty()) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (UUID uuid : PENDING_REHYDRATE) {
                SubLevel sl = container.getSubLevel(uuid);
                if (sl == null || sl.isRemoved()) {
                    PENDING_REHYDRATE.remove(uuid);
                    continue;
                }
                if (!(sl instanceof ServerSubLevel ssl)) {
                    PENDING_REHYDRATE.remove(uuid);
                    continue;
                }
                if (ssl.getUserDataTag() == null) continue; // still loading — retry next tick
                PENDING_REHYDRATE.remove(uuid);
                tryRehydrate(level, sl);
            }
        }
    }

    /** Public for GameTests. Returns the number of ships registered. */
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
        for (Airship existing : SHIPS) {
            if (existing.subLevelId.equals(ssl.getUniqueId())) return false;
        }
        CompoundTag userTag = ssl.getUserDataTag();
        if (userTag == null || !userTag.contains(Airship.NBT_ROOT_KEY)) return false;
        Airship airship = Airship.readNbt(parentLevel, ssl, userTag.getCompound(Airship.NBT_ROOT_KEY));
        if (airship == null) {
            MCPirates.LOGGER.warn("rehydrate: failed to deserialize SubLevel {}", ssl.getUniqueId());
            return false;
        }
        registerRehydrated(airship);
        return true;
    }

    private static boolean tickShip(Airship a, long now) {
        // Re-acquire by UUID: Sable swaps the instance on chunk reload; cached refs go stale.
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
        // Crew-defeat: shut down and deregister. MOORED is exempt — its crew spawns later.
        if (a.state != State.MOORED && !a.isAnyCrewAlive()) {
            shutdownDerelict(a, subLevelLevel);
            MCPirates.LOGGER.info("ship {} ({}): crew defeated, brain deregistering",
                    a.subLevel.getUniqueId(), a.kind.name());
            return false;
        }
        if (a.state == State.MOORED) {
            return true;
        }
        // Sable's @Unique plotPosition isn't persisted; reapply on chunk-reload edges.
        reanchorEntities(a);
        Vector3d shipPos = a.subLevel.logicalPose().position();

        LivingEntity targetPlayer = findEnemyPlayerOnAirship(a, shipPos);
        SubLevel targetShip = findEnemyShip(a, targetPlayer);
        if (targetPlayer != null) {
            a.lastTargetSeenTick = now;
        }
        LivingEntity target = targetPlayer;

        // State machine needs both steady-ticks and initial-Y rise to disambiguate
        // "topped out" from "hasn't started rising".
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
            transitionState(a, desired, now);
            // Off→on edge refreshes Aeronautics' thrust contribution (props can visually
            // spin without applying thrust after reload until toggled).
            if (desired == State.PURSUE || desired == State.HOVER) {
                a.controls.release(a);
            }
            if (previous == State.PURSUE) {
                a.kind.movement().onExitPursue(a);
            }
            if (desired == State.PURSUE) {
                a.kind.movement().onEnterPursue(a, shipPos, targetPlayer, targetShip);
            }
            // Force-update both subsystems on state change.
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

    /** Per-state 3D goal; null = stationary. */
    private static MovementBehavior.Goal computeGoal(Airship a, Vector3d shipPos,
                                                     LivingEntity targetPlayer,
                                                     SubLevel targetShip, long now) {
        // Non-PURSUE altitudes are uniform across kinds — cruiseRise from the airpad.
        // Strategy-specific altitude only matters during PURSUE; that branch delegates.
        double cruiseY = a.airpadAnchor.getY() + a.kind.cruiseRise();
        return switch (a.state) {
            case PURSUE -> a.kind.movement().computeGoal(a, shipPos, targetPlayer, targetShip, now);
            case RETURN -> new MovementBehavior.Goal(
                    a.airpadAnchor.getX() + 0.5, cruiseY, a.airpadAnchor.getZ() + 0.5);
            case NAVIGATE -> a.navDestination == null ? null
                    : new MovementBehavior.Goal(a.navDestination.x, cruiseY, a.navDestination.z);
            case LIFTOFF, HOVER, MOORED -> null;
        };
    }

    /** Brain produces signed heading error; {@link com.mcpirates.airship.interfaces.ShipControls}
     *  translates it to clutch/propeller writes. */
    private static void applySteering(Airship a, LivingEntity targetPlayer, SubLevel targetShip,
                                      Vector3d shipPos) {
        MovementBehavior.Goal goal = computeGoal(a, shipPos, targetPlayer, targetShip,
                                                 a.parentLevel.getGameTime());
        double goalX = goal == null ? Double.NaN : goal.x();
        double goalY = goal == null ? Double.NaN : goal.y();
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
        a.lastGoalX = goalX;
        a.lastGoalY = goalY;
        a.lastGoalZ = goalZ;
        a.lastHeadingErrDeg = headingErrDeg;
        // Orbit uses this for stuck-direction-flip; other movements are no-ops.
        if (a.state == State.PURSUE && !Double.isNaN(goalX)) {
            a.kind.movement().onPursueDecision(a, headingErrDeg);
        }
    }

    /** Plateau-pick (lever, volume) and write to every throttle lever + burner. */
    private static void applyLift(Airship a, LivingEntity target, Vector3d shipPos) {
        a.balloonCapacity = a.lift.queryBalloonCapacity(a);
        int maxGroundAhead = maxGroundAhead(a, shipPos);
        LiftSetting lift = chooseLiftSetting(a, a.state, shipPos, target, maxGroundAhead);
        if (lift != null) {
            a.lift.apply(a, lift);
        }
        long bucket = System.currentTimeMillis() / 2000;
        if (bucket != a.lastThrottleLogBucket) {
            a.lastThrottleLogBucket = bucket;
            // Mirror chooseLiftSetting's targetY formula so the log shows what was actually
            // committed (pre velocity-bias). Pursue Y comes from the movement strategy's
            // goal; other states use cruise altitude.
            double cruiseY = a.airpadAnchor.getY() + a.kind.cruiseRise();
            double floor = maxGroundAhead + a.kind.minAltAboveGround() + 2.0;
            double targetY = (a.state == State.PURSUE && !Double.isNaN(a.lastGoalY))
                    ? Math.max(a.lastGoalY, floor)
                    : Math.max(cruiseY, floor);
            ShipTelemetry.snapshot(a, "throttle", lift, targetY);
        }
    }


    /** Velocity-damping horizon for plateau pick. Picker sees {@code targetY - K·v.y},
     *  biasing toward a row that brakes vertical momentum. Plateau table is
     *  steady-state only and there's no fast brake in the plant, so without this
     *  the controller is position-only and oscillates around every altitude change.
     *  {@code K=10} ≈ 23-block bias for worst LIFTOFF→PURSUE v.y, then relaxes as v.y bleeds off. */
    public static final double VELOCITY_LOOKAHEAD_TICKS = 10.0;

    private static final int[] GROUND_LOOKAHEAD_SAMPLES = {0, 8, 16, 24, 32};

    /** Max heightmap across the ship's XZ + forward look-ahead samples. */
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

    /** Pick the row whose equilibrium altitude is closest to target. */
    private static LiftSetting chooseLiftSetting(
            Airship a, State state, Vector3d shipPos, LivingEntity target, int maxGround) {
        PlateauTable table = ensurePlateauTable(a, shipPos);
        // Cold-start breaker: plateau needs balloonCap, balloonCap needs balloon attached,
        // attachment needs canOutputGas, which needs lever > 0. NBT bakes lever=0, so we
        // must write any positive setting to bootstrap the loop on a fresh assembly.
        if (table == null || table.size() == 0) {
            return new LiftSetting(/*lever=*/10, PlateauTable.BURNER_MIN_VOLUME);
        }

        double floor = maxGround + a.kind.minAltAboveGround() + 2.0;
        double cruiseY = a.airpadAnchor.getY() + a.kind.cruiseRise();
        double targetY = switch (state) {
            case LIFTOFF, RETURN, HOVER, NAVIGATE -> Math.max(cruiseY, floor);
            // PURSUE altitude comes from the movement strategy's 3D goal (set by
            // applySteering). Falls back to cruiseY if the strategy returned no goal
            // (e.g. target lost between steering and lift ticks).
            case PURSUE -> Double.isNaN(a.lastGoalY)
                    ? Math.max(cruiseY, floor)
                    : Math.max(a.lastGoalY, floor);
            case MOORED -> throw new IllegalStateException("chooseLiftSetting unreachable for MOORED");
        };
        // Conditional velocity damping: only apply when extrapolated v.y overshoots target.
        // Unconditional would choke LIFTOFF (large +v.y, far below target, no overshoot risk).
        double vy = ShipTelemetry.velocity(a).y;
        double projectedY = shipPos.y + VELOCITY_LOOKAHEAD_TICKS * vy;
        boolean overshootRising  = vy > 0 && projectedY > targetY;
        boolean overshootSinking = vy < 0 && projectedY < targetY;
        double biasedTargetY = (overshootRising || overshootSinking)
                ? Math.max(targetY - VELOCITY_LOOKAHEAD_TICKS * vy, floor)
                : targetY;
        PlateauTable.Row picked = table.pickClosest(biasedTargetY);
        a.lastBiasedTargetY = biasedTargetY;
        a.lastPickedEquilibriumY = picked.equilibriumY();
        return picked.toLiftSetting();
    }

    /** Rebuilt only when balloon capacity changes. Mass is sampled once. */
    private static PlateauTable ensurePlateauTable(Airship a, Vector3d shipPos) {
        int cap = a.balloonCapacity;
        if (cap <= 0) return null;
        if (a.plateauTable != null && a.plateauTableCapacity == cap) return a.plateauTable;
        if (!(a.subLevel instanceof ServerSubLevel ssl)) return null;
        MassData md = ssl.getMassTracker();
        if (md == null || md.isInvalid()) return null;
        double mass = md.getMass();
        double liftStrength = AeroLiftingGasTypes.DEFAULT_GAS.get().getLiftStrength();
        int nBurners = Math.max(1, a.lift.burnerCount());
        int vMaxPerBurner = Math.max(PlateauTable.BURNER_MIN_VOLUME,
                Math.min(PlateauTable.BURNER_MAX_VOLUME, cap / nBurners));
        a.plateauTable = PlateauTable.build(
                a.parentLevel, mass, liftStrength, nBurners, vMaxPerBurner, shipPos.x, shipPos.z);
        a.plateauTableCapacity = cap;
        ShipTelemetry.snapshot(a, "plateau-built");
        return a.plateauTable;
    }

    /** Release propulsion and strip the {@code "mcpirates"} stamp so the rehydrator leaves
     *  the SubLevel alone on restart. Throttle/burner state is intentionally untouched. */
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

        String liftStr = a.lift == null ? "" : a.lift.describe(a);
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
                        "pos=(%.0f,%.1f,%.0f) %s balloonVol=%s goal=%s yaw_err=%.0f° %s%s%s",
                        shipPos.x, shipPos.y, shipPos.z,
                        liftStr,
                        ShipTelemetry.balloonVol(a.balloonCapacity),
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
        // Distance-only — on-SubLevel predicate is enforced upstream by the trigger.
        // Players carrying TEST_OBSERVER_TAG are skipped so debugging on a live test
        // server doesn't get the human player targeted as the closest enemy.
        ServerPlayer best = null;
        double bestSq = AirshipStateMachine.DISENGAGE_RANGE_SQ;
        for (ServerPlayer player : a.parentLevel.players()) {
            if (player.getTags().contains(MCPDataKeys.TEST_OBSERVER_TAG)) continue;
            double d2 = horizDistSq(shipPos, player.getX(), player.getZ());
            if (d2 < bestSq) {
                bestSq = d2;
                best = player;
            }
        }
        return best;
    }

    /** SubLevel the target is riding (any Sable ship), or null if on foot / on self. */
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
