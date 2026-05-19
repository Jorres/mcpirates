package com.mcpirates.airship.ships.galleon;

import com.mcpirates.airship.ships.ShipNbtSpec;

public final class GalleonNbtSpec implements ShipNbtSpec {

    public static final GalleonNbtSpec INSTANCE = new GalleonNbtSpec();

    private static final int[] ANCHOR = {3, 9, 13};
    private static final int[] ANCHOR_TO_LEVER = {+1, -1, 0};
    private static final int[][] LEFT_PROPS = {
            {-1, -2, -5},
            {-1, -2, -2},
            {-1, -2, +3},
            {-1, -2, +6}};
    private static final int[][] RIGHT_PROPS = {
            {+4, -2, -5},
            {+4, -2, -2},
            {+4, -2, +3},
            {+4, -2, +6}};

    private GalleonNbtSpec() {}

    @Override public String shipId() { return "galleon"; }
    @Override public int[] anchorNbtPos() { return ANCHOR; }
    @Override public int[] anchorToLever() { return ANCHOR_TO_LEVER; }
    @Override public int[][] leftPropellersLeverRel() { return LEFT_PROPS; }
    @Override public int[][] rightPropellersLeverRel() { return RIGHT_PROPS; }
    @Override public boolean nbtReversedL() { return true; }
    @Override public boolean nbtReversedR() { return true; }
}
