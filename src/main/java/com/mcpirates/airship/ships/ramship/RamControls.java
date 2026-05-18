package com.mcpirates.airship.ships.ramship;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.ShipTelemetry;
import com.mcpirates.airship.hardware.ClutchLevers;
import com.mcpirates.airship.hardware.Propellers;
import com.mcpirates.airship.interfaces.ShipControls;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlock;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Vector3d;

/**
 * Steering for ships with a third forward-axis propeller (currently ramship only).
 *
 * <p><b>REVERSED is relative to NBT default.</b> Ramship NBT bakes
 * {@code reversed=true} as the forward-push orientation; the controller captures the
 * NBT defaults at construction and writes deltas. Writing absolute REVERSED clobbers
 * the intentional orientation (ship moves south while facing north).
 *
 * <p><b>Three regimes:</b>
 * <ul>
 *   <li><b>Retreat</b> (post-collision): all three propellers flipped off NBT default,
 *       all three clutches engaged — straight reverse. Triggered by a sudden drop in
 *       forward speed (collision impulse). Heading error is ignored for the retreat
 *       duration; ramship coasts backward, then the normal regimes resume.</li>
 *   <li><b>Aligned</b> ({@code |rawErr| < HEADING_TOLERANCE_DEG}): both outboards at
 *       NBT default, forward clutch on — straight-line rush.</li>
 *   <li><b>Misaligned</b>: counter-rotate. Side to flip is chosen from
 *       {@code effectiveErr = rawErr − K·yawRate} so spinning past target engages the
 *       opposite reverse pattern and brakes the spin. Forward clutch off.</li>
 * </ul>
 *
 * <p>The aligned-vs-misaligned check uses <em>raw</em> error so we don't commit forward
 * thrust on a projection while the hull still points wrong.
 */
public final class RamControls implements ShipControls {

    /** PD horizon for {@code effectiveErr = rawErr − K·yawRate}. Sized to the ~45°
     *  coast at steady-state yaw rate (0.64°/tick × 70 ≈ 45°) so the controller starts
     *  braking before the hull overshoots the aligned window. */
    public static final double YAW_LOOKAHEAD_TICKS = 70.0;

    /** Retreat phase duration. ~6s at 20 TPS — enough to clear the victim hull at
     *  reverse-cruise (~0.3 b/tick × 120 ≈ 36 blocks) while staying well inside
     *  {@code DISENGAGE_RANGE} (100 blocks) so the brain doesn't drop PURSUE mid-retreat. */
    private static final int RETREAT_TICKS = 120;
    /** Pre-collision forward speed gate. Below this we weren't really charging, so a
     *  drop doesn't mean we hit anything (e.g. ship just woke up and is accelerating). */
    private static final double COLLISION_FWD_SPEED_MIN = 0.15;
    /** Forward-speed delta between consecutive applySteering calls that counts as an
     *  impact impulse. Sized > the natural decel from drag at the 3-tick decision cadence. */
    private static final double COLLISION_FWD_DROP_THRESHOLD = 0.20;
    /** Beyond this gap (e.g. ship spent time in HOVER/LIFTOFF), the previous sample is
     *  too old to compare against. Bounds against false retreats on long idle→PURSUE. */
    private static final long STALE_SPEED_GAP_TICKS = 60;

    private final BlockPos slLeftClutchLever;
    private final BlockPos slRightClutchLever;
    private final BlockPos slForwardClutchLever;
    private final BlockPos slLeftPropeller;
    private final BlockPos slRightPropeller;
    private final BlockPos slForwardPropeller;

    /** NBT-default REVERSED for each propeller. Passed in by the kind because the brain
     *  mutates the live block state during steering, so an at-rehydrate block read would
     *  yield a wrong default. */
    private final boolean nbtReversedL;
    private final boolean nbtReversedR;
    private final boolean nbtReversedF;

    private long retreatUntilTick = Long.MIN_VALUE;
    private long lastApplySteeringTick = Long.MIN_VALUE;
    private double lastForwardSpeed = Double.NaN;

    public RamControls(BlockPos slLeftClutchLever,
                       BlockPos slRightClutchLever,
                       BlockPos slForwardClutchLever,
                       BlockPos slLeftPropeller,
                       BlockPos slRightPropeller,
                       BlockPos slForwardPropeller,
                       boolean nbtReversedL,
                       boolean nbtReversedR,
                       boolean nbtReversedF) {
        this.slLeftClutchLever = slLeftClutchLever;
        this.slRightClutchLever = slRightClutchLever;
        this.slForwardClutchLever = slForwardClutchLever;
        this.slLeftPropeller = slLeftPropeller;
        this.slRightPropeller = slRightPropeller;
        this.slForwardPropeller = slForwardPropeller;
        this.nbtReversedL = nbtReversedL;
        this.nbtReversedR = nbtReversedR;
        this.nbtReversedF = nbtReversedF;
    }

    @Override
    public void applySteering(Airship a, double headingErrRad) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;

        long now = a.parentLevel.getGameTime();
        double fwdSpeed = forwardSpeed(a);

        // Drop stale sample so long HOVER/LIFTOFF idle doesn't trigger a fake retreat
        // on the first PURSUE re-entry. The arrival-radius collision case has a much
        // shorter applySteering gap and stays under this bound.
        if (now - lastApplySteeringTick > STALE_SPEED_GAP_TICKS) {
            lastForwardSpeed = Double.NaN;
        }
        // Collision = was charging, suddenly isn't. Signed forward speed so a still-retreating
        // (negative) ship can't re-arm: needs to accelerate forward past the gate again.
        if (now >= retreatUntilTick
                && !Double.isNaN(lastForwardSpeed)
                && lastForwardSpeed > COLLISION_FWD_SPEED_MIN
                && (lastForwardSpeed - fwdSpeed) > COLLISION_FWD_DROP_THRESHOLD) {
            retreatUntilTick = now + RETREAT_TICKS;
        }
        lastForwardSpeed = fwdSpeed;
        lastApplySteeringTick = now;

