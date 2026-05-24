# Decision log

Append-only. Each entry: date, decision, rationale, alternatives considered.

---

## 2026-05-10 — Single-loader (NeoForge only), not multiloader

**Decision.** Build a single NeoForge module, not a Fabric/NeoForge split.

**Why.** Aeronautics' multiloader split exists because Sable + Create-the-rendering-stack each
need it. We're a downstream gameplay addon — Fabric Aeronautics doesn't exist (yet), so a
multiloader split would be all overhead, no payoff.

**Alternatives.** Mirror Aeronautics' `common/neoforge` split. Rejected as premature.

---

## 2026-05-10 — `libs/` flatDir for Create Aeronautics jar instead of public maven dep

**Decision.** Aeronautics is consumed via a local-jar drop into `libs/`, picked up by a
`flatDir` Gradle repo and a `fileTree` `compileOnly`/`runtimeOnly` dependency.

**Why.** Aeronautics' build only publishes to a private maven (`maven.ryanhcode.dev/private`,
auth required). The released jar is on Modrinth/CurseForge, but those don't serve a versioned
maven coordinate for a clean `implementation(...)` line.

**Alternatives.**
- Composite Gradle build with `Simulated-Project` as an `includeBuild`. Heavy: pulls in 3 mods,
  long sync, version drift becomes our problem.
- `publishToMavenLocal` from Simulated-Project then depend by coordinate. Works but adds a
  manual step the same as `libs/` drop, with more ceremony.
- Modrinth maven (`maven.modrinth:create-aeronautics:...`). Worth revisiting if Aeronautics
  publishes a version we can pin against — see `docs/dependencies.md`.

---

## 2026-05-10 — Hard-required dependency on Create, Sable, Flywheel, Aeronautics

**Decision.** All four declared `type = "required"` in `neoforge.mods.toml`.

**Why.** Without Aeronautics there is no airship; without Sable there is no contraption motion;
Create + Flywheel are transitive requirements of those. Soft-dep would just cause confusing
crashes on missing classes at runtime.

---

## 2026-05-10 — Lithostitched as the only outpost-related hard dep; pinned to 1.7.0

**Decision.** `lithostitched = required, [1.7.0,)` in `neoforge.mods.toml`. Towns and Towers,
Yungs Bridges, CTOV are explicitly NOT hard deps even though they touch outposts.

**Why.** Lithostitched provides the `lithostitched:add_template_pool_elements` modifier that
appends our sheriff station into vanilla / CTOV / T&T village house pools (and was the
historical route for the airship-into-outpost wiring before we moved to a direct pool
override — see the `base_plates` entry below). The single dependency lets us coexist
with T&T / Yungs / CTOV instead of overriding them or hard-depping on each.

**Why 1.7.0 specifically.** Lithostitched 1.7.3 (the latest 1.21.1-neoforge-21.1 build at the
time) crashes the dev environment at registry-init with:
> `IllegalStateException: Found unused register callbacks` thrown from
> `Registrate.AbstractRegistrate.onRegister` during Create's RegisterEvent dispatch.
The error doesn't fire with 1.7.0; same Create / NeoForge / classpath otherwise. Cause not
fully isolated — best guess is a 1.7.3 mixin or registry-touching change that conflicts with
Create 6.0.10-280's Registrate version. 1.7.0 is also what the user's live `.minecraft` has,
so it's a known-good baseline.

**How to apply.** When upgrading lithostitched, boot dev MC once after the bump and watch the
log for the same exception before assuming compatibility.

---

## 2026-05-10 — Airship integration: override `base_plates` pool, airship as outpost jigsaw piece

**Decision.** The airship is a piece in the outpost's *own* jigsaw graph — not a separate
worldgen feature, not a runtime placement. We ship a datapack override at
`data/minecraft/worldgen/template_pool/pillager_outpost/base_plates.json` that replaces the
vanilla pool with a single entry: `mcpirates:base_plate_with_airship`. The override variant
is a clone of vanilla `pillager_outpost/base_plate.nbt` whose east-facing `feature_plates`
jigsaw at local `(15, 0, 8)` is repointed to `mcpirates:airships` pool, target
`mcpirates:airship_keel`. The airship piece matches that connector with a west-facing
jigsaw at its own local `(0, 0, 5)`.

