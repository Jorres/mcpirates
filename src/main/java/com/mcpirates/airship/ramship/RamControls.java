package com.mcpirates.airship.ramship;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.kind.ClutchLevers;
import com.mcpirates.airship.kind.Propellers;
import com.mcpirates.airship.kind.ShipControls;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlock;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Steering for ships with a third forward-axis propeller (currently ramship only).
 *
 * <p><b>REVERSED is relative to NBT default.</b> Ramship NBT bakes
 * {@code reversed=true} as the forward-push orientation; the controller captures the
 * NBT defaults at construction and writes deltas. Writing absolute REVERSED clobbers
 * the intentional orientation (ship moves south while facing north).
 *
 * <p><b>Two regimes:</b>
 * <ul>
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

    private final BlockPos slLeftClutchLever;
    private final BlockPos slRightClutchLever;
    private final BlockPos slForwardClutchLever;
    private final BlockPos slLeftPropeller;
    private final BlockPos slRightPropeller;
    /** Driven via its clutch lever; surfaced through {@link #diagnostics} only. */
    private final BlockPos slForwardPropeller;

    /** NBT-default REVERSED, captured once; all writes are deltas from these. */
    private final boolean nbtReversedL;
    private final boolean nbtReversedR;

    public RamControls(Level subLevel,
                       BlockPos slLeftClutchLever,
                       BlockPos slRightClutchLever,
                       BlockPos slForwardClutchLever,
                       BlockPos slLeftPropeller,
                       BlockPos slRightPropeller,
                       BlockPos slForwardPropeller) {
        this.slLeftClutchLever = slLeftClutchLever;
        this.slRightClutchLever = slRightClutchLever;
        this.slForwardClutchLever = slForwardClutchLever;
        this.slLeftPropeller = slLeftPropeller;
        this.slRightPropeller = slRightPropeller;
        this.slForwardPropeller = slForwardPropeller;
        this.nbtReversedL = readReversed(subLevel, slLeftPropeller);
        this.nbtReversedR = readReversed(subLevel, slRightPropeller);
    }

    private static boolean readReversed(Level subLevel, BlockPos pos) {
        if (subLevel == null || pos == null) return false;
        BlockState state = subLevel.getBlockState(pos);
        if (!state.hasProperty(BasePropellerBlock.REVERSED)) return false;
        return state.getValue(BasePropellerBlock.REVERSED);
    }

    @Override
    public void applySteering(Airship a, double headingErrRad) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;

        double yawRate = com.mcpirates.airship.ShipTelemetry.angularVelocity(a).y;
        double absRawErrDeg = Math.abs(Math.toDegrees(headingErrRad));
        double effectiveErrRad = headingErrRad - YAW_LOOKAHEAD_TICKS * yawRate;

        boolean leftReversed, rightReversed;
        boolean leftEngaged, rightEngaged, forwardEngaged;

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

        // Clutch levers: powered=true DIS-engages.
        ClutchLevers.setPowered(subLevel, slLeftClutchLever, !leftEngaged);
        ClutchLevers.setPowered(subLevel, slRightClutchLever, !rightEngaged);
        ClutchLevers.setPowered(subLevel, slForwardClutchLever, !forwardEngaged);
        Propellers.setReversed(subLevel, slLeftPropeller, leftReversed);
        Propellers.setReversed(subLevel, slRightPropeller, rightReversed);
    }

    @Override
    public String diagnostics(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return "";
        return String.format(
                "propL=%s propR=%s propF=%s clutchL=%s clutchR=%s clutchF=%s nbtRev=(L=%s,R=%s)",
                describeProp(subLevel, slLeftPropeller),
                describeProp(subLevel, slRightPropeller),
                describeProp(subLevel, slForwardPropeller),
                describeLever(subLevel, slLeftClutchLever),
                describeLever(subLevel, slRightClutchLever),
                describeLever(subLevel, slForwardClutchLever),
                nbtReversedL, nbtReversedR);
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
