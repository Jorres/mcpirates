package com.mcpirates.pirates.roles;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Long-range crossbow shooter anchored to a Create seat on the airship's deck.
 *
 * <h2>Behaviour</h2>
 *
 * Every tick during {@link AirshipBrain.State#PURSUE}:
 * <ol>
 *   <li>Compute world-rendered position by transforming the pirate's stable plot pos
 *       through the SubLevel's logical pose.</li>
 *   <li>Aim head/body/yaw at the target — cheap rotation writes, runs every tick.</li>
 *   <li>If target is within {@link #MAX_FIRE_RANGE} and reload is clear:
 *       <ul>
 *         <li>raycast from shooter to target; skip if any other anchored pirate is
 *             within {@link #FRIENDLY_FIRE_CLEARANCE} of that line (don't perforate
 *             the captain);</li>
 *         <li>spawn an {@link Arrow} in the parent world at the shooter's world pos,
 *             with target velocity lead and gravity drop compensation.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Outside PURSUE (LIFTOFF / RETURN / HOVER) or with no target, the role idles —
 * no rotation change, no fire. The brain handles target acquisition; we trust it.
 *
 * <h2>Range tuning</h2>
 *
 * {@link #MAX_FIRE_RANGE} = 40 blocks, intentionally larger than the ship's 30-block
 * orbit radius (see {@link com.mcpirates.airship.kind.CrossbowBoardKind#orbitRadius}).
 * Margin of 10 blocks means: as long as the ship is orbiting reasonably, the crew has
 * a constant firing solution; the ship doesn't need to break orbit to maintain pressure.
 * Vanilla {@code RangedCrossbowAttackGoal} uses 8 blocks — wholly inadequate for an
 * airship engagement, which is why we don't reuse it (see {@link PirateRole} class doc).
 *
 * <h2>State</h2>
 *
 * <p>Only {@link #nextFireTick} is per-instance — a long enforcing reload. Staggered
 * initial values across the crew (passed via the constructor) produce a rolling volley
 * instead of all pirates discharging in unison.
 */
public final class CrossbowmanRole implements PirateRole {

    /** Horizontal firing range — must outrun {@code orbitRadius} of the host ship so
     *  the ship doesn't have to close to score hits. */
    private static final double MAX_FIRE_RANGE = 40.0;
    private static final double MAX_FIRE_RANGE_SQ = MAX_FIRE_RANGE * MAX_FIRE_RANGE;

    /** Crossbow reload between shots. 60 ticks = 3 s. Slower than vanilla pillager
     *  (~35 ticks) because multiple crew share the firing schedule; staggered initial
     *  delays produce overlapping volleys. */
    private static final int RELOAD_TICKS = 60;

    /** Arrow launch velocity (blocks/tick). Roughly matches vanilla crossbow output;
     *  at 40 blocks an arrow reaches the target in ~16 ticks. */
    private static final float ARROW_VELOCITY = 2.5F;

    /** Random scatter applied to shot direction. Vanilla pillager inaccuracy is 14 —
     *  effectively a shotgun at 12 blocks. Drop to 2.0 so 40-block shots are credible
     *  but the player can still dodge with movement. */
    private static final float INACCURACY = 2.0F;

    /** Per-shot damage. 3.0 ≈ "annoying but not lethal" through iron armour — leaves
     *  the threat at "many shots will wear you down" rather than "one shot disables you". */
    private static final double ARROW_DAMAGE = 3.0;

    /** Skip the shot when any teammate is closer than this many blocks to the firing
     *  line between shooter and target. 1.2 blocks ≈ pillager body radius + slack. */
    private static final double FRIENDLY_FIRE_CLEARANCE = 1.2;
    private static final double FRIENDLY_FIRE_CLEARANCE_SQ =
            FRIENDLY_FIRE_CLEARANCE * FRIENDLY_FIRE_CLEARANCE;

    /** MC arrow gravity (blocks/tick²). Used to pre-aim above the target at long range. */
    private static final double ARROW_GRAVITY = 0.05;

    /** Vertical offset from feet to muzzle. Pillager eye level is ~1.74; rounding to
     *  1.5 keeps the arrow visually emerging from the upper torso. */
    private static final double MUZZLE_Y_OFFSET = 1.5;

    private long nextFireTick;

    /** @param initialFireDelay absolute tick at or after which this crewmate may first
     *                          fire. Pass {@code now + n*staggerTicks} to space a volley. */
    public CrossbowmanRole(long initialFireDelay) {
        this.nextFireTick = initialFireDelay;
    }

    @Override public String name() { return "crossbowman"; }

    @Override
    public void tick(ServerLevel parentLevel, Airship ship, AnchoredEntity self, Pillager pillager,
                     ServerPlayer target, long now) {
        if (ship.state != AirshipBrain.State.PURSUE || target == null) {
            return;
        }

        Vec3 worldPos = ship.subLevel.logicalPose().transformPosition(self.plotPos());
        Vec3 muzzlePos = new Vec3(worldPos.x, worldPos.y + MUZZLE_Y_OFFSET, worldPos.z);
        Vec3 targetPos = new Vec3(target.getX(), target.getEyeY() - 0.15, target.getZ());

        aimAt(pillager, muzzlePos, targetPos);

        double dx = targetPos.x - muzzlePos.x;
        double dz = targetPos.z - muzzlePos.z;
        double horizSq = dx * dx + dz * dz;
        if (horizSq > MAX_FIRE_RANGE_SQ) return;

        if (now < nextFireTick) return;

        if (!hasClearShotAcrossCrew(parentLevel, ship, self, muzzlePos, targetPos)) {
            // Push next attempt out a short way; we don't want to retry every tick when
            // a teammate is permanently in the firing line.
            nextFireTick = now + 10;
            return;
        }

        fireArrow(parentLevel, pillager, muzzlePos, targetPos, target.getDeltaMovement());
        nextFireTick = now + RELOAD_TICKS;
    }

    /** Write the rotation fields so the pillager visibly faces the target. We set both
     *  current and "prior tick" fields so the client doesn't lerp from a stale rotation.
     *  Yaw uses the {@code atan2(-dx, dz)} MC convention (NORTH = 180°). */
    private static void aimAt(Pillager pillager, Vec3 muzzlePos, Vec3 targetPos) {
        double dx = targetPos.x - muzzlePos.x;
        double dy = targetPos.y - muzzlePos.y;
        double dz = targetPos.z - muzzlePos.z;
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

    /** Cast a ray from {@code shooter} to {@code target}, return false if any other
     *  anchored pirate's world-rendered position falls within
     *  {@link #FRIENDLY_FIRE_CLEARANCE} of that line, between shooter and target. */
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
            Vec3 otherWorldPos = ship.subLevel.logicalPose().transformPosition(other.plotPos());
            // Pillagers are taller than feet-pos suggests; sample at muzzle height too.
            Vec3 otherMuzzle = new Vec3(otherWorldPos.x, otherWorldPos.y + MUZZLE_Y_OFFSET, otherWorldPos.z);
            Vec3 rel = otherMuzzle.subtract(shooterPos);
            double t = rel.dot(dir);
            // Skip if behind shooter (t<=0) or past target (t>=len). Small positive
            // floor (0.6) prevents clipping yourself when the teammate is "very close
            // but slightly in front" — your arrow leaves at +X, theirs starts at +X.
            if (t <= 0.6 || t >= len) continue;
            Vec3 closest = shooterPos.add(dir.scale(t));
            if (closest.distanceToSqr(otherMuzzle) < FRIENDLY_FIRE_CLEARANCE_SQ) {
                return false;
            }
        }
        return true;
    }

    /** Spawn the arrow in the parent world (NOT the SubLevel — arrows fly through real
     *  world space). Owner = shooter so vanilla "killed by mob" attribution works.
     *  Pickup is DISALLOWED so the player can't farm endless arrows from a fight. */
    private static void fireArrow(ServerLevel parentLevel, Pillager shooter, Vec3 muzzlePos,
                                  Vec3 targetCenter, Vec3 targetVel) {
        double dx = targetCenter.x - muzzlePos.x;
        double dy = targetCenter.y - muzzlePos.y;
        double dz = targetCenter.z - muzzlePos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double timeTicks = horiz / ARROW_VELOCITY;

        // Velocity lead — aim where the target WILL be when the arrow arrives. Falls
        // apart on accelerating / turning targets but works fine vs. a strafing player.
        double aimX = targetCenter.x + targetVel.x * timeTicks;
        double aimZ = targetCenter.z + targetVel.z * timeTicks;

        // Gravity compensation: arrow falls 0.5·g·t² over the flight; pre-aim above the
        // target by that much. At 40 blocks t≈16, drop≈6.4 blocks — non-trivial.
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
}