        boolean leftReversed, rightReversed, forwardReversed;
        boolean leftEngaged, rightEngaged, forwardEngaged;

        if (now < retreatUntilTick) {
            // Retreat: flip all three off NBT default + engage all clutches → straight reverse.
            leftReversed = !nbtReversedL;
            rightReversed = !nbtReversedR;
            forwardReversed = !nbtReversedF;
            leftEngaged = true;
            rightEngaged = true;
            forwardEngaged = true;
        } else {
            double yawRate = ShipTelemetry.angularVelocity(a).y;
            double absRawErrDeg = Math.abs(Math.toDegrees(headingErrRad));
            double effectiveErrRad = headingErrRad - YAW_LOOKAHEAD_TICKS * yawRate;
            forwardReversed = nbtReversedF;

            if (absRawErrDeg < AirshipBrain.HEADING_TOLERANCE_DEG) {
                // Aligned: both outboards at NBT default (push forward), forward clutch on.
                leftReversed = nbtReversedL;
                rightReversed = nbtReversedR;
                leftEngaged = true;
                rightEngaged = true;
                forwardEngaged = true;
            } else {
                // Counter-rotate: flip one side off NBT default. Yaw convention: positive = CW.
                // CW needs starboard reversed; CCW needs port reversed.
                if (effectiveErrRad > 0) {
                    leftReversed = nbtReversedL;
                    rightReversed = !nbtReversedR;
                } else {
                    leftReversed = !nbtReversedL;
                    rightReversed = nbtReversedR;
                }
                leftEngaged = true;
                rightEngaged = true;
                forwardEngaged = false;
            }
        }

        // Clutch levers: powered=true DIS-engages.
        ClutchLevers.setPowered(subLevel, slLeftClutchLever, !leftEngaged);
        ClutchLevers.setPowered(subLevel, slRightClutchLever, !rightEngaged);
        ClutchLevers.setPowered(subLevel, slForwardClutchLever, !forwardEngaged);
        Propellers.setReversed(subLevel, slLeftPropeller, leftReversed);
        Propellers.setReversed(subLevel, slRightPropeller, rightReversed);
        Propellers.setReversed(subLevel, slForwardPropeller, forwardReversed);
    }

    /** Signed projection of velocity onto the ship's world-forward axis (blocks/tick).
     *  Positive = charging; negative = retreating. */
    private static double forwardSpeed(Airship a) {
        Vector3d vel = ShipTelemetry.velocity(a);
        Vector3d worldFwd = a.subLevel.logicalPose().orientation()
                .transform(new Vector3d(a.shipLocalForward), new Vector3d());
        return vel.x * worldFwd.x + vel.z * worldFwd.z;
    }

    @Override
    public String diagnostics(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return "";
        long now = a.parentLevel.getGameTime();
        String phase = now < retreatUntilTick
                ? String.format("retreat=%d", retreatUntilTick - now)
                : String.format("fwdSpeed=%.3f", lastForwardSpeed);
        return String.format(
                "%s propL=%s propR=%s propF=%s clutchL=%s clutchR=%s clutchF=%s nbtRev=(L=%s,R=%s,F=%s)",
                phase,
                describeProp(subLevel, slLeftPropeller),
                describeProp(subLevel, slRightPropeller),
                describeProp(subLevel, slForwardPropeller),
                describeLever(subLevel, slLeftClutchLever),
                describeLever(subLevel, slRightClutchLever),
                describeLever(subLevel, slForwardClutchLever),
                nbtReversedL, nbtReversedR, nbtReversedF);
    }

    private static String describeProp(Level subLevel, BlockPos pos) {
        if (pos == null) return "—";
        BlockEntity be = subLevel.getBlockEntity(pos);
        if (!(be instanceof BasePropellerBlockEntity bpe)) {
            return "no-prop-be@" + pos.toShortString();
        }
        boolean reversed = subLevel.getBlockState(pos).getValue(BasePropellerBlock.REVERSED);
        return String.format("(thrust=%.2f rev=%s active=%s)",
                bpe.getThrust(), reversed, bpe.isActive());
    }

    private static String describeLever(Level subLevel, BlockPos pos) {
        if (pos == null) return "—";
        BlockState state = subLevel.getBlockState(pos);
        var pwr = BlockStateProperties.POWERED;
        return state.hasProperty(pwr)
                ? String.format("(powered=%s)", state.getValue(pwr))
                : "(no-powered-prop)";
    }

    @Override
    public void release(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;
        ClutchLevers.setPowered(subLevel, slLeftClutchLever, /*powered=disengaged=*/true);
        ClutchLevers.setPowered(subLevel, slRightClutchLever, true);
        ClutchLevers.setPowered(subLevel, slForwardClutchLever, true);
        // Restore NBT default so the next engage starts from a clean forward-thrust state.
        Propellers.setReversed(subLevel, slLeftPropeller, nbtReversedL);
        Propellers.setReversed(subLevel, slRightPropeller, nbtReversedR);
        Propellers.setReversed(subLevel, slForwardPropeller, nbtReversedF);
    }

    @Override
    public boolean isActive(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return false;
        return ClutchLevers.isEngaged(subLevel, slLeftClutchLever)
                || ClutchLevers.isEngaged(subLevel, slRightClutchLever)
                || ClutchLevers.isEngaged(subLevel, slForwardClutchLever);
    }
}