**Why pool override, not lithostitched-add.** An earlier iteration used
`lithostitched:add_template_pool_elements` with weight 99 to inject our base_plate variant
alongside vanilla. That topped out at ~98% airship coverage (vanilla weight 1, ours 99 →
99/100). To get every outpost we needed to *displace* the vanilla entry, not outweigh it,
and lithostitched's per-element `Codec.intRange(1, 150)` caps the weight at 150 — never
enough to fully eclipse vanilla. A direct pool override is the simplest reliable path.
Other mods that extend `pillager_outpost/base_plates` would still be added on top by
lithostitched after our override loads.

**Why airship as jigsaw piece, not runtime placement.** Three earlier approaches failed:
1. *Add airship to `pillager_outpost/features` pool* — features sit under the watchtower's
   claimed airspace; every 5×10×10 ship roll got bbox-rejected.
2. *Custom Feature + biome modifier* — features can write only to the chunk currently being
   decorated; cross-chunk writes log "Detected setBlock in a far chunk" and are silently
   dropped. Our airship spans into the chunk east of the outpost start chunk.
3. *`ChunkEvent.Load` runtime listener* — works mechanically (cross-chunk writes are fine
   post-worldgen) but force-loading neighboring chunks to compute terrain height risks
   cascading chunk gen and is fragile compared to data-only worldgen.

The chosen approach has no Java code path, no events, no chunk hooks — pure datapack/JSON +
NBTs. Watchtower bbox `(0..14, 1..21, 0..14)` vs airship `(16..25, 0..12, 3..12)` relative
to base_plate origin — no X overlap, so the jigsaw assembler accepts both pieces in the
same generation pass.

**Cost.** We sacrifice the eastern feature_plate (so 1 fewer cage/tent on the east side of
the outpost) per outpost. West and south feature_plate jigsaws are untouched.

**How to apply.** Adding more airship designs / variants: add them as elements in
`mcpirates:airships` pool with weights. Adding airships to *other* structure types
(villages, woodland mansions): clone *their* base / start pool entry the same way.

---

## 2026-05-11 — Bounty pipeline: shape + gotchas worth not forgetting

