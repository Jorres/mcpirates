# Technical debt

Living list of design smells we want to revisit. One section per item: what's
wrong, why it bites, and the rough shape of a fix. Append as we find more.

---

## Ships are identified by the world position of their primary lever

**Why it's wrong.** The world lever position is an *incidental* property of a
placed ship — it's where the lever happened to land at worldgen time. We've
overloaded it as ship identity, ship-defeat key, crew-to-ship binding, airpad
target, cruise-altitude baseline, and dev-bridge ship filter. Two ships sharing
the pos (impossible in normal worldgen, but easy in GameTests that place
templates at controlled offsets — and `multiShipRehydrate` already maintains
~20-block spacing precisely to dodge this) collapse to one entity from every
system below. After assembly the lever is moved into the SubLevel anyway, so
the "anchor" we keep keying on is a coordinate that no longer holds the lever
— it survives only as a label.

**Where it leaks (consumers of the lever world pos as identity):**

- `MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY` — the NBT key on every spawned pirate.
  Stamped by `CaptainSpawner.spawnAnchoredPillager` (captain + cannoneers +
  crossbowmen) and `GroundCombatModule.makeVindicator` (ground captain).
- `CaptainDeath.onLivingDeath` reads the stamp to call
  `DefeatedAirships.markDefeated`.
- `AirshipLiftoffTrigger.maybeSpawnGroundCombat` reads the stamp (as
  `leverPosLong`) to adopt pre-existing captains after a restart instead of
  spawning duplicates.
- `AirshipRehydrator.tryRehydrate` re-reads the lever pos (as `airpad` in the
  SubLevel `userDataTag`) and uses it BOTH for ship identity AND to pull crew
  pillagers back into the brain by matching their `CAPTAIN_ANCHOR_NBT_KEY`
  stamps (`airpadLong`).
- `DefeatedAirships` is keyed by `Set<BlockPos>` of lever positions.
  `containsExact` and `isDefeated` (with its 3×3 chunk neighbourhood widening
  — a workaround the keying choice forces on us) both query it. The
  neighbourhood widening exists precisely because `findNearestMapStructure`
  returns the outpost's reported origin, which doesn't necessarily land in the
  same chunk as our lever.
- `Airship.airpadAnchor` field carries the lever pos through the brain.
- `AirshipBrain` uses `airpadAnchor` for three orthogonal jobs: the RETURN
  goal X/Z, the cruise-altitude baseline (`airpadAnchor.y + kind.cruiseRise()`),
  and the HOVER-vs-RETURN proximity check.
- `BridgeHandlers.airshipBrainState` accepts a `lever` JSON param to filter the
  diagnostic dump — the dev bridge treats it as ship identity from outside the
  JVM.
- GameTests (`AirshipGameTests.runRehydrateTest`, etc.) walk the arena bbox
  looking for the anchor BE and assert pre/post equality of `ship.airpadAnchor`.

**Shape of a fix.** Mint a stable ship ID at first assembly (UUID or counter),
stamp it on the crew + SubLevel `userDataTag`, and re-key `DefeatedAirships` /
crew lookup / dev-bridge filter on it. Keep airpad pos as a separate
positional field (RETURN goal + cruise-Y baseline only).

---

## Block-position deltas resolved via `AssemblyResult.offset()` instead of plot scans

**Where it happens.** Six call sites use `offset` (the `BlockPos` such that
`worldPos + offset = SubLevel-local pos`, returned by the Sim assembler):

- `AirshipLiftoffTrigger.activateShip`:
  - `worldMount.offset(offset)` — cannon mount SL pos (for assembly + later use).
  - `w.offset(offset)` — each throttle lever's SL pos.
  - `leftClutchPos.offset(offset)`, `rightClutchPos.offset(offset)`.
  - `pos.offset(offset)` → `slPrimaryAnchorPos` stamped on `userDataTag` so
    `AirshipRehydrator` can rebuild SL-local positions from kind deltas.
- `CaptainSpawner.scanSeatsInGlueBox` — translates the kind's NBT-frame
  `glueMin/glueMax` corners into SL space for the seat scan.

`HotAirBurners` already abandoned this pattern and scans the plot directly
(precisely because galleons wire levers far from their burners — adjacency
broke, scan is robust).

**Why it's wrong.** Each delta in `AirshipKind` is a hand-maintained mirror of
the NBT layout — every time a ship NBT changes shape, the delta table must
change in lockstep. Burners are the proof-by-existence that scanning is
robust enough; the rest of the layout is coasting on the same maintenance
burden for no structural reason.

**What can be rewired to scans:**

- **Cannon mounts** — `CannonMountBlockEntity` is a unique CBC class. Scan
  the plot, no ambiguity. Clean win.
- **Throttle levers + primary anchor** — `AnalogLeverBlockEntity` and
  `ThrottleLeverBlockEntity` are unique BE classes per kind (already
  encoded in `AirshipKind.throttleLeverKind`). Scan the plot, get the set.
  Brain treats throttles symmetrically (writes the same state to all), so
  there's no "primary" to disambiguate — and `slPrimaryAnchor` in
  `userDataTag` falls away with it (rehydrator scans on reload).
