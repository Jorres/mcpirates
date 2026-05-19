package com.mcpirates.airship.ships;

/**
 * Pure-Java declaration of a ship's NBT-frame layout — the part nbtcheck tests
 * validate against the on-disk structure NBT. Implementations live one-per-folder
 * ({@code <Ship>NbtSpec.INSTANCE} inside each {@code airship/ships/<ship>/}), and each
 * ship's {@link com.mcpirates.airship.interfaces.AirshipKind#nbtSpec()} returns its own.
 *
 * <p>No Minecraft imports — keeps nbtcheck JUnit tests lightweight, and lets the spec
 * data drift be caught at unit-test time rather than gametest time.
 */
public interface ShipNbtSpec {

    /** Ship name string ({@code "airship_small"}, etc.) — must match the structure NBT
     *  filename and the kind's {@code name()}. */
    String shipId();

    /** NBT-frame position of the {@code mcpirates:ship_anchor} block. */
    int[] anchorNbtPos();

    /** Lever offset from the anchor (NBT-frame). The lever is the actuator-resolution
     *  reference passed through {@code makeControls}/{@code layoutAt}. */
    int[] anchorToLever();

    /** Port-side propellers, lever-relative NBT deltas. */
    int[][] leftPropellersLeverRel();

    /** Starboard-side propellers, lever-relative NBT deltas. */
    int[][] rightPropellersLeverRel();

    /** NBT palette default for the REVERSED block state, port side. All props on one
     *  side must share the same default (current convention; revisit if a kind needs
     *  per-prop control). */
    boolean nbtReversedL();
    boolean nbtReversedR();

    /** Forward-axis propeller (ramship-style ramming prop). {@code null} if the ship
     *  has only the two outboard props. */
    default int[] forwardPropellerLeverRel() { return null; }

    /** NBT default for the forward propeller's REVERSED. {@code null} when there's
     *  no forward prop. */
    default Boolean nbtReversedF() { return null; }
}
