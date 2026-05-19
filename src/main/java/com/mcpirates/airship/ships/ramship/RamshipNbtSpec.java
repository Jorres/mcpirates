package com.mcpirates.airship.ships.ramship;

import com.mcpirates.airship.ships.ShipNbtSpec;

public final class RamshipNbtSpec implements ShipNbtSpec {

    public static final RamshipNbtSpec INSTANCE = new RamshipNbtSpec();

    private static final int[] ANCHOR = {3, 4, 8};
    private static final int[] ANCHOR_TO_LEVER = {0, 0, 1};
    private static final int[][] LEFT_PROPS = {{-1, -1, +10}};
    private static final int[][] RIGHT_PROPS = {{+3, -1, +10}};
    private static final int[] FORWARD_PROP = {+1, -2, +12};

    private RamshipNbtSpec() {}

    @Override public String shipId() { return "ramship"; }
    @Override public int[] anchorNbtPos() { return ANCHOR; }
    @Override public int[] anchorToLever() { return ANCHOR_TO_LEVER; }
    @Override public int[][] leftPropellersLeverRel() { return LEFT_PROPS; }
    @Override public int[][] rightPropellersLeverRel() { return RIGHT_PROPS; }
    @Override public boolean nbtReversedL() { return true; }
    @Override public boolean nbtReversedR() { return true; }
    @Override public int[] forwardPropellerLeverRel() { return FORWARD_PROP; }
    @Override public Boolean nbtReversedF() { return true; }
}
