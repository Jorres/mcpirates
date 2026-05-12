package com.mcpirates.airship.kind;

/**
 * What kind of 0..15 lever a ship uses for its burner throttle. Both kinds expose an
 * integer state in [0, 15], but they're physically different blocks with different BE
 * classes — write paths diverge.
 *
 * <p>Read paths converge through {@link ThrottleLevers#readState(net.minecraft.world.level.Level,
 *   net.minecraft.core.BlockPos)} which auto-dispatches by BE class.
 */
public enum LeverKind {
    /** Create's {@code create:analog_lever} — used by airship_small and crossbow-board. */
    CREATE_ANALOG,
    /** Simulated-Project's {@code simulated:throttle_lever} — used by galleon. Same
     *  0..15 semantics, but a separate BE class with its own {@code setSignal(int)}
     *  setter (no reflection needed for writes). Note: respects the lever's
     *  {@code INVERTED} blockstate — passing N to setSignal stores N or 15-N. */
    SIMULATED_THROTTLE,
}
