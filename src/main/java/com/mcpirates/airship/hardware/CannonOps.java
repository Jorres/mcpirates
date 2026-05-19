package com.mcpirates.airship.hardware;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.physics.Ballistics;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
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

    /** Powder charges loaded per shot. Sets the muzzle velocity at 1.0 b/tick per charge
     *  (CBC's propellant arithmetic). When per-kind variation actually has a use case,
     *  promote this to an {@code AirshipKind} method — until then it's a global constant. */
    private static final int MUZZLE_CHARGE_COUNT = 2;

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
     *  pose at render time. Pure math, no side effects. */
    public static Aim computeAim(Airship ship, BlockPos slMountPos, LivingEntity target) {
        Vec3 muzzle = ship.subLevel.logicalPose().transformPosition(
                new Vec3(slMountPos.getX() + 0.5,
                        slMountPos.getY() + 0.5,
                        slMountPos.getZ() + 0.5));
        double dx = target.getX()    - muzzle.x;
        double dy = target.getEyeY() - muzzle.y;
        double dz = target.getZ()    - muzzle.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        double v0 = MUZZLE_CHARGE_COUNT;
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
        long bucket = System.currentTimeMillis() / 2000;
        boolean firstAim = !ship.hasAimedOnce;
        ship.hasAimedOnce = true;
        if (firstAim || bucket != ship.lastAimLogBucket) {
            ship.lastAimLogBucket = bucket;
            float storedYaw = readCannonYaw(mount);
            float storedPitch = readCannonPitch(mount);
            MCPirates.LOGGER.info(
                    "ship {} ({}) {} aim mount={}: setYaw={} setPitch={} storedYaw={} storedPitch={}",
                    ship.subLevel.getUniqueId(), ship.kind.name(),
                    firstAim ? "first" : "tick", slMountPos,
                    String.format("%.1f", yaw),
                    String.format("%.1f", pitch),
                    String.format("%.1f", storedYaw),
                    String.format("%.1f", storedPitch));
        }
    }

    /** Load (if empty) and fire the cannon at {@code slMountPos}. Returns true if a shot
     *  was actually issued. */
    public static boolean fireOnce(Airship ship, BlockPos slMountPos) {
        Level subLevelLevel = ship.subLevel.getLevel();
        if (!(subLevelLevel instanceof ServerLevel ssub)) return false;
        boolean fired = fireRawAt(ssub, ship.parentLevel, slMountPos, MUZZLE_CHARGE_COUNT);
        if (fired && !ship.hasFiredOnce) {
            ship.hasFiredOnce = true;
            MCPirates.LOGGER.info("ship {} ({}) first fire at mount {}",
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
            return false;
        }
        PitchOrientedContraptionEntity entity = getMountedContraption(mount);
        if (entity == null
                || !(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)) {
            return false;
        }
        loadIfNeeded(cannon, powderCount);
        try {
            cannon.fireShot(projectileLevel, entity);
            return true;
        } catch (Throwable th) {
            MCPirates.LOGGER.error("fireShot threw at {}: {}", mountPos, th.toString());
            return false;
        }
    }

    /** Stuff {@code powderCount} powder charges then one solid shot into the first empty
     *  cannon blocks of {@code cannon}. CBC barrels stay loaded between shots; this only
     *  fires on the first activation per cannon, after which the barrel holds the loaded
     *  state. If the cannon doesn't have {@code powderCount + 1} free slots the shot won't
     *  fit — fireShot then becomes a recoil-only blank, which is fine as a soft cap. */
    private static void loadIfNeeded(AbstractMountedCannonContraption cannon, int powderCount) {
        BlockState powder = blockState(POWDER_CHARGE_ID);
        BlockState shot = blockState(SOLID_SHOT_ID);
        if (powder == null || shot == null) return;

        int loaded = 0;
        int total = powderCount + 1;
        for (BlockEntity be : cannon.presentBlockEntities.values()) {
            if (!(be instanceof SmartBlockEntity sbe)) continue;
            BigCannonBehavior beh = bigCannonBehavior(sbe);
            if (beh == null) continue;
            StructureBlockInfo current = beh.block();
            if (current != null && !current.state().isAir()) continue;

            BlockState toLoad = (loaded < powderCount) ? powder : shot;
            beh.loadBlock(new StructureBlockInfo(BlockPos.ZERO, toLoad, null));
            if (++loaded >= total) return;
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
