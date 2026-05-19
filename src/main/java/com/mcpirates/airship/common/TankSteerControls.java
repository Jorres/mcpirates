package com.mcpirates.airship.common;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.ShipTelemetry;
import com.mcpirates.airship.hardware.ClutchLevers;
import com.mcpirates.airship.hardware.Propellers;
import com.mcpirates.airship.interfaces.ShipControls;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Default steering for any ship with two outboard prop groups (one per side). Maps
 * {@link TurnPolicy} regimes to clutch + prop-REVERSED writes; identical to ramship's
 * outboard handling minus the third forward prop and retreat phase.
 *
 * <p>Each side is a {@code List<BlockPos>} so multi-prop ships (galleon: 4/side) pass
 * the whole bank — one REVERSED flip per side cascades to every prop in the list. NBT
 * default REVERSED is per-side, not per-prop (all props on a side share the same baked
 * orientation; if a kind ever violates this we split the field then).
 */
public final class TankSteerControls implements ShipControls {

    private final BlockPos slClutchL;
    private final BlockPos slClutchR;
    private final List<BlockPos> slPropsL;
    private final List<BlockPos> slPropsR;
    private final boolean nbtReversedL;
    private final boolean nbtReversedR;

    public TankSteerControls(BlockPos slClutchL, BlockPos slClutchR,
                             List<BlockPos> slPropsL, List<BlockPos> slPropsR,
                             boolean nbtReversedL, boolean nbtReversedR) {
        this.slClutchL = slClutchL;
        this.slClutchR = slClutchR;
        this.slPropsL = List.copyOf(slPropsL);
        this.slPropsR = List.copyOf(slPropsR);
        this.nbtReversedL = nbtReversedL;
        this.nbtReversedR = nbtReversedR;
    }

    @Override
    public void applySteering(Airship a, double headingErrRad) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;

        double yawRate = ShipTelemetry.angularVelocity(a).y;
        TurnPolicy.Decision d = TurnPolicy.decide(headingErrRad, yawRate);

        boolean clutchLEngaged, clutchREngaged;
        boolean reversedL = nbtReversedL;
        boolean reversedR = nbtReversedR;
        switch (d.regime()) {
            case ALIGNED -> {
                clutchLEngaged = true;
                clutchREngaged = true;
            }
            case TANK_STEER -> {
                // Outside outboard pushes forward, inside disengages.
                // yawDir = +1 (CW): keep LEFT engaged, drop RIGHT.
                clutchLEngaged = d.yawDir() > 0;
                clutchREngaged = d.yawDir() < 0;
            }
            case COUNTER_ROTATE -> {
                // Both clutches on, one side's props flipped. yawDir = +1 (CW): flip RIGHT.
                clutchLEngaged = true;
                clutchREngaged = true;
                if (d.yawDir() > 0) reversedR = !nbtReversedR;
                else reversedL = !nbtReversedL;
            }
            default -> throw new IllegalStateException("unreachable regime: " + d.regime());
        }
        ClutchLevers.setPowered(subLevel, slClutchL, !clutchLEngaged);
        ClutchLevers.setPowered(subLevel, slClutchR, !clutchREngaged);
        setSidePropsReversed(subLevel, slPropsL, reversedL);
        setSidePropsReversed(subLevel, slPropsR, reversedR);
    }

    @Override
    public void release(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;
        ClutchLevers.setPowered(subLevel, slClutchL, /*powered=disengaged=*/true);
        ClutchLevers.setPowered(subLevel, slClutchR, true);
        setSidePropsReversed(subLevel, slPropsL, nbtReversedL);
        setSidePropsReversed(subLevel, slPropsR, nbtReversedR);
    }

    @Override
    public boolean isActive(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return false;
        return ClutchLevers.isEngaged(subLevel, slClutchL)
                || ClutchLevers.isEngaged(subLevel, slClutchR);
    }

    private static void setSidePropsReversed(Level subLevel, List<BlockPos> props, boolean reversed) {
        for (BlockPos p : props) Propellers.setReversed(subLevel, p, reversed);
    }
}
