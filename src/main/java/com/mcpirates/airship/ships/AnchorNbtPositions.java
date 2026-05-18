package com.mcpirates.airship.ships;

import java.util.Map;

/**
 * Canonical NBT-frame coordinates of each ship's {@code mcpirates:ship_anchor} block.
 * Pure-Java (no Minecraft imports) so JUnit nbtcheck tests can load it without dragging
 * the modded classpath in. Values must match the anchor block actually baked into each
 * ship's source NBT; the {@code ShipAnchorTest} JUnit test enforces that invariant.
 */
public final class AnchorNbtPositions {

    public static final Map<String, int[]> BY_NAME = Map.of(
            "airship_small",  new int[]{3, 3,  5},
            "crossbow_board", new int[]{2, 3,  8},
            "galleon",        new int[]{3, 9, 13},
            "ramship",        new int[]{3, 4,  8}
    );

    private AnchorNbtPositions() {}
}
