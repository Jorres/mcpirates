package com.mcpirates;

/**
 * Single source of truth for mcpirates' NBT- and tag-string identifiers.
 *
 * <p>Anything that ends up serialised — scoreboard tags on entities, NBT keys on
 * an entity's {@code persistentData}, NBT keys on level-attached SavedData —
 * needs the exact same string on read and write. Defining the strings here lets
 * a rename happen in one place, and lets a future static analyser find all
 * call sites with a simple usage search.
 *
 * <p>Format convention: every key is namespaced under {@code mcpirates.} (note
 * the dot, not a colon — these aren't ResourceLocations, just opaque strings,
 * and a dotted prefix avoids collisions with vanilla tags / other mods).
 *
 * <p>If you add a new persisted string, add it here first, then reference the
 * constant. Do not inline new literals.
 */
public final class MCPDataKeys {

    /** Scoreboard-style tag added to a pillager that's a "pirate captain" — the
     *  guy whose death drops a {@code captain_seal} (see {@code CaptainDeath})
     *  and marks his airship in {@code DefeatedAirships}. Crewmates do NOT get
     *  this tag. */
    public static final String CAPTAIN_TAG = "mcpirates.pirate_captain";

    /** Key into a captain pillager's {@code persistentData} compound holding
     *  the world {@code BlockPos} (as a packed long via {@code BlockPos.asLong})
     *  of his airship's analog lever — i.e. the airpad anchor in world space.
     *  Read by {@code CaptainDeath} to know which airship to mark defeated.
     *
     *  <p>Captain's own {@code position()} is plot-local (~20M block coord
     *  inside the SubLevel storage region), so it can't tell us where the
     *  airship is in the *world*. The lever pos can. */
    public static final String CAPTAIN_ANCHOR_NBT_KEY = "mcpirates.airship_anchor";

    /** Key into a sheriff villager's {@code persistentData} compound counting
     *  the number of bounty scrolls he's sold over his entire lifetime. Read by
     *  {@code SheriffLifetimeCap} to enforce a hard cap that survives
     *  work-cycle restocks. */
    public static final String SHERIFF_SCROLLS_SOLD_NBT_KEY = "mcpirates.bounty_scrolls_sold";

    /** Scoreboard tag on a player who wants pirate AI to ignore them — for
     *  visiting a running gametest server without becoming the target. Brain
     *  skips any player carrying this tag in {@code findEnemyPlayerOnAirship}.
     *  Apply via {@code /tag @s add mcpirates.test_observer}. */
    public static final String TEST_OBSERVER_TAG = "mcpirates.test_observer";

    private MCPDataKeys() {}
}
