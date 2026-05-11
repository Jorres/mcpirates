package com.mcpirates.util;

import net.minecraft.util.RandomSource;

public final class FunnyNames {
    private static final String[] SHERIFF_GIVEN = {
            "Buck", "Dusty", "Mabel", "Cactus", "Beans", "Howdy", "Tumble", "Maple",
            "Boot", "Nugget", "Pickles", "Juniper", "Biscuit", "Copper", "Muffin", "Rattler"
    };

    private static final String[] SHERIFF_FAMILY = {
            "Oatwhistle", "Tumblepickle", "Haystack", "Crumbbucket", "Mudbritches",
            "Snoredust", "Wobblehat", "Cobblesprocket", "Puddleboots", "Drybarrel",
            "Noodlelasso", "Dustmuffin", "Saddlebiscuit", "Tinspoon", "Wigglebuckle", "Goosefern"
    };

    private static final String[] CAPTAIN_STYLE = {
            "Barnacle", "Pegleg", "Grog", "Bilge", "Cutlass", "Scurvy", "Salty", "Crab",
            "Shanty", "Cannon", "Rum", "Moldy", "Sea", "Plank", "Kraken", "Gull"
    };

    private static final String[] CAPTAIN_FAMILY = {
            "Beard", "Boot", "Belly", "Sniffer", "McSoggy", "Plankwalker", "Rumblegut",
            "Caskbones", "Barnacles", "Seaboots", "Oarwhistle", "Gullchewer", "Brinechin",
            "Kelpcoat", "Drifttooth", "Crumbbeard"
    };

    private FunnyNames() {}

    public static String nextSheriffName(RandomSource random) {
        return "Sheriff " + pick(random, SHERIFF_GIVEN) + " " + pick(random, SHERIFF_FAMILY);
    }

    public static String nextPirateCaptainName(RandomSource random) {
        return "Captain " + pick(random, CAPTAIN_STYLE) + pick(random, CAPTAIN_FAMILY);
    }

    private static String pick(RandomSource random, String[] values) {
        return values[random.nextInt(values.length)];
    }
}

