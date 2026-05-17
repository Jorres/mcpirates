package com.mcpirates.airship.kind;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlock;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Steering for ships with a third forward-axis propeller (currently only the
 * ramship). Hardware:
 *
 * <ul>
 *   <li>{@code slLeftClutchLever} / {@code slRightClutchLever} — gate the two
 *       outboard propellers' clutches.
 *   <li>{@code slForwardClutchLever} — gates the center forward propeller
 *       that provides the rammer's straight-line thrust.
 *   <li>{@code slLeftPropeller} / {@code slRightPropeller} / {@code slForwardPropeller}
 *       — surfaced through {@link #diagnostics} so we can see live thrust
 *       and {@code REVERSED} state.
 * </ul>
 *
 * <h2>Propeller REVERSED state is RELATIVE to NBT default</h2>
 * The ramship NBT places the three propellers with {@code reversed=true} —
 * that's the orientation in which they push the ship NORTH (forward). The
 * controller does NOT write absolute REVERSED values; instead it captures the
 * NBT defaults at construction time and writes deltas from them:
 * <ul>
 *   <li>{@link #release} and the <b>Aligned</b> branch restore both outboard
 *       propellers to their NBT default — both push forward.
 *   <li>The <b>Counter-rotation</b> branch flips ONE side away from its NBT
 *       default — that side now pushes backward, producing a torque couple
 *       with the other side that still pushes forward.
 * </ul>
 * Writing absolute values would clobber the NBT's intentional propeller
 * orientation and produce backward thrust when "engaging forward" — caught
 * 2026-05-17 during the ramship intercept test (the ship moved south while
 * facing north).
 *
 * <h2>Control law</h2>
 * Two regimes selected by the actual (raw) heading error to target:
 *
 * <ul>
 *   <li><b>Aligned</b> ({@code |rawErr| < HEADING_TOLERANCE_DEG}): both
 *       outboards engaged at NBT default, forward clutch on. Straight-line
 *       rush at the target.
 *   <li><b>Misaligned</b>: counter-rotate to pivot. Side to flip is chosen
 *       from {@code effectiveErr = rawErr − K · yawRate} (PD form — see
 *       {@link #YAW_LOOKAHEAD_TICKS}), so a rotation that has carried us
 *       past target naturally engages the opposite reverse pattern,
 *       actively braking the spin. Forward clutch is off while pivoting —
 *       the hull's current facing direction is by definition wrong, so
 *       adding forward thrust would just drive us off-course.
 * </ul>
 *
 * <p>"Aligned" tests <em>raw</em> error, not {@code effectiveErr}: we want
 * to commit to forward thrust only when the hull <em>actually</em> points
 * at the target, not when it's projected to soon. Committing on the
 * projection makes the ship rush in its current (wrong) facing direction
 * for the few ticks it takes the rotation to actually arrive — see the
 * 2026-05-17 ramship debugging session for the post-mortem.
 */
public final class RamControls implements ShipControls {

    /** Look-ahead horizon (ticks) for the yaw PD term — {@code effectiveErr
     *  = rawErr − K · yawRate}. Used only to bias <em>which side to flip</em>
     *  during counter-rotation: when the ship is already rotating toward
     *  target, {@code effectiveErr} shrinks and eventually flips sign, at
     *  which point the controller engages the opposite reverse pattern and
     *  actively brakes the spin.
     *
     *  <p>Sized to the ramship's measured coast distance — at full-throttle
     *  steady-state yaw rate (~0.64°/tick), the hull continues ~45° after the
     *  propellers stop driving. The horizon must be at least that long, or the
     *  controller doesn't start counter-rotating until the ship has already
     *  overshot the aligned window, producing visible heading oscillation.
     *  70 ticks × 0.64°/tick ≈ 45°, matching the empirical coast.
     *
     *  <p>Not used for the aligned-vs-misaligned check — that one uses raw
     *  error to avoid committing to forward thrust based on a projection. */
    public static final double YAW_LOOKAHEAD_TICKS = 70.0;

    private final BlockPos slLeftClutchLever;
    private final BlockPos slRightClutchLever;
    private final BlockPos slForwardClutchLever;
    private final BlockPos slLeftPropeller;
    private final BlockPos slRightPropeller;
    /** Forward-axis propeller — not actuated directly (its clutch lever is),
     *  but surfaced through {@link #diagnostics} so we can confirm it's
     *  spinning when the brain expects forward thrust. */
    private final BlockPos slForwardPropeller;

    /** NBT-default REVERSED state for each outboard propeller, captured once
     *  at construction time. All controller writes go through these as the
     *  baseline; the controller never writes an absolute {@code REVERSED}
     *  value. See class javadoc. */
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

        double yawRate = com.mcpirates.airship.ShipLog.angularVelocity(a).y;
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
            // Counter-rotate — flip ONE side away from its NBT default to make it push
            // backward, while the other stays at default (pushing forward). Forward
            // clutch off — the hull's current facing is wrong, no straight thrust.
            //
            // Yaw convention (Airship.yawRadians): positive yaw = CW from above
            // (south→west→north→east). For CW rotation the bow must swing right (east):
            // port pushes forward, starboard pushes backward → flip starboard (R).
            // For CCW: flip port (L).
            if (effectiveErrRad > 0) {
                // Need to increase yaw → CW → flip starboard away from default.
                leftReversed = nbtReversedL;
                rightReversed = !nbtReversedR;
            } else {
                // Need to decrease yaw → CCW → flip port away from default.
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
        // Restore propellers to NBT default in case counter-rotation left one flipped.
        // Keeping them at default means the next clutch engage (LIFTOFF, PURSUE, etc.)
        // starts with both outboards pushing forward — the same state the player sees
        // on a freshly placed ramship.
        Propellers.setReversed(subLevel, slLeftPropeller, nbtReversedL);
        Propellers.setReversed(subLevel, slRightPropeller, nbtReversedR);
    }
}
