# Design — MC Pirates

## Goal

Pillager outposts spawn a pre-built pirate airship next to the watchtower. When a player
approaches, the airship lifts off into a Sable SubLevel and engages — orbits the player at
range, fires its Create Big Cannons cannon, breaks contact and returns to its airpad when
the player leaves. Killing the captain on board marks that ship "defeated" so the bounty
system can hand out a different target next time.

Villages additionally get a sheriff station injected into the residential ring (vanilla,
CTOV, T&T pools). The sheriff villager sells *furled bounty scrolls*; right-clicking a
scroll resolves it to a treasure map pointing at the nearest still-undefeated airship.

## Architecture (as built, see `decisions.md` for the change history)

```
Worldgen
├─ data/mcpirates/worldgen/structure/<kind>_outpost.json
│    one mcpirates:permitted_ship_outpost per ship kind. Vanilla pillager outposts
│    stay vanilla (ship-less); ship outposts spawn only at chunks permitted by
│    OutpostPermits when a bounty scroll unfurls (see runtime block below).
├─ data/mcpirates/structure/<ship>.nbt / <ship>_pad.nbt
│    the parked ship + its pad piece (jigsaw-attached at structure-gen time).
└─ data/mcpirates/lithostitched/worldgen_modifier/sheriff_station_{vanilla,ctov,kaisyn}.json
     inject mcpirates:sheriff_station into the village house pools of vanilla
     biomes, CTOV's house pools, and T&T's kaisyn house pools respectively.

Runtime
├─ AirshipLiftoffTrigger (server tick)
│    scans loaded chunks for dormant analog levers near players, spawns the
│    honey-glue bounding entity, calls AirshipAssembler.
├─ AirshipAssembler
│    wraps SimAssemblyHelper.assembleFromSingleBlock — yields a Sable SubLevel
│    holding the ship's blocks at "plot" coords; world-rendered pos comes from
│    subLevel.logicalPose().transformPosition().
├─ CaptainSpawner
│    spawns one tagged pillager (the captain) + one crewmate into the SubLevel,
│    anchored to ride the ship's pose via Sable's sable$setPlotPosition.
├─ AirshipBrain (server tick, per registered ship)
│    state machine LIFTOFF → PURSUE → RETURN → HOVER. Tank-steers the propellers
│    via clutch levers, throttles the burner via the analog lever, aims the
│    cannon at the player. Re-acquires its SubLevel by UUID each tick so
│    chunk unload/reload doesn't strand it. Re-anchors captain + crewmate each
│    tick because Sable's plot-position field isn't persisted to entity NBT.
├─ CaptainDeath (LivingDeathEvent)
│    drops the captain_seal at world-rendered coords; marks the ship's airpad
│    anchor in the DefeatedAirships SavedData.
├─ SheriffTrades (VillagerTradesEvent)
│    registers seal → emeralds + emeralds → furled_bounty trades on the
│    mcpirates:sheriff profession.
├─ FurledBountyItem.use
│    server-side: locate nearest pirate_outpost via findNearestMapStructure,
│    skip outposts in DefeatedAirships (perturb origin + retry up to N times),
│    swap the scroll in hand for a filled bounty map.
└─ SheriffLifetimeCap (TradeWithVillagerEvent + EntityTickEvent)
     caps each sheriff to 5 lifetime scroll sales; re-pegs the offer to
     out-of-stock every 100 ticks once the cap is reached, surviving restocks.
```

## Non-goals (v0.x)

- No procedural ship generation — premade NBT only.
- No multiplayer-tuned netcode beyond what Sable / Create already provide.
- No new pillager mob types — captain is a tagged vanilla `Pillager` with
  `NoAi=true`, anchored to the deck via Sable's plot-position mechanism.
- No loot tables on the ship (boarding is not the intended interaction; bounty
  maps are the gameplay-aligned reward path).

## Outstanding gameplay knobs

- Re-enable the "player must be on a SubLevel" filter in
  `AirshipLiftoffTrigger.checkAroundPlayer` + `AirshipBrain.findEnemyPlayerOnAirship`
  (both currently disabled for creative testing).
- Phase 4: spent bounty trades should disappear from the trade GUI entirely,
  not just grey out as out-of-stock.
- Real textures (currently programmatic placeholders for captain_seal,
  furled_bounty, bounty_board, sheriff overlay).
