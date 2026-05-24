package com.mcpirates.airship.ships.firecracker;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.hardware.ClutchLevers;
import com.mcpirates.airship.interfaces.CombatBehavior;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.torsion_spring.TorsionSpringBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Firecracker combat: one degree of freedom (pitch only, no yaw).
 *
 * <p>Geometry: a four-dispenser bank rides a {@code simulated:swivel_bearing} that swings
 * around the ship's forward axis. The {@code simulated:torsion_spring} drives the bearing
 * to a commanded angle in [1, 360], where:
 * <ul>
 *   <li>{@code angle=1} → dispensers point starboard</li>
 *   <li>{@code angle=90} → straight up (into the envelope — forbidden)</li>
 *   <li>{@code angle=180} → port</li>
 *   <li>{@code angle=270} → straight down</li>
 * </ul>
 *
 * <p>The cannon CANNOT track yaw — that's the ship's job (orbit movement supplies the
 * lateral component). Combat only adjusts pitch. If the target sits too close to the
 * ship's forward/backward axis (i.e., not really "to the side"), no spring angle hits
 * it — we abort and wait for the orbit to bring the target into the swivel plane.
 *
 * <p>Spring power chain: {@code simulated:portable_engine} (auto-lit at liftoff) →
 * {@code create:clutch} → spring. Standard {@link ClutchLevers} convention applies:
 * <ul>
 *   <li>lever {@code powered=false} → clutch engaged → kinetic flows → spring rotates
 *       toward the {@code angleInput} setpoint</li>
 *   <li>lever {@code powered=true} → clutch disengaged → spring returns to rest at 0</li>
 * </ul>
 * Combat engages the lever ONCE (idempotent) and leaves it engaged for the ship's
 * lifetime. Every toggle whips the spring through huge angles as the clutch engages/
 * disengages, so we never disengage — out-of-range we simply stop updating the setpoint
 * and skip firing. The spring naturally holds its last commanded angle.
 *
 * <p>Safety: angles in [20, 160] point into the envelope. Firing there shoots our own
 * balloon. Both the aim setpoint and the fire-time read of the live angle are gated.
 *
 * <p>Range gate: combat only activates when target is within {@code orbitRadius() + 20}
 * blocks. The orbit will pull us to ~25m from the target; we engage from 45m to give
 * the brain a bit of slack for closing in.
 *
 * <p>Cadence: {@link #aim} runs every {@code AIM_INTERVAL=5} ticks; {@link #fire} every
 * tick after the {@code fireIntervalTicks()=200} (10s) cooldown elapses, until it
 * returns true.
 */
public final class FirecrackerCombat implements CombatBehavior {

    /** Cone half-angle (20° "forgiving" tier) used at fire-time against the live
     *  cannon-vs-target geometry. Same value gates the perpendicular-to-swivel-axis
     *  check in {@link #computeIdealAngleDeg}. */
    private static final double CONE_HALF_ANGLE_RAD = Math.toRadians(20);
    private static final double COS_CONE = Math.cos(CONE_HALF_ANGLE_RAD);
    private static final double SIN_CONE = Math.sin(CONE_HALF_ANGLE_RAD);

    /** Forbidden pitch zone: pointing into the balloon. Both bounds are exclusive — the
     *  edges {@code 20°} and {@code 160°} are still considered safe (just-grazing the
     *  envelope at horizontal-ish angles). */
    private static final int FORBIDDEN_LO_DEG = 20;
    private static final int FORBIDDEN_HI_DEG = 160;

    /** ~2 second cooldown between salvos. Brain enforces this. Each successful fire
     *  launches one wind charge from EACH of the 4 dispensers in the cluster. */
    private static final int FIRE_INTERVAL_TICKS = 40;

    /** Wind charge initial velocity (m/tick). 1.5 ≈ 30 m/s — comparable to a player throw. */
    private static final double WIND_CHARGE_SPEED = 1.5;

    /** Combat-engagement padding past the orbit radius. Together with
     *  {@link com.mcpirates.airship.interfaces.AirshipKind#orbitRadius()} this defines
     *  the maximum horizontal range at which we'll engage the spring. */
    private static final double RANGE_PAD_BLOCKS = 40.0;

    /** Throttle for debug logging so we don't spam every aim()/fire() call. Set to 0 to
     *  log everything; 20 (1× per second) is enough to watch convergence. */
    private static long lastLogTick = 0;

    /** NBT-frame, lever-relative. Lever is the inner cabin wall-lever at NBT (3,2,14).
     *  Clutch (the actuator) is one east at NBT (4,2,14). */
    public static final BlockPos SPECIAL_LEVER_LEVER_REL  = new BlockPos(-1, -2, +4);
    public static final BlockPos SPECIAL_CLUTCH_LEVER_REL = new BlockPos( 0, -2, +4);

    @Override public int fireIntervalTicks() { return FIRE_INTERVAL_TICKS; }

    @Override
    public void aim(Airship ship, LivingEntity target) {
        // Engage the spring's clutch ONCE — idempotent (setPowered short-circuits when
        // the lever is already at the requested state). Never disengaged. Toggling whips
        // the spring through huge angles as kinetic flow stops and restarts.
        // Standard ClutchLevers convention: powered=false ⇒ clutch engaged.
        BlockPos specialLever = specialLeverPos(ship);
        if (specialLever != null) {
            ClutchLevers.setPowered(ship.subLevel.getLevel(), specialLever, false);
        }

        // Range is a FIRING gate only — we don't try to aim when we couldn't shoot.
        // Leaves the spring at its last commanded setpoint instead of fighting it.
        if (!targetInRange(ship, target)) return;

        TorsionSpringBlockEntity spring = scanForSpring(ship);
        if (spring == null) return;

        Vec3 cannonCenter = resolveCannonCenter(ship);
        if (cannonCenter == null) return;

        Double ideal = computeIdealAngleDeg(ship, target, cannonCenter);
        if (ideal == null) return;
        if (ideal >= FORBIDDEN_LO_DEG && ideal <= FORBIDDEN_HI_DEG) return;

        int target360 = (int) Math.round(ideal);
        if (target360 < 1) target360 += 360;
        if (target360 > 360) target360 -= 360;
        spring.angleInput.setValue(target360);

        long tick = ship.parentLevel.getGameTime();
        if (tick - lastLogTick >= 20) {
            lastLogTick = tick;
            double curAngle = spring.getAngle();
            var sp = ship.subLevel.logicalPose().position();
            double dx = target.getX() - sp.x;
            double dz = target.getZ() - sp.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            MCPirates.LOGGER.info("[firecracker.aim] dist={} ideal={} cur={} setpoint={}",
                    String.format("%.1f", horiz),
                    String.format("%.1f", (double) ideal),
                    String.format("%.1f", curAngle),
                    target360);
        }
    }

    @Override
    public boolean fire(Airship ship, LivingEntity target) {
        if (!targetInRange(ship, target)) return false;

        TorsionSpringBlockEntity spring = scanForSpring(ship);
        if (spring == null) return false;

        // Forbidden zone on the LIVE angle — where the cannon is actually pointing right
        // now, not where the spring is commanded. Even if aim() set a safe setpoint, the
        // spring may be mid-swing through the envelope.
        double normCur = ((spring.getAngle() % 360.0) + 360.0) % 360.0;
        if (normCur > FORBIDDEN_LO_DEG && normCur < FORBIDDEN_HI_DEG) return false;

        // Find every dispenser in the cannon plot (4 of them, glued together) and salvo
        // one wind charge per dispenser. They all face the same direction, so the cone
        // check uses any one — failing it rejects the whole salvo.
        SubLevel cannonSL = resolveCannonSubLevel(ship);
        SubLevel hostSL   = (cannonSL != null) ? cannonSL : ship.subLevel;
        java.util.List<BlockPos> dispensers = scanAllDispensers(hostSL);
        if (dispensers.isEmpty()) return false;

        // Reference geometry from the first dispenser — cone check + fire direction.
        BlockPos ref = dispensers.get(0);
        Vec3 refCenter = hostSL.logicalPose().transformPosition(
                new Vec3(ref.getX() + 0.5, ref.getY() + 0.5, ref.getZ() + 0.5));
        BlockState refState = hostSL.getLevel().getBlockState(ref);
        net.minecraft.core.Direction facing =
                refState.getValue(net.minecraft.world.level.block.DispenserBlock.FACING);
        Vector3d localDir = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vector3d worldDirJ = hostSL.logicalPose().orientation()
                .transform(localDir, new Vector3d());
        if (worldDirJ.lengthSquared() < 1e-9) return false;
        worldDirJ.normalize();
        Vec3 fireDir = new Vec3(worldDirJ.x, worldDirJ.y, worldDirJ.z);

        Vec3 toTarget = new Vec3(
                target.getX()    - refCenter.x,
                target.getEyeY() - refCenter.y,
                target.getZ()    - refCenter.z);
        double dist = toTarget.length();
        if (dist < 0.5) return false;
        if (fireDir.dot(toTarget.scale(1.0 / dist)) < COS_CONE) return false;

        // Salvo: one wind charge per dispenser, each spawned 1 block ahead of its own
        // muzzle along the shared fire direction.
        ServerLevel level = ship.parentLevel;
        Vec3 vel = fireDir.scale(WIND_CHARGE_SPEED);
        for (BlockPos disp : dispensers) {
            Vec3 muzzle = hostSL.logicalPose().transformPosition(
                    new Vec3(disp.getX() + 0.5, disp.getY() + 0.5, disp.getZ() + 0.5));
            WindCharge wc = new WindCharge(EntityType.WIND_CHARGE, level);
            wc.moveTo(muzzle.x + fireDir.x, muzzle.y + fireDir.y, muzzle.z + fireDir.z);
            wc.setDeltaMovement(vel);
            level.addFreshEntity(wc);
        }
        MCPirates.LOGGER.debug("[firecracker] salvo: {} wind charges at {} (curAngle={})",
                dispensers.size(), target.getName().getString(), normCur);
        return true;
    }

    // ─────────────────────────── geometry helpers ───────────────────────────

    /** Where the cannon is (world) and which way it's pointing (world unit vector). */
    private record FireGeometry(Vec3 cannonCenter, Vec3 fireDir) {}

    /**
     * Horizontal distance gate. Vertical separation can be huge during orbit (ship rides
     * 30+ m above target); only the XZ distance matters for "is this fight close enough
     * to engage". {@code orbitRadius() + RANGE_PAD_BLOCKS}.
     */
    private boolean targetInRange(Airship ship, LivingEntity target) {
        var shipPos = ship.subLevel.logicalPose().position();
        double dx = target.getX() - shipPos.x;
        double dz = target.getZ() - shipPos.z;
        double maxR = ship.kind.orbitRadius() + RANGE_PAD_BLOCKS;
        return (dx * dx + dz * dz) <= maxR * maxR;
    }

    /** Plot-local position of the special clutch-lever inside the ship SubLevel. */
    private BlockPos specialLeverPos(Airship ship) {
        if (ship.slPrimaryAnchor == null) return null;
        return ship.slPrimaryAnchor.offset(SPECIAL_LEVER_LEVER_REL.rotate(ship.rotation));
    }

    /**
     * Find the cannon's live world position + direction. Two cases:
     * (1) swivel has its own sub-SubLevel → dispensers live there; pose composes
     *     parent ship + spring rotation;
     * (2) swivel hasn't spawned a child yet → dispensers still on the ship plot
     *     in NBT-default east orientation.
     */
    private FireGeometry resolveFireGeometry(Airship ship) {
        SubLevel cannonSL = resolveCannonSubLevel(ship);
        SubLevel hostSL   = (cannonSL != null) ? cannonSL : ship.subLevel;

        BlockPos disp = scanForDispenser(hostSL);
        if (disp == null) return null;

        Vec3 center = hostSL.logicalPose().transformPosition(
                new Vec3(disp.getX() + 0.5, disp.getY() + 0.5, disp.getZ() + 0.5));

        BlockState s = hostSL.getLevel().getBlockState(disp);
        net.minecraft.core.Direction facing =
                s.getValue(net.minecraft.world.level.block.DispenserBlock.FACING);
        Vector3d localDir = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vector3d worldDir = hostSL.logicalPose().orientation()
                .transform(localDir, new Vector3d());
        if (worldDir.lengthSquared() < 1e-9) return null;
        worldDir.normalize();

        return new FireGeometry(center, new Vec3(worldDir.x, worldDir.y, worldDir.z));
    }

    private Vec3 resolveCannonCenter(Airship ship) {
        SubLevel cannonSL = resolveCannonSubLevel(ship);
        SubLevel hostSL   = (cannonSL != null) ? cannonSL : ship.subLevel;
        BlockPos disp = scanForDispenser(hostSL);
        if (disp == null) return null;
        return hostSL.logicalPose().transformPosition(
                new Vec3(disp.getX() + 0.5, disp.getY() + 0.5, disp.getZ() + 0.5));
    }

    private SubLevel resolveCannonSubLevel(Airship ship) {
        SwivelBearingBlockEntity bearing = scanForBearing(ship);
        if (bearing == null) return null;
        UUID innerId = bearing.getSubLevelID();
        if (innerId == null) return null;
        SubLevelContainer container = SubLevelContainer.getContainer(ship.parentLevel);
        if (container == null) return null;
        SubLevel inner = container.getSubLevel(innerId);
        return (inner != null && !inner.isRemoved()) ? inner : null;
    }

    /**
     * Ideal spring angle (degrees, 1..360) that would point the cannon at {@code target},
     * computed in ship-local frame. Returns null if the target lies too close to the
     * ship's forward/backward axis (no spring angle can reach it — orbit must adjust).
     */
    private Double computeIdealAngleDeg(Airship ship, LivingEntity target, Vec3 cannonCenter) {
        Vector3d toTargetWorld = new Vector3d(
                target.getX()    - cannonCenter.x,
                target.getEyeY() - cannonCenter.y,
                target.getZ()    - cannonCenter.z);
        double distWorld = toTargetWorld.length();
        if (distWorld < 0.5) return null;

        Vector3d toTargetLocal = ship.subLevel.logicalPose().orientation()
                .transformInverse(toTargetWorld, new Vector3d());

        // Swivel rotation axis in ship-local frame = shipLocalForward (bow direction).
        double axial = toTargetLocal.dot(ship.shipLocalForward);
        Vector3d perp = new Vector3d(toTargetLocal).fma(-axial, ship.shipLocalForward);
        double perpLen = perp.length();
        if (perpLen < 0.1) return null;

        // Cone-on-axis check: target must lie near the swivel plane.
        if (Math.abs(axial) / distWorld > SIN_CONE) return null;

        // In-swivel-plane basis: starboard = forward × up, "up" = world-Y.
        Vector3d worldUpLocal = new Vector3d(0, 1, 0);
        Vector3d starboard = new Vector3d(ship.shipLocalForward).cross(worldUpLocal);
        double sLen = starboard.length();
        if (sLen < 1e-6) return null;
        starboard.div(sLen);

        double sComp = perp.dot(starboard);
        double uComp = perp.y;

        double deg = Math.toDegrees(Math.atan2(uComp, sComp));
        if (deg < 1) deg += 360;
        return deg;
    }

    // ─────────────────────────── plot scanners ───────────────────────────

    /** Spring + bearing stay on the ship plot — the swivel only moves the cannon
     *  cluster (dispensers + link block) into the sub-SubLevel. */
    private TorsionSpringBlockEntity scanForSpring(Airship ship) {
        return scanForBE(ship.subLevel, TorsionSpringBlockEntity.class);
    }

    private SwivelBearingBlockEntity scanForBearing(Airship ship) {
        return scanForBE(ship.subLevel, SwivelBearingBlockEntity.class);
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> T scanForBE(SubLevel sl, Class<T> type) {
        var pb = sl.getPlot().getBoundingBox();
        for (BlockPos bp : BlockPos.betweenClosed(
                pb.minX(), pb.minY(), pb.minZ(),
                pb.maxX(), pb.maxY(), pb.maxZ())) {
            BlockEntity be = sl.getLevel().getBlockEntity(bp);
            if (type.isInstance(be)) return (T) be;
        }
        return null;
    }

    /** Find any one minecraft:dispenser in {@code sl}'s plot. The firecracker has four
     *  glued together; any of them is fine for centre-of-fire and facing. */
    private BlockPos scanForDispenser(SubLevel sl) {
        var pb = sl.getPlot().getBoundingBox();
        for (BlockPos bp : BlockPos.betweenClosed(
                pb.minX(), pb.minY(), pb.minZ(),
                pb.maxX(), pb.maxY(), pb.maxZ())) {
            if (sl.getLevel().getBlockState(bp).is(Blocks.DISPENSER)) return bp.immutable();
        }
        return null;
    }

    /** Find every minecraft:dispenser in {@code sl}'s plot — the firecracker's 4-gun
     *  cannon cluster. Returns plot-local positions; transform via the SubLevel's pose
     *  for world coords. */
    private java.util.List<BlockPos> scanAllDispensers(SubLevel sl) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>(4);
        var pb = sl.getPlot().getBoundingBox();
        for (BlockPos bp : BlockPos.betweenClosed(
                pb.minX(), pb.minY(), pb.minZ(),
                pb.maxX(), pb.maxY(), pb.maxZ())) {
            if (sl.getLevel().getBlockState(bp).is(Blocks.DISPENSER)) {
                out.add(bp.immutable());
            }
        }
        return out;
    }
}