**Decision.** The bounty loop is split into independent pieces so each can evolve
without touching the others: a sheriff villager (workstation = our POI), a tagged
captain pillager (spawned into the airship's SubLevel via `CaptainSpawner`), and
a death-drop handler (`CaptainDeath` → seal at the world-rendered position, not
the captain's plot-local position). See `docs/design.md` for the current pipeline
diagram.

**Why a tagged vanilla pillager, not a custom entity.** A custom `EntityType` requires
its own model, texture, renderer, attributes, AI registration, and spawn-egg item —
a full session of work for what we can express as `entity.addTag(String)` + a
`getTags()` check on death. The tag is namespaced (`MCPDataKeys.CAPTAIN_TAG`,
formatted `mcpirates.…`) so we won't clash with vanilla or other mods.

**POI block-state registration — NeoForge does it for us, don't do it twice.** An
earlier draft of `MCPPoiTypes` hooked `FMLCommonSetupEvent` and reflectively called
`PoiTypes.registerBlockStates(Holder, Set<BlockState>)` (package-private in Mojang's
1.21.1 mappings). The dev client immediately logged
`IllegalStateException: Block{mcpirates:bounty_board} is defined in more than one PoI type`
because NeoForge had already added the block to `TYPE_BY_STATE` as part of the
registry-add callback. The fix: **do nothing**. Registering a `PoiType` with non-empty
`matchingStates` via `DeferredRegister` is sufficient on NeoForge 21.1.228 — the
auto-registration is part of NeoForge's `PoiTypes` patch. Don't ship an AT, don't reflect.

**Peaceful-mode footgun.** Default `runs/server/server.properties` shipped with
`difficulty=peaceful`. Pillagers `discard()` themselves within a tick under peaceful
regardless of `PersistenceRequired`, so the captain was killed silently on every
test. Changed default to `difficulty=easy`. If future tests see "captain spawned but
`@e[type=pillager]` finds nothing," check difficulty FIRST — easy to mis-diagnose as
a SubLevel-visibility race.

---

## 2026-05-11 — Captain rides ship via `sable$setPlotPosition`, not the retain tag

**Decision.** Anchor pillagers to a moving Sable SubLevel by calling
`((EntityStickExtension) entity).sable$setPlotPosition(plotVec3)` immediately after
`Level.addFreshEntity`. The `sable:retain_in_sub_level` entity-type tag is **not enough on
its own** — it only stops Sable's `kickEntity` from moving wandering entities out of
plot space. The actual ship-riding rebind is in `Entity.tick` (mixed by Sable): every
tick, if `plotPosition != null`, the entity's world position is set to
`subLevel.logicalPose().transformPosition(plotPosition)`.

**Why this matters.** First implementation had captain at NoGravity/NoAI + plot-local
spawn coords, hoping vanilla physics would do the right thing inside the SubLevel
plot. It didn't — the captain hovered at its spawn world-position while the ship flew
away, and the user reported "ship flied right through it." Adding `pillager` to the
retain tag didn't fix it either (the tag only prevents one kick path; the per-tick
rebind only happens with `setPlotPosition`).

**Symptom diagnosis.** A captain "stuck in mid-air at original spawn coords while the
ship visibly moves" almost always means `sable$setPlotPosition` wasn't called or was
called too early. Sable's mixin field initialiser fires when the entity is first added
to a level, so the call has to come AFTER `addFreshEntity`, not before.

**Generalized helper.** `CaptainSpawner.spawnAnchoredPillager(...)` takes a NBT delta and
an optional tag, so every airship spawns both a captain (tagged with
`MCPDataKeys.CAPTAIN_TAG` → seal drop on death) and a regular crewmate (no tag, no
special drop). The crewmate exists partly to confirm the anchor mechanism is
type-agnostic — any vanilla entity type can ride if bound — and partly to make the
ship feel populated.

**Side benefit / known limitation.** Pillagers still have `NoAi=true`, so they don't
shoot or take knockback. Fixing knockback would mean removing NoAi, which means
inventing AI that doesn't walk the captain off the deck (since walking would fight
the per-tick rebind). Deferred to a session that wants combat-on-airship.

**How to apply.** When adding more anchored entities to airships (a parrot mascot, a
seated commander, etc.), use the same recipe: `spawnAnchoredPillager` is the
reference; copy its sequence (create entity → `moveTo(initialWorldPos)` → flags +
items + name → `addFreshEntity` → `sable$setPlotPosition`). The
`((EntityStickExtension) entity)` cast is via the public mixin interface, no AT needed.

**Cannon split into AIM (always-on) + FIRE (runtime-toggled).**
- `CANNON_AIM_ENABLED` — compile-time `true`. The cannon visually tracks the player
  during PURSUE; no projectiles spawned, just yaw/pitch updates.
- `CANNON_FIRE_ENABLED` — runtime `volatile boolean`, defaults to `false`. Toggled at
  chat via `/mcpirates fire on|off` (see `MCPCommands`). Stays in whatever state the
  last command set; resets to false on server restart.

Why this split: the user wants the visual feedback of an aiming cannon (sells the
"the pirates see you" fantasy) but doesn't want cannonball impacts during routine
debug sessions. Splitting AIM from FIRE keeps the demo-ready visuals on while
gating the actual harm. To fight a fully-armed airship, op-level player runs
{@code /mcpirates fire on}.

---

## 2026-05-11 — PURSUE engage range: "ship visible but not yet chasing"

**Decision.** `AirshipBrain.DISENGAGE_RANGE_SQ` was originally set to exactly vanilla
render distance — the ship started pursuing the moment it appeared on screen, which felt
jarring. Pulled the range in so there's a buffer of "the ship is in view, looming, but
hasn't engaged yet" before combat starts. Improves the "you see the pirate ship coming"
gameplay beat. The same constant bounds `findEnemyPlayerOnAirship`, so target acquisition
matches what the player sees.

**Known limitations.**

- **Captain doesn't shoot back.** `NoAi=true` is the simplest way to anchor it to the
  SubLevel deck without inventing custom AI. The airship's cannon (`AirshipBrain`)
  remains the only offensive system for now.
