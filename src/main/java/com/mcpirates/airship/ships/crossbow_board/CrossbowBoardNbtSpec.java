package com.mcpirates.airship.ships.crossbow_board;

import com.mcpirates.airship.ships.ShipNbtSpec;

public final class CrossbowBoardNbtSpec implements ShipNbtSpec {

    public static final CrossbowBoardNbtSpec INSTANCE = new CrossbowBoardNbtSpec();

    private static final int[] ANCHOR = {2, 3, 8};
    private static final int[] ANCHOR_TO_LEVER = {0, 0, 1};
    private static final int[][] LEFT_PROPS = {
            {-2, 0, -7},
            {-2, 0, -3}};
    private static final int[][] RIGHT_PROPS = {
            {+3, 0, -7},
            {+3, 0, -3}};

    private CrossbowBoardNbtSpec() {}

    @Override public String shipId() { return "crossbow_board"; }
    @Override public int[] anchorNbtPos() { return ANCHOR; }
    @Override public int[] anchorToLever() { return ANCHOR_TO_LEVER; }
    @Override public int[][] leftPropellersLeverRel() { return LEFT_PROPS; }
    @Override public int[][] rightPropellersLeverRel() { return RIGHT_PROPS; }
    @Override public boolean nbtReversedL() { return false; }
    @Override public boolean nbtReversedR() { return false; }
}
