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
  crossbowmen).
- `CaptainDeath.onLivingDeath` reads the stamp to call
  `DefeatedAirships.markDefeated`.
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
`orbitRadius()`, `cruiseRise()`) — the small, brain-shaped surface it
was meant to be.

---

## `RamControls` duplicates `TankSteerControls` instead of composing it

**Why it's wrong.** Both controllers now consume `TurnPolicy.decide()` and translate
its `Regime` to clutch + prop-REVERSED writes. The L/R outboard handling in
`RamControls` is mechanically identical to `TankSteerControls` — the only
ramship-unique pieces are (a) the third forward propeller (engaged in ALIGNED +
TANK_STEER, disengaged in COUNTER_ROTATE, flipped during RETREAT), (b) the
retreat phase wrapper, and (c) bbox+stuck collision detection. The L/R outboard
code is copy-paste.

**Where it bites.** Any future change to the regime → hardware mapping
(adding a fourth regime, changing how TANK_STEER picks sides, etc.) has to be
edited in two places. Already nearly tripped on it twice — once when adding
counter-rotate to `TankSteerControls`, once when wiring up `TurnPolicy`.

**Fix shape.** Have `RamControls` *compose* a `TankSteerControls` for the
outboards. Its `applySteering` either delegates straight through (ALIGNED /
TANK_STEER / COUNTER_ROTATE during PURSUE), or overrides with the retreat
pattern when `now < retreatUntilTick`. Forward propeller is handled in a
parallel ramship-only block. `release()` and `isActive()` similarly delegate
or supplement. Tradeoff: retreat needs to write outboard REVERSED state
directly, which means breaking the TankSteer encapsulation a little (either
expose `slPropsL/slPropsR/nbtReversedL/nbtReversedR` package-private, or pass
a "force pattern" override into TankSteer). Pick whichever is uglier when we
do the refactor.


---

## Hand-rolled SubLevel velocity tracking in `RamMovement`

**Why it's wrong.** `RamMovement.computeGoal` should be able to ask Sable
"what's the target SubLevel's current linear velocity?" via
`Sable.HELPER.getVelocity(level, subLevel, pos, dest)`. That API exists, has
the right signature, and the `ServerSubLevel` branch reads
`RigidBodyHandle.getLinearVelocity()` from the physics handle. **It returns
zero every call** for hot-air-balloon SubLevels, even when the ship is clearly
moving (positions change tick to tick, our hand-rolled position-delta confirms
non-zero velocity).

We worked around it by tracking position deltas ourselves: `LAST_TARGET_SAMPLE`
map in `RamMovement` keyed by ramship SubLevel UUID, stores
`(targetId, tx, tz, tick)`, computes `vx = (tx - prev.tx) / dt`. This is the
input the intercept solver actually uses. (The Sable comparison column used to
ride along in the goal log as `Vsable=...` but was always zero — removed.)

