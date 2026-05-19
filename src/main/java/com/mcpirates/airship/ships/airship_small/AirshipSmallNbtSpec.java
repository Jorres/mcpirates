package com.mcpirates.airship.ships.airship_small;

import com.mcpirates.airship.ships.ShipNbtSpec;

public final class AirshipSmallNbtSpec implements ShipNbtSpec {

    public static final AirshipSmallNbtSpec INSTANCE = new AirshipSmallNbtSpec();

    private static final int[] ANCHOR = {3, 3, 5};
    private static final int[] ANCHOR_TO_LEVER = {0, 0, 1};
    private static final int[][] LEFT_PROPS = {{-2, -2, 3}};
    private static final int[][] RIGHT_PROPS = {{+2, -2, 3}};

    private AirshipSmallNbtSpec() {}

    @Override public String shipId() { return "airship_small"; }
    @Override public int[] anchorNbtPos() { return ANCHOR; }
    @Override public int[] anchorToLever() { return ANCHOR_TO_LEVER; }
    @Override public int[][] leftPropellersLeverRel() { return LEFT_PROPS; }
    @Override public int[][] rightPropellersLeverRel() { return RIGHT_PROPS; }
    @Override public boolean nbtReversedL() { return false; }
    @Override public boolean nbtReversedR() { return false; }
}
