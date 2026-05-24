package com.mcpirates.airship.hardware;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.physics.Ballistics;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;

/**
 * Shared cannon-mount poking primitives used by every {@link CombatBehavior}. CBC's
 * {@code CannonMountBlockEntity} renders the cannon with the SubLevel's pose <em>plus</em>
 * its own stored yaw/pitch — so to aim at a world-frame target we must feed the BE a
 * <em>SubLevel-local</em> direction (target vector pre-rotated by the inverse of the
 * ship's orientation).
 */
public final class CannonOps {

    private static final ResourceLocation POWDER_CHARGE_ID =
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "powder_charge");
    private static final ResourceLocation SOLID_SHOT_ID =
            ResourceLocation.fromNamespaceAndPath("createbigcannons", "solid_shot");

    /** Powder charges loaded per shot. Cannon barrels are 2 slots (1 powder + 1 shot)
     *  so this stays at 1; any higher leaves no room for the projectile. */
    private static final int MUZZLE_CHARGE_COUNT = 1;

    /** Power contribution per {@code createbigcannons:powder_charge} block, mirroring
     *  CBC's {@code data/createbigcannons/munition_properties/block_propellant/powder_charge.json}
     *  {@code strength} field. {@code chargesUsed = COUNT × STRENGTH}, and CBC then uses
     *  {@code chargesUsed} as the projectile's muzzle velocity (b/tick). If CBC bumps the
     *  default {@code strength}, retune here so the ballistic solver and the actual shot
     *  agree on v0 — a mismatch makes the solver refuse fire (canFire=false) while CBC
     *  could happily reach the target. */
    private static final double POWDER_STRENGTH = 2.0;

    private static Field cachedMountedContraptionField;
    private static Field cachedCannonYawField;
    private static Field cachedCannonPitchField;

    private CannonOps() {}

    /** Yaw/pitch in DEGREES, ship-local frame. {@code yaw} follows the same
     *  {@code atan2(-x, z)} convention as the original aimCannon helper: NORTH = 180°,
     *  SOUTH = 0°, EAST = -90°, WEST = +90°.
     *  <ul>
     *    <li>{@code outOfRange} — ballistic solver couldn't reach; angle is a 45° "best
     *        effort" toward the target so the barrel still tracks visually. Callers
     *        suppress fire.</li>
     *    <li>{@code yawClamped} — the actual {@code yaw} written to the mount differs from
     *        the solver's ideal yaw because a per-kind clamp (e.g. {@code BroadsideCombat}'s
     *        ±N° from rest yaw) kicked in. Cannon is pointing at the limit of its arc,
     *        not at the target. Callers must suppress fire.</li>
     *  </ul>
     *  Computed by {@link #computeAim} with {@code yawClamped = false}; callers that
     *  post-process yaw construct a new Aim with the flag set. */
    public record Aim(float yaw, float pitch, boolean outOfRange, boolean yawClamped) {
        /** True iff the cannon is pointing at the target within its physical limits. */
        public boolean canFire() { return !outOfRange && !yawClamped; }
    }

    /** Compute the (yaw, pitch) a cannon at {@code slMountPos} needs to land a solid shot
     *  on {@code target}, in ship-local degrees. World-frame ballistic solve (gravity is
     *  world-down) → rotated into ship-local because the mount BE re-applies the SubLevel
     *  pose at render time. Pure math, no side effects.
     *
     *  <p>Muzzle is queried from the cannon contraption's current pose (entity.toGlobalVector
     *  of the spawn-cell centre, matching CBC's {@code MountedBigCannonContraption.fireShot}).
     *  Since the spawn depends on the cannon's yaw/pitch and we're solving for those, the
     *  estimate is one tick stale — but aim() runs every {@code AIM_INTERVAL} ticks so it
     *  self-corrects within the first volley. Falls back to mount-as-muzzle when the
     *  contraption hasn't loaded yet. */
    public static Aim computeAim(Airship ship, BlockPos slMountPos, LivingEntity target) {
        Vec3 muzzle = resolveMuzzleWorld(ship, slMountPos);
        double dx = target.getX()    - muzzle.x;
        double dy = target.getEyeY() - muzzle.y;
        double dz = target.getZ()    - muzzle.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        double v0 = MUZZLE_CHARGE_COUNT * POWDER_STRENGTH;
        double worldPitch = Ballistics.solvePitch(horiz, dy, v0);
        boolean outOfRange = Double.isNaN(worldPitch);
        if (outOfRange) worldPitch = Math.PI / 4;

        double worldYaw = Math.atan2(-dx, dz);
        Vector3d worldDir = new Vector3d(
                -Math.sin(worldYaw) * Math.cos(worldPitch),
                 Math.sin(worldPitch),
                 Math.cos(worldYaw) * Math.cos(worldPitch));

        // Mount BE re-applies ship pose at render time, so we hand it a SubLevel-local dir.
        Vector3d localDir = ship.subLevel.logicalPose().orientation()
                .transformInverse(worldDir, new Vector3d());
        double localHoriz = Math.sqrt(localDir.x * localDir.x + localDir.z * localDir.z);
        float yaw   = (float) Math.toDegrees(Math.atan2(-localDir.x, localDir.z));
        float pitch = (float) Math.toDegrees(Math.atan2(localDir.y, localHoriz));
        return new Aim(yaw, pitch, outOfRange, false);
    }

    /** World-rendered pos of the cell where CBC will spawn the projectile this tick.
     *  Mirrors {@code MountedBigCannonContraption.fireShot}: {@code spawnPos =
     *  entity.toGlobalVector(centerOf(startPos + (N+1)*step), 0) - 2*forward}, which
     *  reduces to {@code centerOf(startPos + (N-1)*step)} once the {@code -2*forward}
     *  cancels two of the cells.
     *
     *  <p>{@code entity.toGlobalVector} returns plot-space (Sable parks the SubLevel's
     *  contents in a far-off plot region); we run it through {@link SubLevel#logicalPose()}
     *  to get the world-rendered position so the solver's dx/dy/dz to the world-frame
     *  target are correct. */
    private static Vec3 resolveMuzzleWorld(Airship ship, BlockPos slMountPos) {
        Level subLevelLevel = ship.subLevel.getLevel();
        if (subLevelLevel != null
                && subLevelLevel.getBlockEntity(slMountPos) instanceof CannonMountBlockEntity mount) {
            PitchOrientedContraptionEntity entity = getMountedContraption(mount);
            if (entity != null
                    && entity.getContraption() instanceof AbstractMountedCannonContraption cannon) {
                BlockPos startPos = cannonStartPos(cannon);
                if (startPos != null) {
                    Direction dir = cannon.initialOrientation();
                    int barrelLength = countBarrelCells(cannon, startPos, dir);
                    int offsetCells = Math.max(0, barrelLength - 1);
                    Vec3 spawnLocal = new Vec3(
                            startPos.getX() + 0.5 + dir.getStepX() * offsetCells,
                            startPos.getY() + 0.5 + dir.getStepY() * offsetCells,
                            startPos.getZ() + 0.5 + dir.getStepZ() * offsetCells);
                    Vec3 spawnPlot = entity.toGlobalVector(spawnLocal, 0);
                    return ship.subLevel.logicalPose().transformPosition(spawnPlot);
                }
            }
        }
        // Pre-assembly fallback: treat the mount itself as the muzzle. First-aim shot
        // will be off; the next aim cycle picks up the contraption and corrects.
        return ship.subLevel.logicalPose().transformPosition(
                new Vec3(slMountPos.getX() + 0.5,
                        slMountPos.getY() + 0.5,
                        slMountPos.getZ() + 0.5));
    }

    private static int countBarrelCells(AbstractMountedCannonContraption cannon,
                                        BlockPos startPos, Direction dir) {
        int n = 0;
        BlockPos p = startPos;
        while (cannon.presentBlockEntities.get(p) != null && n < 16) {
            n++;
            p = p.relative(dir);
        }
        return n;
    }

    /** Write yaw/pitch onto the cannon mount BE. Idempotent at the BE level (CBC won't
     *  re-render unless values change). */
    public static void applyAim(Airship ship, BlockPos slMountPos, float yaw, float pitch) {
        Level subLevelLevel = ship.subLevel.getLevel();
        if (subLevelLevel == null) return;
        if (!(subLevelLevel.getBlockEntity(slMountPos) instanceof CannonMountBlockEntity mount)) {
            return;
        }
        mount.setYaw(yaw);
        mount.setPitch(pitch);
        logAim(ship, slMountPos, mount, yaw, pitch);
    }

    /** Convenience: aim-and-apply, no clamping. Free-tracking cannons (airship_small) use
     *  this directly. Returns the computed Aim so callers can read {@code outOfRange}
     *  without re-solving. Returns null if the mount BE isn't loaded. */
    public static Aim aimAt(Airship ship, BlockPos slMountPos, LivingEntity target) {
        Level subLevelLevel = ship.subLevel.getLevel();
        if (subLevelLevel == null) return null;
        if (!(subLevelLevel.getBlockEntity(slMountPos) instanceof CannonMountBlockEntity mount)) {
            return null;
        }
        Aim a = computeAim(ship, slMountPos, target);
        mount.setYaw(a.yaw());
        mount.setPitch(a.pitch());
        logAim(ship, slMountPos, mount, a.yaw(), a.pitch());
        return a;
    }

    private static void logAim(Airship ship, BlockPos slMountPos,
                               CannonMountBlockEntity mount, float yaw, float pitch) {
        if (!MCPirates.LOGGER.isDebugEnabled()) return;
        float storedYaw = readCannonYaw(mount);
        float storedPitch = readCannonPitch(mount);
        MCPirates.LOGGER.debug(
                "ship {} ({}) aim mount={}: setYaw={} setPitch={} storedYaw={} storedPitch={}",
                ship.subLevel.getUniqueId(), ship.kind.name(), slMountPos,
                String.format("%.1f", yaw),
                String.format("%.1f", pitch),
                String.format("%.1f", storedYaw),
                String.format("%.1f", storedPitch));
    }

    /** Max degrees of random yaw/pitch perturbation applied at fire time on top of CBC's
     *  own spread. Pirate volleys feel too sniper-accurate otherwise — gives players a
     *  realistic chance to dodge by closing or opening the range. */
    private static final float FIRE_JITTER_DEG = 10.0f;

    /** Load (if empty) and fire the cannon at {@code slMountPos}. Jitters the cannon's
     *  yaw/pitch by ±{@link #FIRE_JITTER_DEG} before firing so consecutive shots aren't
     *  identical; the next aim() cycle will overwrite the jitter with the fresh target
     *  solution. Returns true if a shot was actually issued. */
    public static boolean fireOnce(Airship ship, BlockPos slMountPos) {
        Level subLevelLevel = ship.subLevel.getLevel();
        if (!(subLevelLevel instanceof ServerLevel ssub)) return false;
        if (ssub.getBlockEntity(slMountPos) instanceof CannonMountBlockEntity mount) {
            float yawJitter   = (ssub.getRandom().nextFloat() - 0.5f) * 2f * FIRE_JITTER_DEG;
            float pitchJitter = (ssub.getRandom().nextFloat() - 0.5f) * 2f * FIRE_JITTER_DEG;
            mount.setYaw(readCannonYaw(mount) + yawJitter);
            mount.setPitch(readCannonPitch(mount) + pitchJitter);
        }
        boolean fired = fireRawAt(ssub, ship.parentLevel, slMountPos, MUZZLE_CHARGE_COUNT);
        if (fired) {
            MCPirates.LOGGER.debug("ship {} ({}) fire at mount {}",
                    ship.subLevel.getUniqueId(), ship.kind.name(), slMountPos);
        }
        return fired;
    }

    /** Level-only entry: find cannon mount at {@code mountPos} in {@code mountLevel}, load
     *  with {@code powderCount} powder + 1 shot, fire into {@code projectileLevel} (the
     *  world the projectile flies in — equal to mountLevel for non-SubLevel cannons). */
    public static boolean fireRawAt(ServerLevel mountLevel, ServerLevel projectileLevel,
                                     BlockPos mountPos, int powderCount) {
        if (!(mountLevel.getBlockEntity(mountPos) instanceof CannonMountBlockEntity mount)) {
            MCPirates.LOGGER.warn("[fireRaw] no CannonMountBE at {}", mountPos);
            return false;
        }
        PitchOrientedContraptionEntity entity = getMountedContraption(mount);
        if (entity == null
                || !(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)) {
            MCPirates.LOGGER.warn("[fireRaw] no POCE/cannon contraption at {}", mountPos);
            return false;
        }
        MCPirates.LOGGER.info(
                "[fireRaw] mount={} mountLvl={} projLvl={} entityPos={} entityLvl={} cannon={}",
                mountPos,
                mountLevel.dimension().location(),
                projectileLevel.dimension().location(),
                entity.position(),
                entity.level().dimension().location(),
                cannon.getClass().getSimpleName());
        loadIfNeeded(cannon, powderCount);

        Vec3 ePos = entity.position();
        int preMount = countProjectilesNear(mountLevel, ePos, 80);
        int preProj = (projectileLevel == mountLevel) ? preMount : countProjectilesNear(projectileLevel, ePos, 80);
        try {
            cannon.fireShot(projectileLevel, entity);
        } catch (Throwable th) {
            MCPirates.LOGGER.error("fireShot threw at {}", mountPos, th);
            return false;
        }
        int postMount = countProjectilesNear(mountLevel, ePos, 80);
        int postProj = (projectileLevel == mountLevel) ? postMount : countProjectilesNear(projectileLevel, ePos, 80);
        MCPirates.LOGGER.info(
                "[fireRaw] post-fire projectile counts near entity: mountLvl {} -> {}, projLvl {} -> {}",
                preMount, postMount, preProj, postProj);
        return true;
    }

    private static int countProjectilesNear(ServerLevel level, Vec3 centre, double r) {
        AABB box = AABB.ofSize(centre, r * 2, r * 2, r * 2);
        return level.getEntitiesOfClass(AbstractCannonProjectile.class, box).size();
    }

    /** Stuff {@code powderCount} powder charges then one solid shot, walking the cannon
     *  from breech to muzzle in CBC's canonical {@code initialOrientation} order. CBC's
     *  {@code fireShot} walks the same sequence and fails fast if propellant doesn't sit
     *  before the projectile — iterating {@code presentBlockEntities.values()} (HashMap
     *  order) happens to work for short standalone test cannons but breaks for ship
     *  cannons whose iteration order doesn't match the physical layout. */
    private static void loadIfNeeded(AbstractMountedCannonContraption cannon, int powderCount) {
        BlockState powder = blockState(POWDER_CHARGE_ID);
        BlockState shot = blockState(SOLID_SHOT_ID);
        if (powder == null || shot == null) return;

        BlockPos pos = cannonStartPos(cannon);
        if (pos == null) return;
        Direction dir = cannon.initialOrientation();
        int loaded = 0;
        int total = powderCount + 1;
        int positionsVisited = 0;
        StringBuilder trace = new StringBuilder();
        while (loaded < total) {
            BlockEntity be = cannon.presentBlockEntities.get(pos);
            trace.append(' ').append(pos).append('=');
            if (!(be instanceof SmartBlockEntity sbe)) { trace.append("noSBE"); break; }
            BigCannonBehavior beh = bigCannonBehavior(sbe);
            if (beh == null) { trace.append("noBeh"); break; }
            StructureBlockInfo current = beh.block();
            if (current == null || current.state().isAir()) {
                BlockState toLoad = (loaded < powderCount) ? powder : shot;
                beh.loadBlock(new StructureBlockInfo(BlockPos.ZERO, toLoad, null));
                trace.append(loaded < powderCount ? "+P" : "+S");
                loaded++;
            } else {
                trace.append("hasBlock(").append(current.state().getBlock()).append(')');
            }
            pos = pos.relative(dir);
            positionsVisited++;
            if (positionsVisited > 16) { trace.append(" overflow"); break; }
        }
        MCPirates.LOGGER.info("loadIfNeeded startPos={} dir={} loaded={}/{} trace=[{}]",
                cannonStartPos(cannon), dir, loaded, total, trace);
    }

    private static Field cachedStartPosField;

    private static BlockPos cannonStartPos(AbstractMountedCannonContraption cannon) {
        try {
            if (cachedStartPosField == null) {
                cachedStartPosField = AbstractMountedCannonContraption.class.getDeclaredField("startPos");
                cachedStartPosField.setAccessible(true);
            }
            return (BlockPos) cachedStartPosField.get(cannon);
        } catch (ReflectiveOperationException e) {
            MCPirates.LOGGER.error("CannonOps: failed to read startPos via reflection", e);
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BigCannonBehavior bigCannonBehavior(SmartBlockEntity sbe) {
        BlockEntityBehaviour b = sbe.getBehaviour((BehaviourType) BigCannonBehavior.TYPE);
        return (b instanceof BigCannonBehavior bcb) ? bcb : null;
    }

    private static BlockState blockState(ResourceLocation id) {
        Block b = BuiltInRegistries.BLOCK.get(id);
        if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) return null;
        return b.defaultBlockState();
    }

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
}