- **No Create-seat embedding.** Mounting the captain on a `create:*_seat` block would be
  the proper "captain at the helm" implementation. Defers to a session that can also
  modify the airship NBT to bake a seat in.
- **No ship-identity matching between seal and bounty map.** All seals are
  interchangeable; the bounty scroll picks an outpost at unfurl time without consulting
  which captain's seal the player turned in. Adding a `ship_id` NBT field on both is the
  right way to wire this — designing the registry now would be premature.
- **Placeholder textures.** Four PNGs generated programmatically by
  `tools/build_placeholder_textures.py`: bounty board, captain seal, furled bounty,
  sheriff villager overlay. All visually distinct from vanilla assets so they're easy to
  spot when replacing.

---

## 2026-05-11 — Bounty v0.2: furled scroll, defeat tracking, lifetime cap, sheriff station

Four interlocking changes shipped together because each is useless without the others.

**1. Furled bounty scroll, not pre-baked map.** The v0.1 sheriff trade emitted a
`filled_map` from `findNearestMapStructure(trader.blockPosition(), …)` at trade-generation
time. That bakes the player-villager geometry into the map: every map a given sheriff ever
sold pointed at the same outpost — the one closest to the villager. The redesign sells
`mcpirates:furled_bounty` (a paper-stack item, NBT-marked) and defers the outpost lookup
to right-click. `FurledBountyItem.use(...)` runs server-side, calls
`findNearestMapStructure` *from the player's current position*, builds the `filled_map`,
and swaps the scroll in hand. Same sheriff can sell many scrolls; each unfurls to a
different outpost depending on where the player is when they break the seal.

The other reason for deferring is **(2) defeat skipping**, which makes no sense at
trade-gen time (no captain has died yet) but is the natural filter to apply at unfurl.

**2. `DefeatedAirships` SavedData — captain death marks the airship.** Per-level
`SavedData` (so it survives save/load) holding a `Set<BlockPos>` of airship anchor
positions whose captain has been killed. `CaptainSpawner` stamps each captain's
`persistentData` with the airship's world-side analog-lever pos
(`MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY`) at spawn. `CaptainDeath` reads that key on
`LivingDeathEvent` and adds the anchor to the set. `FurledBountyItem.use` queries
`DefeatedAirships.isDefeated(outpostPos)` on the candidate outpost; on a hit it perturbs
the search origin by a random 800–2400-block offset and retries up to 6 times.

We can't use the captain's own `position()` as the airship handle — that's a plot-local
coord inside the SubLevel storage region (~20 million block range), unrelated to the
outpost's world location. The lever's world pos is the only stable handle we have back to
the world location of the outpost. `isDefeated(...)` matches at chunk resolution and
widens to a 3×3 chunk neighbourhood so the outpost's reported origin chunk doesn't have
to exactly equal the lever's chunk.

**3. Per-sheriff lifetime cap on scroll sales.** Each sheriff can sell exactly five
furled bounties over its entire lifetime, regardless of work-cycle restocks. Counter lives
on the villager's `persistentData` (`MCPDataKeys.SHERIFF_SCROLLS_SOLD_NBT_KEY`).
`SheriffLifetimeCap` listens on NeoForge's `TradeWithVillagerEvent`: if the merchant is a
sheriff and the offer's result is `furled_bounty`, increment the counter; if it hits the
cap, call `offer.setToOutOfStock()` immediately.

The trick: vanilla's `restock()` resets `offer.uses` to 0 on work-day cycles, which would
*un-lock* an out-of-stock cap. So a second handler — `EntityTickEvent.Post`, rate-limited
to every 100 ticks — re-pegs the bounty offer to out-of-stock as long as the lifetime
counter is past the cap. Cosmetically the trade appears greyed out as "out of stock"; a
later phase 4 task is to make it disappear from the GUI entirely.

