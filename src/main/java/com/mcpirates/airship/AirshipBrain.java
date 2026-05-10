package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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

    /** Cruise altitude. Above any vanilla mountain; player has to climb to engage. */
    private static final double CRUISE_ALTITUDE = 200.0;
    /** Disengage if target leaves this radius (horizontal, from airpad anchor). */
    private static final double DISENGAGE_RANGE_SQ = (12 * 16) * (12 * 16);
    /** Considered "at airpad" (HOVER) when within this horizontal range. */
    private static final double HOVER_RADIUS_SQ = 16 * 16;
    /** Considered "at cruise" when within this much of {@link #CRUISE_ALTITUDE}. */
    private static final double CRUISE_TOLERANCE = 3.0;
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

    private static final ResourceLocation POWDER_CHARGE_ID =
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "powder_charge");
    private static final ResourceLocation SOLID_SHOT_ID =
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "solid_shot");

    private static final List<Pirate> SHIPS = new CopyOnWriteArrayList<>();
    private static final java.util.Set<UUID> AIMED_ONCE = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<UUID> FIRED_ONCE = ConcurrentHashMap.newKeySet();

    private static Field cachedAnalogStateField;
    private static Field cachedMountedContraptionField;

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
            applyMovement(p, target, shipPos);
            p.lastDecisionTick = now;
        } else if (now - p.lastDecisionTick >= DECISION_INTERVAL) {
            applyMovement(p, target, shipPos);
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
        return true;
    }

    // ───────────────────────────── State machine ─────────────────────────────

    private static State decideNextState(Pirate p, ServerPlayer target, Vector3d shipPos, long now) {
        boolean atCruise = Math.abs(shipPos.y - CRUISE_ALTITUDE) < CRUISE_TOLERANCE;
        boolean atAirpad = horizDistSq(shipPos, p.airpadAnchor) < HOVER_RADIUS_SQ;
        boolean targetLost = (now - p.lastTargetSeenTick) > LOST_TARGET_DEBOUNCE;
        boolean targetTooFar = target != null
                && horizDistSq(shipPos, target.position().x, target.position().z) > DISENGAGE_RANGE_SQ;

        return switch (p.state) {
            case LIFTOFF -> {
                if (!atCruise) yield State.LIFTOFF;
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

    private static void applyMovement(Pirate p, ServerPlayer target, Vector3d shipPos) {
        Level subLevelLevel = p.subLevel.getLevel();

        // Pick a horizontal goal, or null if we're stationary (LIFTOFF, HOVER).
        double goalX = Double.NaN, goalZ = Double.NaN;
        switch (p.state) {
            case PURSUE -> {
                if (target != null) {
                    goalX = target.getX();
                    goalZ = target.getZ();
                }
            }
            case RETURN -> {
                goalX = p.airpadAnchor.getX() + 0.5;
                goalZ = p.airpadAnchor.getZ() + 0.5;
            }
            case LIFTOFF, HOVER -> { /* stay put */ }
        }

        boolean leftEngaged = false, rightEngaged = false;
        if (!Double.isNaN(goalX)) {
            double dx = goalX - shipPos.x;
            double dz = goalZ - shipPos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > ARRIVAL_RADIUS_SQ) {
                double targetYaw = Math.atan2(-dx, dz);
                double currentYaw = currentYawRadians(p);
                double err = normalizeRadians(targetYaw - currentYaw);
                double absErrDeg = Math.abs(Math.toDegrees(err));

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

        // Throttle: bang-bang on altitude error.
        int throttle = chooseThrottle(p.state, shipPos.y);
        setAnalogLeverState(subLevelLevel, p.slAnalogLever, throttle);
    }

    private static int chooseThrottle(State state, double currentY) {
        if (state == State.LIFTOFF) {
            return 15;  // climb hard
        }
        double err = CRUISE_ALTITUDE - currentY;
        if (err > 5) return 14;
        if (err > 1) return 11;
        if (err < -5) return 0;
        if (err < -1) return 4;
        return 8;  // hover band
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
        Vector3d cannonLocal = new Vector3d(
                p.slCannonMount.getX() + 0.5,
                p.slCannonMount.getY() + 0.5,
                p.slCannonMount.getZ() + 0.5);
        Vector3d playerLocal = new Vector3d();
        p.subLevel.logicalPose().transformPositionInverse(
                new Vector3d(target.getX(), target.getEyeY(), target.getZ()), playerLocal);

        double dx = playerLocal.x - cannonLocal.x;
        double dy = playerLocal.y - cannonLocal.y;
        double dz = playerLocal.z - cannonLocal.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        mount.setYaw(yaw);
        mount.setPitch(pitch);
        if (AIMED_ONCE.add(p.subLevel.getUniqueId())) {
            MCPirates.LOGGER.info("pirate {} first aim: yaw={} pitch={}",
                    p.subLevel.getUniqueId(), yaw, pitch);
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
            SubLevel containing = dev.ryanhcode.sable.Sable.HELPER.getContaining(player);
            if (containing == null || containing == p.subLevel) continue;
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
