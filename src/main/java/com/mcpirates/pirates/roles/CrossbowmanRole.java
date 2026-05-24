package com.mcpirates.pirates.roles;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.physics.SegmentAABB;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crossbow shooter on a Create seat. During PURSUE: aim, then fire if in range,
 * reload is clear, and no teammate sits on the firing line.
 *
 * <p>{@link #MAX_FIRE_RANGE} is set above the ship's orbit radius so the crew has
 * a firing solution without the ship having to break orbit. Per-instance state is
 * just {@link #nextFireTick}; constructor stagger spreads the volley.
 */
public final class CrossbowmanRole implements PirateRole {

    private static final double MAX_FIRE_RANGE = 40.0;
    private static final double MAX_FIRE_RANGE_SQ = MAX_FIRE_RANGE * MAX_FIRE_RANGE;

    /** 60t = 3s; deliberately slower than vanilla (~35t) since multiple crew stagger. */
    private static final int RELOAD_TICKS = 60;

    private static final float ARROW_VELOCITY = 2.5F;

    /** Vanilla pillager 14 makes 12-block shotgunning; 2.0 keeps long shots credible. */
    private static final float INACCURACY = 2.0F;

    private static final double ARROW_DAMAGE = 3.0;

    /** ≈ pillager body radius + slack. */
    private static final double FRIENDLY_FIRE_CLEARANCE = 1.2;
    private static final double FRIENDLY_FIRE_CLEARANCE_SQ =
            FRIENDLY_FIRE_CLEARANCE * FRIENDLY_FIRE_CLEARANCE;

    /** Skip the raycast for blocks within this distance of the muzzle — the shooter's own
     *  seat block, cannon barrel directly in front, etc. would otherwise count as a hit. */
    private static final double SELF_BLOCK_CLEARANCE = 0.5;

    /** {@code /mcpirates crew hullcheck on|off}. ON is correct behaviour; OFF reverts to
     *  the legacy "fire regardless of own-ship hits" path so we can A/B compare in-game. */
    public static volatile boolean HULL_CHECK_ENABLED = true;

    private static final double ARROW_GRAVITY = 0.05;

    /** Feet→muzzle. Pillager eye is ~1.74; 1.5 keeps the arrow on the torso. */
    private static final double MUZZLE_Y_OFFSET = 1.5;

    private long nextFireTick;

    /** Pass {@code now + n*staggerTicks} to space a volley. */
    public CrossbowmanRole(long initialFireDelay) {
        this.nextFireTick = initialFireDelay;
    }

    long nextFireTick() { return nextFireTick; }

    @Override public String name() { return "crossbowman"; }

    @Override
    public void tick(ServerLevel parentLevel, Airship ship, AnchoredEntity self, Pillager pillager,
                     LivingEntity target, long now) {
        if (ship.state != AirshipBrain.State.PURSUE || target == null) {
            return;
        }

        // Sable's rideTick injection kicks seated pillagers to world coords each tick;
        // entity.position() is already world-frame.
        Vec3 worldPos = pillager.position();
        Vec3 muzzlePos = new Vec3(worldPos.x, worldPos.y + MUZZLE_Y_OFFSET, worldPos.z);
        Vec3 targetPos = new Vec3(target.getX(), target.getEyeY() - 0.15, target.getZ());

        aimAt(pillager, muzzlePos, targetPos, ship);

        double dx = targetPos.x - muzzlePos.x;
        double dz = targetPos.z - muzzlePos.z;
        double horizSq = dx * dx + dz * dz;
        if (horizSq > MAX_FIRE_RANGE_SQ) return;

        if (now < nextFireTick) return;

        if (!hasClearShotAcrossCrew(parentLevel, ship, self, muzzlePos, targetPos)) {
            // Avoid retrying every tick when a teammate permanently blocks the line.
            nextFireTick = now + 10;
            return;
        }

        if (HULL_CHECK_ENABLED && wouldHitOwnShip(ship, pillager, muzzlePos, targetPos)) {
            // Shooter is on the wrong side / line passes through hull or envelope.
            // Re-check soon; ship rotation may open a clear angle.
            nextFireTick = now + 10;
            return;
        }

        fireArrow(parentLevel, pillager, muzzlePos, targetPos, target.getDeltaMovement());
        nextFireTick = now + RELOAD_TICKS;
    }

    /** Sets current + prior-tick rotation fields so the client doesn't lerp from stale.
     *  Sable's renderer rotates kickable entities by the SubLevel's render pose at draw
     *  time, so we compute the direction in plot frame — the visual result is "world-frame
     *  aim at target" after the renderer applies the ship's orientation. */
    private static void aimAt(Pillager pillager, Vec3 muzzlePos, Vec3 targetPos, Airship ship) {
        Vec3 worldDir = targetPos.subtract(muzzlePos);
        org.joml.Vector3d plotDir = ship.subLevel.logicalPose().transformNormalInverse(
                new org.joml.Vector3d(worldDir.x, worldDir.y, worldDir.z));
        double dx = plotDir.x;
        double dy = plotDir.y;
        double dz = plotDir.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        pillager.setYRot(yaw);
        pillager.setXRot(pitch);
        pillager.setYHeadRot(yaw);
        pillager.yBodyRot = yaw;
        pillager.yRotO = yaw;
        pillager.xRotO = pitch;
        pillager.yHeadRotO = yaw;
        pillager.yBodyRotO = yaw;
    }

    /** Returns false if any teammate sits within FRIENDLY_FIRE_CLEARANCE of the line. */
    private static boolean hasClearShotAcrossCrew(ServerLevel parentLevel, Airship ship,
                                                  AnchoredEntity self, Vec3 shooterPos, Vec3 targetPos) {
        Vec3 d = targetPos.subtract(shooterPos);
        double len = d.length();
        if (len < 0.01) return true;
        Vec3 dir = d.scale(1.0 / len);
        for (AnchoredEntity other : ship.anchoredEntities) {
            if (other == self || other.uuid().equals(self.uuid())) continue;
            Entity oe = parentLevel.getEntity(other.uuid());
            if (oe == null || oe.isRemoved() || !oe.isAlive()) continue;
            // Live world pos — Sable kicks seated entities to world coords each tick.
            Vec3 otherWorldPos = oe.position();
            // Pillagers are taller than feet-pos suggests; sample at muzzle height too.
            Vec3 otherMuzzle = new Vec3(otherWorldPos.x, otherWorldPos.y + MUZZLE_Y_OFFSET, otherWorldPos.z);
            Vec3 rel = otherMuzzle.subtract(shooterPos);
            double t = rel.dot(dir);
            // Floor at 0.6 to avoid clipping yourself on a teammate right in front.
            if (t <= 0.6 || t >= len) continue;
            Vec3 closest = shooterPos.add(dir.scale(t));
            if (closest.distanceToSqr(otherMuzzle) < FRIENDLY_FIRE_CLEARANCE_SQ) {
                return false;
            }
        }
        return true;
    }

    /** Arrows live in the parent world (not the SubLevel). */
    private static void fireArrow(ServerLevel parentLevel, Pillager shooter, Vec3 muzzlePos,
                                  Vec3 targetCenter, Vec3 targetVel) {
        double dx = targetCenter.x - muzzlePos.x;
        double dy = targetCenter.y - muzzlePos.y;
        double dz = targetCenter.z - muzzlePos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double timeTicks = horiz / ARROW_VELOCITY;

        // Velocity lead.
        double aimX = targetCenter.x + targetVel.x * timeTicks;
        double aimZ = targetCenter.z + targetVel.z * timeTicks;

        // Gravity comp: 0.5·g·t² ≈ 6.4 blocks at 40m.
        double dropComp = 0.5 * ARROW_GRAVITY * timeTicks * timeTicks;
        double aimY = targetCenter.y + dropComp;

        double dirX = aimX - muzzlePos.x;
        double dirY = aimY - muzzlePos.y;
        double dirZ = aimZ - muzzlePos.z;

        Arrow arrow = new Arrow(parentLevel, muzzlePos.x, muzzlePos.y, muzzlePos.z,
                new ItemStack(Items.ARROW), new ItemStack(Items.CROSSBOW));
        arrow.setOwner(shooter);
        arrow.setBaseDamage(ARROW_DAMAGE);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.shoot(dirX, dirY, dirZ, ARROW_VELOCITY, INACCURACY);

        boolean added = parentLevel.addFreshEntity(arrow);
        parentLevel.playSound(null, muzzlePos.x, muzzlePos.y, muzzlePos.z,
                SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0F, 1.0F);

        if (!shooter.getPersistentData().getBoolean("mcpirates_logged_first_shot")) {
            shooter.getPersistentData().putBoolean("mcpirates_logged_first_shot", true);
            MCPirates.LOGGER.info(
                    "crossbowman {} first shot: muzzle={} target={} dir=({},{},{}) added={}",
                    shooter.getUUID(), muzzlePos, targetCenter, dirX, dirY, dirZ, added);
        }
    }

    /** True if the muzzle→target line clips a solid block of the shooter's own ship.
     *  Catches port/starboard wrong-side shooters AND below-deck/below-envelope angles. */
    private static boolean wouldHitOwnShip(Airship ship, Pillager shooter,
                                           Vec3 muzzleWorld, Vec3 targetWorld) {
        BoundingBox3dc bb = ship.subLevel.boundingBox();
        if (bb == null) return false;

        Vec3 d = targetWorld.subtract(muzzleWorld);
        double len = d.length();
        if (len < 0.01) return false;
        Vec3 dirUnit = d.scale(1.0 / len);
        // Step off the muzzle so the shooter's own seat / cannon barrel doesn't self-trigger.
        Vec3 start = muzzleWorld.add(dirUnit.scale(SELF_BLOCK_CLEARANCE));

        // AABB pre-reject: if the segment doesn't cross the ship's world bbox, no hit possible.
        if (!SegmentAABB.intersects(
                start.x, start.y, start.z,
                targetWorld.x, targetWorld.y, targetWorld.z,
                bb.minX(), bb.minY(), bb.minZ(),
                bb.maxX(), bb.maxY(), bb.maxZ())) {
            return false;
        }

        // Transform endpoints into plot frame; the SubLevel's Level holds blocks at plot coords.
        Vec3 plotStart = ship.subLevel.logicalPose().transformPositionInverse(start);
        Vec3 plotEnd = ship.subLevel.logicalPose().transformPositionInverse(targetWorld);
        BlockHitResult hit = ship.subLevel.getLevel().clip(new ClipContext(
                plotStart, plotEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                shooter));
        return hit.getType() != HitResult.Type.MISS;
    }

}
