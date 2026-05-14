package com.mcpirates.airship.kind;

/**
 * Burner actuator constants and helpers. {@link LiftSetting} is the pair the brain
 * writes each tick; gas output = {@code volume * lever / 15}.
 */
public final class LiftMath {

    /** Aeronautics config default ({@code hot_air_burner_max}). */
    public static final int BURNER_MAX_VOLUME = 500;
    /** Hard-coded floor inside {@code HotAirBurnerBlockEntity}. */
    public static final int BURNER_MIN_VOLUME = 5;
    /** Wrench-UI quantisation step; writes off-step round on first wrench-open. */
    public static final int BURNER_VOLUME_STEP = 5;

    public record LiftSetting(int lever, int volume) {}

    private LiftMath() {}

    /** Snap a volume down to the nearest {@link #BURNER_VOLUME_STEP}, clamped to
     *  {@link #BURNER_MIN_VOLUME}. */
    public static int snapVolume(int v) {
        return Math.max(BURNER_MIN_VOLUME, (v / BURNER_VOLUME_STEP) * BURNER_VOLUME_STEP);
    }

    /** Bang-bang: lever=15 at the snapped ceiling. */
    public static LiftSetting maxLift(int effectiveMax) {
        return new LiftSetting(15, snapVolume(effectiveMax));
    }
}