- **Seat-scan AABB** — already trivially redundant. The seat scan is bounded
  by the kind's glue corners, but every seat that survived assembly lives
  inside the SL plot bbox by construction. Just scan the whole plot like
  burners do. `glueMin/glueMax` then exist purely for the pre-assembly
  honey-glue body spawn — a single remaining use.

**What CAN'T be cleanly rewired:**

- **Left vs right clutch lever** — both are vanilla `LeverBlock`, which the
  brain inverts (`powered=true` ⇒ disengaged). Three possible
  discriminators, none clean:
  1. Marker block adjacent to each clutch (e.g. red wool = port, lime wool
     = starboard). Costs two extra blocks per ship NBT but lets the kind
     drop the deltas.
  2. Positional convention (lever with lower ship-local-X = port). Works
     today by accident; nothing enforces ships place their clutches
     symmetrically. A future kind with asymmetric layout silently breaks.
  3. Walk the redstone graph from each lever to find a `create:clutch` BE.
     Fragile against any decorative redstone in the hull.

**Shape of a fix.** Migrate mounts, throttles, seat AABB to plot scans
(deltes the deltas, removes `slPrimaryAnchor` from `userDataTag`). Decide
between clutch-marker-block vs. clutch-positional-convention as a separate
call. `AssemblyResult.offset()` would then survive only as a transient
inside `activateShip` (used to find the post-assembly seed once, then
discarded).

---

## `AirshipKind` interface exposes raw block-position deltas

**Why it's wrong.** `AirshipKind` is supposed to be the per-kind contract
for the brain — what role the ship plays, how it steers, what its combat
profile looks like. Instead it currently surfaces the *implementation*: a
flat list of NBT-frame block-pos deltas for engines, throttle levers,
left/right clutch levers, cannon mounts, plus the hull glue bbox. Every
new kind reimplements all of those; every consumer (assembly, rehydrator)
threads the resolved SubLevel-frame positions onto `Airship` as `sl*`
fields, and the brain mostly just hands those positions off to
`ClutchLevers.setPowered` / `HotAirBurners.setVolume`.

Two concrete pains:

1. Adding the ramship's third clutch could not go on the interface
   without forcing every other kind to define a `forwardClutchLeverDelta`
   they don't have. The escape hatch — keep the delta on `RamshipKind`
   only and have `RamMovement` look it up via SubLevel `userDataTag` +
   the kind's delta — works fine and is what shipped, but it bypasses
   the interface entirely. Any future kind-specific lever or block
   wanting brain-level engagement will follow the same pattern, which
   means `AirshipKind` keeps shrinking in relative weight as kinds grow.
2. The shape of those deltas leaks into the brain's logging
   (`"clutches=({},{})"`), debug overlays, and the rehydrator's
   re-registration call — none of which actually use the positions, they
   just thread them.

**Shape of a fix.** Pull the layout out of `AirshipKind` entirely and
move to a kind-supplied "controls" object (`ShipControls`?) that owns
clutch / throttle / engine / cannon-mount IO. The brain talks to it
through narrow verbs (`controls.setSteerLeftEngaged(bool)`,
`controls.setLiftBurnerVolume(int)`) and the impl resolves block
positions internally — possibly via a plot scan ([related debt entry
above](#block-position-deltas-resolved-via-assemblyresultoffset-instead-of-plot-scans))
or via `userDataTag` stamps as the ramship already does. After that,
`AirshipKind` is left with role + behaviour (`combat()`, `movement()`,
`orbitRadius()`, `cruiseRise()`, `groundCombat()`) — the small,
brain-shaped surface it was meant to be.

---

## Ground-combat gametests bypass `processNearbyAnchors`' real entry point

**Why it's wrong.** `groundCombatSpawnsForOnFootPlayer` and
`groundCombatRetreatsToDormantThenAirArrivalLifts` both call
`AirshipLiftoffTrigger.processNearbyAnchors` directly with a hand-supplied
`playerOnAirship` flag and player-derived (x, z). The flag was added solely
so the test could route without constructing a player; the production
caller is `checkAroundPlayer`, which derives the flag from
`Sable.HELPER.getContaining(player)` against a real `ServerPlayer`. By
calling one layer deeper, the tests skip:

- the on-airship/on-foot decision being driven by Sable's actual
  containment lookup;
- iteration over `level.players()` (zero-player worlds slip through
  silently in tests but never in prod);
- whatever future side-effects `checkAroundPlayer` accumulates.

Net result: the boolean parameter exists only to satisfy these two tests,
and the most interesting production code path is unverified.

**Shape of a fix.** Spawn a mock `ServerPlayer` via
`GameTestHelper.makeMockServerPlayerInLevel` (now used in
`RamshipTests.ramshipInterceptsMovingTarget`), position it on or off a
SubLevel as the test variant demands, and let the per-tick
`checkAroundPlayer` path fire naturally. Drop `playerOnAirship` from the
public signature of `processNearbyAnchors`; collapse the two-arg overload
back into the original single-arg internal helper.