**Why it bites.** Two parallel velocity sources for SubLevels in our codebase.
The official one (Sable's API) silently returns wrong data; our fallback works
but means every consumer that wants target velocity has to re-implement the
position-delta tracking. Any future kind that needs target velocity will hit
the same trap and either silently underlead (if it trusts Sable's zero) or
copy our hand-rolled code (duplication).

**Investigation (open).** Initial "kinematic teleport" hypothesis was *wrong* —
verified by reading the Sable + Aeronautics sources:
- `ServerBalloon.applyForces` accumulates force × dt into a `ForceTotal`, then
  Sable's tick loop calls `handle.applyLinearAndAngularImpulse` → pipeline →
  `Rapier3D.applyForceAndTorque` → `rb.apply_impulse(...)`. So it goes through
  Rapier's impulse machinery, not a pose-set bypass.
- SubLevels are created as *dynamic* (not kinematic) rigid bodies
  (`RigidBodyBuilder::dynamic()` in `rapier/lib.rs::createSubLevel`).
- `pipeline.readPose` pulls the post-step pose from Rapier — so positions are
  genuinely Rapier-integrated, not externally teleported.

If impulses are applied AND integration runs, `linvel()` should reflect the
integrated velocity. Yet our diagnostic shows `Vsable=(0.000,0.000)` every
read. **Real `linvel` IS non-zero** in some contexts — the existing
`mcp__minecraft__airship_brain_state` bridge calls the same
`RigidBodyHandle.getLinearVelocity()` and returned `[-0.002, -0.074, -0.011]`
m/s for ships in LIFTOFF, etc. So the API itself works.

**Remaining theories to bisect:**
1. **Sleep thresholds.** Rapier bodies sleep when normalized linvel stays under
   `0.15` for some ticks. Sleeping bodies have `linvel = 0`. For steady-state
   balloons hovering near equilibrium altitude, lift = gravity, *net* impulses
   per step are tiny — net linvel may stay below threshold despite the body
   appearing to drift along east via accumulated tiny impulses. Worth logging
   `rb.is_sleeping()` alongside `linvel` to confirm.
2. **`pos` argument interpretation.** Sable's `getVelocity` takes a `pos` it
   transforms via `pose.transformPosition(pos, dest).sub(pose.position())` and
   names the result `localPos`. The transform direction (local→world vs
   world→local) is ambiguous from the API alone. We pass `targetShip.logicalPose().position()`
   which is *world* CoM — if the helper expects *local* coords, our caller is
   buggy and the math produces near-zero by coincidence.
3. **Stale-read timing.** Maybe `getLinearVelocity` is called between physics
   substeps when the integrator hasn't yet committed the impulse to `linvel`.
   Less likely (would also affect the brain-state bridge) but easy to rule out
   by timestamping.

**Next concrete step.** Add a one-shot debug command (or a transient log line
in `RamMovement`) that prints, for the *ramship's own* SubLevel:
- `is_sleeping()` (needs a new Rapier3D JNI export — currently not exposed)
- `getLinearVelocity` from `RigidBodyHandle`
- The position-delta we compute
Run for ~30 ticks during steady cruise. If `is_sleeping()` is true while
position deltas are non-zero → theory (1) confirmed; fix is to bump activation
thresholds in `createSubLevel` or call `wake_up` more aggressively from the
force application path. If sleeping is false and `linvel` is non-zero — our
caller is wrong (theory 2). Either way, the workaround can be removed and
Sable's API used directly.

**Fix shape (after diagnosis):**
- If theory 1: tune `activation_params` for SubLevel rigid bodies, OR set
  `wake_up=true` unconditionally in `ForceTotal.applyForces` (instead of only
  when force changed).
- If theory 2: pass a *local* (CoM-relative) `pos`, or pass `(0,0,0)` to skip
  the angular contribution entirely when we just want CoM linvel.
- Either: drop the `LAST_TARGET_SAMPLE` map + position-delta tracking from
  `RamMovement`; let `Sable.HELPER.getVelocity` be the single source of truth.

---

## `Sable.HELPER.getContaining` misses riders of moving contraptions

**Why it's wrong.** `Sable.HELPER.getContaining(entity)` is the canonical
"which SubLevel is this entity riding?" lookup, and the docstring on the
removed `AirshipLiftoffTrigger.findSubLevelByWorldBounds` flagged it: the
helper keys off the SubLevel's *static plot chunk*, not its world-rendered
pose. Once a SubLevel translates away from its plot (i.e. the moment a ship
lifts off), `getContaining` returns `null` for every entity actually riding
it. The "right" answer is right there in the SubLevel's world-frame bbox; the
API just doesn't look at it.

**Where it bites us today.**

- `AirshipBrain.findEnemyShip` now falls back to a private
  `findSubLevelByWorldBounds` (point-test against every SubLevel's
  `boundingBox()`) when `getContaining` returns null. Without that fallback the
  ramship couldn't identify its victim once airborne, and `RamMovement`
  silently dropped into the on-foot orbit branch — caught by the
  `ramshipInterceptsMovingTarget` gametest at commit time.
- Anything else that needs "is target X riding ship Y" has to either replicate
  the bbox scan or accept false negatives on moving SubLevels. The signal is
  load-bearing for combat dispatch (orbit vs. ram), so the false-negative case
  is silently-wrong, not loudly-broken.

**Shape of a fix.** Two paths:

1. Upstream the bbox-aware lookup into Sable's `SubLevelHelper` — either as a
   second method (`getContainingByWorldPos`) or by making `getContaining`
   itself bbox-aware (the plot-chunk fast path is only correct for stationary
   SubLevels, which is the degenerate case). Then drop our private helper.
2. Pull the bbox helper out of `AirshipBrain` into a shared
   `com.mcpirates.airship.util.SubLevelLookup` so other call sites (if any
   appear) reuse it instead of re-rolling. Lower-effort but leaves Sable's
   API misleading for any future caller who reads the obvious-looking
   `getContaining` and assumes it works.

(1) is the right answer; (2) is the holding pattern until we have appetite
to send a PR.

---

## `DefeatedAirships.markDefeated` duplicate path probably unreachable

The `added == false` branch fires only if the same anchor is marked twice. A
captain dies once and anchor positions are unique per ship, so under current
invariants this can't happen — the branch logs a WARN today specifically to
flag invariant breakage. Worth replacing with an assertion once we've burnt in
enough play hours to be sure GameTests and save corruption can't reach it.
Related: "Ships are identified by the world position of their primary lever".

---

## `CaptainDeath` if-condition — strange, probably based on ground combat, may be outdated

`CaptainDeath.onLivingDeath` splits its log into two INFO templates around
`if (data.contains(CAPTAIN_ANCHOR_NBT_KEY) && level instanceof ServerLevel sl)`.
The `ServerLevel` half is dead (we already returned on `isClientSide`), and
the anchor-key half is a "pre-Phase-2 captain" hedge that no current spawn
path can trigger — likely a holdover from when captains could die on the
ground before ever being crewed onto an airship. Worth collapsing to a single
INFO and replacing the guard with an assert or a fail-loud WARN. Don't just
drop the check unconditionally: a missing key reads as `0L` and would poison
`DefeatedAirships` with anchor `(0,0,0)`.
