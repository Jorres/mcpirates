package com.mcpirates.airship.lift;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.kind.HotAirBurners;
import com.mcpirates.airship.kind.PlateauTable.LiftSetting;
import com.mcpirates.airship.kind.ThrottleLevers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Default lift implementation: writes {@code setting.lever()} to every throttle lever
 * and {@code setting.volume()} to every burner. Lock-step write across multi-burner
 * kinds (galleon) keeps the burners producing equal output.
 *
 * <p>Captures the resolved SL-local block positions at construction time so the brain
 * can call {@link #apply}/{@link #queryBalloonCapacity} without holding any block
 * addresses itself.
 */
public final class HotAirBalloonLift implements ShipLift {

    private final List<BlockPos> slThrottleLevers;
    private final List<BlockPos> slBurnerPositions;

    public HotAirBalloonLift(List<BlockPos> slThrottleLevers, List<BlockPos> slBurnerPositions) {
        this.slThrottleLevers = slThrottleLevers;
        this.slBurnerPositions = slBurnerPositions;
    }

    @Override
    public void apply(Airship a, LiftSetting setting) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;
        for (BlockPos lever : slThrottleLevers) {
            ThrottleLevers.setState(subLevel, lever, setting.lever());
        }
        for (BlockPos burner : slBurnerPositions) {
            HotAirBurners.setVolume(subLevel, burner, setting.volume());
        }
    }

    @Override
    public int burnerCount() {
        return slBurnerPositions.size();
    }

    @Override
    public int queryBalloonCapacity(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return -1;
        for (BlockPos burner : slBurnerPositions) {
            int c = HotAirBurners.queryBalloonCapacity(subLevel, burner);
            if (c > 0) return c;
        }
        return -1;
    }

    /** Reads the first throttle/burner pair — matches the per-tick lock-step write,
     *  so one sample reflects the whole assembly's state. */
    @Override
    public String describe(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return "";
        int throttle = slThrottleLevers.isEmpty() ? 0
                : ThrottleLevers.readState(subLevel, slThrottleLevers.get(0));
        int burnerVolume = slBurnerPositions.isEmpty() ? 0
                : HotAirBurners.readVolume(subLevel, slBurnerPositions.get(0));
        return "thr=" + throttle + " vol=" + burnerVolume + "m³";
    }
}
