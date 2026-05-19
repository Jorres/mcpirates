package com.mcpirates;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * COMMON config (loaded on both sides; not synced — the value is read on the server
 * at menu-construction time, and on the client at screen-render time for the locked
 * overlay; both sides need it but they read it independently). Live changes take
 * effect the next time a {@code SheriffMenu} is opened.
 *
 * <p>{@link #CYCLE_LENGTH} — number of furled-bounty maps a single sheriff hands out
 * before retiring. The last map in the cycle is always stamped with
 * {@code is_galleon_bounty}. {@code 1} means the very first (and only) map is the
 * galleon; {@code 5} (the default) gives four regular outpost maps + one galleon.
 */
public final class MCPConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CYCLE_LENGTH = BUILDER
            .comment(
                    "Number of furled-bounty maps a single sheriff hands out before retiring.",
                    "The last map in the cycle is always the galleon-spawn map.",
                    "Range: 1..5. Set to 1 for galleon-on-first-map; 5 (default) gives four",
                    "regular outpost maps + the galleon as the fifth.")
            .defineInRange("cycle_length", 5, 1, 5);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private MCPConfig() {}

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    public static int cycleLength() {
        return CYCLE_LENGTH.get();
    }
}
