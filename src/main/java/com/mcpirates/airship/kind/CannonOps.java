package com.mcpirates.airship.kind;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
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

    private static Field cachedMountedContraptionField;
    private static Field cachedCannonYawField;
    private static Field cachedCannonPitchField;

    private CannonOps() {}

    /** Yaw/pitch in DEGREES, ship-local frame. {@code yaw} follows the same
     *  {@code atan2(-x, z)} convention as the original aimCannon helper: NORTH = 180°,
     *  SOUTH = 0°, EAST = -90°, WEST = +90°. */
    public record Aim(float yaw, float pitch) {}

    /** Compute the (yaw, pitch) a cannon at {@code slMountPos} needs to track
     *  {@code target}, in ship-local degrees. Pure math, no side effects — kinds that
     *  need to clamp/filter the aim (e.g. broadsides limited to ±N° from rest) call this,
     *  mutate, then call {@link #applyAim}. */
    public static Aim computeAim(Airship ship, BlockPos slMountPos, LivingEntity target) {
        Vec3 cannonWorldV = ship.subLevel.logicalPose().transformPosition(
                new Vec3(slMountPos.getX() + 0.5,
                        slMountPos.getY() + 0.5,
                        slMountPos.getZ() + 0.5));
        Vector3d worldDir = new Vector3d(
                target.getX() - cannonWorldV.x,
                target.getEyeY() - cannonWorldV.y,
                target.getZ() - cannonWorldV.z);
        // Rotate the world-direction vector INTO the ship's local frame (use inverse of
        // pose orientation). The cannon-mount BE then re-applies the ship pose at render
        // time — feeding world-frame yaw/pitch would double-count it.
        Vector3d localDir = ship.subLevel.logicalPose().orientation()
                .transformInverse(worldDir, new Vector3d());

        double horiz = Math.sqrt(localDir.x * localDir.x + localDir.z * localDir.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-localDir.x, localDir.z));
        // CBC treats positive pitch as "barrel up" (opposite of vanilla), so no negate.
        float pitch = (float) Math.toDegrees(Math.atan2(localDir.y, horiz));
        return new Aim(yaw, pitch);
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
     *  this directly. */
    public static void aimAt(Airship ship, BlockPos slMountPos, LivingEntity target) {
        Level subLevelLevel = ship.subLevel.getLevel();
        if (subLevelLevel == null) return;
        if (!(subLevelLevel.getBlockEntity(slMountPos) instanceof CannonMountBlockEntity mount)) {
            return;
        }
        Aim a = computeAim(ship, slMountPos, target);
        mount.setYaw(a.yaw());
        mount.setPitch(a.pitch());
        logAim(ship, slMountPos, mount, a.yaw(), a.pitch());
    }

    private static void logAim(Airship ship, BlockPos slMountPos,
                               CannonMountBlockEntity mount, float yaw, float pitch) {

        // (continued logAim body — diagnostic only)
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
        if (subLevelLevel == null) return false;
        if (!(subLevelLevel.getBlockEntity(slMountPos) instanceof CannonMountBlockEntity mount)) {
            return false;
        }
        PitchOrientedContraptionEntity entity = getMountedContraption(mount);
        if (entity == null
                || !(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)) {
            return false;
        }
        loadIfNeeded(cannon);
        try {
            cannon.fireShot(ship.parentLevel, entity);
            if (!ship.hasFiredOnce) {
                ship.hasFiredOnce = true;
                MCPirates.LOGGER.info("ship {} ({}) first fire at mount {}",
                        ship.subLevel.getUniqueId(), ship.kind.name(), slMountPos);
            }
            return true;
        } catch (Throwable th) {
            MCPirates.LOGGER.error("fireShot threw at {}: {}", slMountPos, th.toString());
            return false;
        }
    }

    /** Stuff one powder charge and one solid shot into the first two empty cannon
     *  blocks of {@code cannon}. CBC barrels stay loaded between shots; this only fires
     *  on the first activation per cannon, after which the barrel holds the loaded state. */
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