**4. Sheriff station replaces gazebo.** The v0.1 gazebo (5×6×6 oak frame with a
cobblestone-then-bounty-board centrepiece, programmatically built by
`tools/build_bounty_board.py`) was replaced with a hand-built sheriff station:
9×8×12 stone building with an oak front door, a small iron-door holding cell, a back
bedroom (red bed → spawns a resident villager who claims the bounty_board), hanging
lanterns flanking the south face. Saved via the structure block in dev, imported through
`tools/import_sheriff_station.py` (idempotent — strips and re-adds the jigsaw connector
each run). Placement modifier file split is the same shape as before, now extended to a
third pool list for **T&T (kaisyn)** villages:

- `sheriff_station_vanilla.json` — vanilla 5 biomes' `houses` pools.
- `sheriff_station_ctov.json` — 21 CTOV `village/<biome>/house` pools, gated on
  `mod_loaded:ctov`.
- `sheriff_station_kaisyn.json` — 23 T&T house pools, gated on `mod_loaded:t_and_t`.

The bounty_board POI lives inside the building and is reachable from the bed. Vanilla
unemployed villagers (`VillagerProfession.NONE.acquirableJobSite`) only consider POIs in
the `minecraft:acquirable_job_site` tag — modded POIs aren't auto-tagged. Without the
tag append, the bounty_board POI is correctly registered (NeoForge auto-handles
`BlockState → PoiType`) but villagers ignore it. Fix:
`data/minecraft/tags/point_of_interest_type/acquirable_job_site.json` with `replace:false`
and our one entry. **This is the single most-overlooked thing about shipping a modded
profession — easy to think "the POI is registered, why doesn't anyone claim it?" and look
in the wrong place for hours.**

---

## 2026-05-14 — Mixin into `GameTestServer` to pin test arenas at world origin

**Decision.** Add a tiny SpongePowered Mixin (`com.mcpirates.gametest.mixin.GameTestServerMixin`)
that `@Redirect`s the two `RandomSource.nextIntBetweenInclusive(-14999992, 14999992)` calls
in `net.minecraft.gametest.framework.GameTestServer#startTests` to return 0, so every
`@GameTest` arena lands near world (0, -59, 0) instead of somewhere up to 15 million
blocks away.

