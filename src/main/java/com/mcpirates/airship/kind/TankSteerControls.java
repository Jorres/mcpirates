package com.mcpirates.airship.kind;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Default steering: two outboard propellers, no reversal. Heading aligned →
 * both engaged (straight-line thrust). Heading off → engage the side opposite
 * the turn direction (one prop pushing, the other coasting), pivoting by
 * tank-steer.
 *
 * <p>Used by every kind that doesn't override
 * {@link AirshipKind#makeControls}. Cannot pivot in place — angular speed is
 * half that of counter-rotation because only one prop produces torque about
 * the centre.
 */
public final class TankSteerControls implements ShipControls {

    private final BlockPos slLeftClutchLever;
    private final BlockPos slRightClutchLever;

    public TankSteerControls(BlockPos slLeftClutchLever, BlockPos slRightClutchLever) {
        this.slLeftClutchLever = slLeftClutchLever;
        this.slRightClutchLever = slRightClutchLever;
    }

    @Override
    public void applySteering(Airship a, double headingErrRad) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;

        double absErrDeg = Math.abs(Math.toDegrees(headingErrRad));
        boolean leftEngaged, rightEngaged;
        if (absErrDeg < AirshipBrain.HEADING_TOLERANCE_DEG) {
            leftEngaged = true;
            rightEngaged = true;
        } else if (headingErrRad > 0) {
            leftEngaged = true;
            rightEngaged = false;
        } else {
            leftEngaged = false;
            rightEngaged = true;
        }
        // ClutchLevers: powered=true DIS-engages — see ClutchLevers header.
        ClutchLevers.setPowered(subLevel, slLeftClutchLever, !leftEngaged);
        ClutchLevers.setPowered(subLevel, slRightClutchLever, !rightEngaged);
    }

    @Override
    public void release(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;
        ClutchLevers.setPowered(subLevel, slLeftClutchLever, /*powered=disengaged=*/true);
        ClutchLevers.setPowered(subLevel, slRightClutchLever, /*powered=disengaged=*/true);
    }
}
