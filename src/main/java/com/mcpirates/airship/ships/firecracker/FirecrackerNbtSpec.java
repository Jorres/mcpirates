package com.mcpirates.airship.ships.firecracker;

import com.mcpirates.airship.ships.ShipNbtSpec;

public final class FirecrackerNbtSpec implements ShipNbtSpec {

    public static final FirecrackerNbtSpec INSTANCE = new FirecrackerNbtSpec();

    private static final int[] ANCHOR = {3, 6, 10};
    private static final int[] ANCHOR_TO_LEVER = {+1, -2, 0};
    private static final int[][] LEFT_PROPS  = {{-3, +1, +7}};
    private static final int[][] RIGHT_PROPS = {{+3, +1, +7}};

    private FirecrackerNbtSpec() {}

    @Override public String shipId() { return "firecracker"; }
    @Override public int[] anchorNbtPos() { return ANCHOR; }
    @Override public int[] anchorToLever() { return ANCHOR_TO_LEVER; }
    @Override public int[][] leftPropellersLeverRel() { return LEFT_PROPS; }
    @Override public int[][] rightPropellersLeverRel() { return RIGHT_PROPS; }
    @Override public boolean nbtReversedL() { return true; }
    @Override public boolean nbtReversedR() { return true; }
}