**Why.** Sable's physics engine is rapier3d compiled with 32-bit (`f32`) precision. At
world coordinates of magnitude ~10⁷ the per-tick integration `pos += vel * dt` gets
quantised below the float step (~1.5 units), so SubLevel rigid bodies effectively cannot
accelerate — a buoyancy test on `airship_small` will show `totalLift > mass*|gravity|`
and a non-zero linear velocity, but `pos.y` will stubbornly stay where assembly left it.
Sable's own `PhysicsTest.testGravity` carries the FIXME `allow manual tests to run
automatically when rapier is set to 64-bit mode` and `testSnag` is `attempts=10,
requiredSuccesses=10, required=false` — both are flaky-by-design under f32 quantisation.
Without addressing this, our `airshipSmallRisesUnderBuoyancy` gametest can't be a
CI-credible regression guard.

**Alternatives.**
- *Configure it the boring way.* Searched: `@GameTest` annotation has no position
  parameter; NeoForge has no event hook for it (verified against `RegisterGameTestsEvent`
  and the patches in `neoforge-21.1.228-userdev.jar`); the JVM `--spawnPos` flag and
  `runs/gametest/server.properties` only affect the world's player-spawn, not test
  arena placement (`GameTestServer.create` hard-codes `WorldPresets.FLAT` and
  `startTests` uses its own random against the world seed). NeoForge's docs page on
  Game Tests confirms it: the framework doesn't expose arena positioning. Rejected — the
  configuration knob doesn't exist.
- *Match Sable's `required=false` workaround.* Mark our test optional and accept that
  CI won't catch buoyancy regressions. Rejected because the user explicitly wants
  reliable physics tests as the foundation for future ones, and "the test runs but the
  result is ignored" is worse than no test at all.
- *Build Sable against `rapier3d-f64`.* The proper fix Sable points at in its FIXME.
  Rejected as the scope is "modify and rebuild an upstream rust dependency" — much
  larger blast radius and not under our control.
- *Teleport the SubLevel to small coords after assembly inside the test.* Works but
  makes the ship visibly fall for several seconds before lift catches up, which is
  hostile to writing tests that involve "ship on solid ground" scenarios. Rejected by
  user feedback.

**Why a mixin is the smallest entropy here.** The mixin lives in one Java class + one
`mcpirates.mixins.json` + one `[[mixins]]` line in the existing `neoforge.mods.toml`
template. It targets Mojang's class directly (no fork of MC or NeoForge), only runs
when `GameTestServer` is loaded (i.e. only under `runGameTestServer` — production
`runServer` / `runClientQuick` never load that class), and the redirect is one
six-line method. The blast radius is exactly the gametest entry point, nothing else.

---

## 2026-05-19 — Sheriff GUI is a custom `AbstractContainerMenu`, not `MerchantOffers`

**Decision.** Right-clicking a sheriff villager is intercepted in `SheriffInteract`
(`PlayerInteractEvent.EntityInteract`); the event is cancelled before vanilla's
`Villager.mobInteract` runs, and the player is opened into `SheriffMenu` instead of
`MerchantMenu`.

**Why.** Three constraints rule out the vanilla trade path:
1. **Structured state.** The bounty loop has order dependencies ("can't take map N+1
   until seal N is turned in", "reward N unlocks with seal N") that don't map onto
   `MerchantOffer`'s `(cost, result, uses, maxUses)` shape. Encoding it as N separate
   trades that lock and unlock each other would need multiple tick handlers re-pegging
   `out_of_stock` flags after work-cycle restocks, with no compile-time invariant
   linking them.
2. **`MerchantScreen` footgun.** Passing `Offers:{}` (or any non-null `MerchantOffers`)
   to `/summon` permanently breaks the trade GUI: `AbstractVillager.getOffers()` only
   triggers lazy population when `offers == null`, not `.isEmpty()`. Bypassing the
   vanilla path means we never call `getOffers()` and the footgun goes away.
3. **Custom visuals.** Active/taken/locked overlays per slot, row labels, an
   always-available Patchouli book slot, "?" markers on locked rewards. Not reachable
   inside `MerchantScreen` without mixins.

**Shape.**
- **State.** Three ints on the villager's `getPersistentData()` (server-side source of
  truth): `mcpirates.sheriff_maps_claimed`, `…seals_returned`, `…rewards_claimed`.
  Invariant: `seals <= maps <= seals + 1` and `rewards <= seals`, all in
  `[0, cycleLength]`. Sheriff is "retired" when `seals == cycleLength`.
- **Sync.** `Entity.getPersistentData()` is **not** synced — client reads return 0.
  Each counter is mirrored to a `DataSlot.standalone()` registered via `addDataSlot`;
  vanilla's per-tick `broadcastChanges` diff-syncs them. Writes go through
  `writeCounter(slot, key, value)` which updates both the DataSlot and the NBT.
- **Layout.** 196 × 182 GUI. Board: 5×3 stateful slots at x∈{64,82,100,118,136} /
  y∈{18,40,62} with row labels in the left margin; an always-available book slot at
  (160, 18). The book slot's `mayPickup` is unconditional and the cell re-mints on
  every `refreshBoard`, so it works as an unlimited dispenser — including on retired
  sheriffs.
- **Seal consumption.** `SealSlot.mayPlace` accepts a `captain_seal` into the active
  slot but doesn't immediately bump the counter; consumption happens in the next
  `broadcastChanges`. Visual effect: player sees the seal land, then disappear, then
  the next map + reward slots light up.
- **Galleon trigger.** The last map in a sheriff's cycle (index `cycleLength - 1`) is
  stamped with the `mcpirates:is_galleon_bounty` data component at mint time;
  `FurledBountyItem.use` reads the component directly to switch between outpost and
  galleon paths. The galleon trigger is exactly the last map a sheriff hands you.
- **Cycle length** is read from `MCPConfig.cycleLength()` (NeoForge `ModConfigSpec`,
  range 1..5, default 5) at menu-construction time and snapshotted as a final field —
  config reloads mid-session don't desync server and client menu instances.
- **Sheriff pinning.** Vanilla trade GUI gates wandering via brain hooks we don't
  have wired. The menu's `broadcastChanges` clears the villager's navigation +
  horizontal delta-movement each server tick while open; head-tracking and ambient
  sounds keep working. Without this the sheriff drifts out of `stillValid` range
  mid-interaction.

**Tests.** `SheriffMenuTests` drives the server-side menu directly (`SimpleMenuProvider`
+ `AbstractContainerMenu.clicked(...)`) bypassing the network layer. Each scenario
asserts on the three counters + carried slot state. The visual layer (slot overlays,
row labels, locked-reward "?", retired banner) is **not** gametest-covered — gametest
server is headless; verified manually under `runClientQuick`. JUnit-style menu tests
under `src/test/java` were rejected: `AbstractContainerMenu` reaches into `MenuType`
static fields and ServerPlayer machinery that JUnit can't bootstrap without a real
`MinecraftServer`.

---

## 2026-05-24 — Replace galleon-unlock boolean + vanilla pool override with permit-gated ship outpost structures

**Decision.** Pirate-ship outposts no longer hitchhike on vanilla `minecraft:pillager_outpost`.
Each ship kind (`airship_small`, `crossbow_board`, `ramship`, `galleon`) is its own
top-level structure of type `mcpirates:permitted_ship_outpost`. The `base_plates` pool
override and `base_plate_with_<ship>` NBTs are gone; vanilla pillager outposts are back
to vanilla (no ship). The `GalleonUnlockState` global boolean gate is gone.

**Generation is permit-gated.** `findGenerationPoint` returns empty unless a `ChunkPos`
entry exists in `OutpostPermits` for that structure's `permit_key`. Permits are stamped
by `FurledBountyItem.use` when a bounty scroll unfurls: the scroll picks a ship kind
(galleon if the `IS_GALLEON_BOUNTY` component is present, else random from the
non-galleon set), iterates outward via the structure_set's
`RandomSpreadStructurePlacement.getPotentialStructureChunk`, validates each candidate
against vanilla's full `isStructureChunk` (frequency dice + exclusion zones) and the
structure's `validBiome` tag, skips cells whose chunks are already loaded/on-disk/
evaluated-by-our-own-findGenerationPoint, and stamps the first that survives. The map's
decoration points at that cell's center.

**One scroll = one structure.** A permit is consumed by one specific cell; permits never
expire. Other structure-set candidate cells stay un-permitted and therefore unspawned.

**Future-flexibility hook.** `OutpostPermits` also carries an `openGates: Set<RL>` that
short-circuits `isAllowed` to true for every chunk. Nothing calls `openGate(key)` today;
when gameplay design wants "the scroll opens a kind globally instead", `openGate` is the
one-line transition. Structure-side code is unchanged.

**Why a custom Structure type (`PermittedShipOutpostStructure`) rather than mixin/override.**
Vanilla `JigsawStructure` is `final`. The natural extension point is registering our own
`StructureType` whose `findGenerationPoint` knows about permits. One Java class drives
all kinds — the `permit_key` field in the JSON identifies which permit set each
structure consults, so adding a new ship is a data-only change.

**Why the chunk-existence + own-evaluation probe.** Vanilla's `StructureCheck` caches
findGenerationPoint results. A neighbouring chunk hitting STRUCTURE_STARTS triggers
findGenerationPoint for all structures whose region the chunk falls in — including ours,
returning empty for lack of permit, and the cache poisons that cell. Permitting it later
is a no-op. We skip any candidate (a) loaded in memory, (b) saved to disk, or (c) where
our own structure has already had its findGenerationPoint fire (tracked in a
`ConcurrentHashMap<RL, Set<ChunkPos>>` populated by the structure subclass).

**Tradeoffs.** Vanilla pillager outposts coexist alongside ours but are ship-less.
Tooling that detects "outposts in worldgen" by tag `#mcpirates:pirate_outposts` no longer
works — the tag is gone. The `outpost_anchor` jigsaw block was permanently removed from
each pad NBT; `tools/strip_outpost_anchor_jigsaws.py` documents the surgery. Pad `size_y`
was bumped to encompass the ship's vertical extent (`tools/bump_pad_sizes_for_standalone.py`)
because each pad is now the structure's start piece and vanilla's bbox-extension hack
only applies to child pieces.

Supersedes the 2026-05-10 entry "Airship integration: override base_plates pool, airship
as outpost jigsaw piece".
