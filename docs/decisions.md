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
appends our landing pad to whatever pillager-outpost template pool is live, so we coexist with
T&T / Yungs / CTOV instead of overriding them. Other outpost-mods are users' choice.

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

## 2026-05-10 — Pillager-outpost landing pad: vanilla template-pool gotchas

**Decision.** Our landing-pad NBT and the lithostitched modifier follow the exact conventions
of vanilla `feature_tent1.nbt` / `feature_cage1.nbt`:
- Pool path: `minecraft:pillager_outpost/features` (not `feature_pool`).
- Element type: `minecraft:legacy_single_pool_element` (not `single_pool_element`).
- Single attach jigsaw at y=0 of the pad, oriented `down_south`,
  `name = target = minecraft:feature`, pool `minecraft:empty`.
- `final_state = minecraft:stone_bricks` so the jigsaw becomes solid floor after attachment.

**Why.** The vanilla outpost wires up as
`base_plate → feature_plates → feature_plate.nbt → features`. The `features` pool is what
holds tents, cages, logs etc. Each piece in that pool needs the matching jigsaw config above
or the assembler silently rejects it. `legacy_single_pool_element` gets the right rotation
behavior for these flat ground pieces; the non-legacy variant does not.

**How to apply.** Any future ground-level outpost piece we add should follow the same
template. If we want pieces that hang off the watchtower itself, that's a different jigsaw
target (`minecraft:entrance` etc.) — re-derive from `watchtower.nbt` then.

---

## 2026-05-10 — Airship integration: clone base_plate, repoint east jigsaw to airships pool

**Decision.** The airship is a piece in the outpost's *own* jigsaw graph, not a separate
worldgen feature, not a runtime placement. We inject a clone of vanilla
`pillager_outpost/base_plate.nbt` into the start pool with high weight; the clone differs
from vanilla only in that its east-facing `feature_plates` jigsaw at local `(15, 0, 8)` is
repointed to `mcpirates:airships` pool, target `mcpirates:airship_keel`. The airship piece
matches that connector with a west-facing jigsaw at its own local `(0, 0, 5)`.

**Why.** Three earlier approaches failed for specific reasons that this approach avoids:
1. *Add airship to `pillager_outpost/features` pool* — features sit under the watchtower's
   claimed airspace; every 5×10×10 ship roll got bbox-rejected.
2. *Custom Feature + biome modifier* — features can write only to the chunk currently being
   decorated; cross-chunk writes log "Detected setBlock in a far chunk" and are silently
   dropped. Our airship spans into the chunk east of the outpost start chunk.
3. *`ChunkEvent.Load` runtime listener* — works mechanically (cross-chunk writes are fine
   post-worldgen) but force-loading neighboring chunks to compute terrain height risks
   cascading chunk gen and is fragile compared to data-only worldgen.

The chosen approach has no Java code path, no events, no chunk hooks — pure datapack/JSON +
NBTs. The jigsaw assembler computes both watchtower and airship bounding boxes in the same
pass and picks the airship's rotation to fit. Watchtower bbox `(0..14, 1..21, 0..14)`
vs airship `(16..25, 0..12, 3..12)` relative to base_plate origin — no X overlap → no
rejection.

**Alternatives considered and rejected.**
- *Spacer/mast piece between base_plate and airship*: works but adds an extra NBT and pool.
  The east-jigsaw repoint approach is simpler and yields the same result.
- *Make airship a sibling structure with shared salt*: structure_set entries can't share a
  chunk, so this doesn't actually colocate them at outposts.
- *Modify base_plate jigsaws to add a NEW one (instead of repointing)*: also workable but
  reasoning about jigsaw position adjacency is less constrained. Repointing the existing
  east jigsaw means the airship slots into a known-tested layout slot.

**Cost.** We sacrifice the eastern feature_plate (so 1 fewer cage/tent on the east side of
the outpost) per airship-equipped outpost. West and south feature_plate jigsaws are
untouched. Acceptable.

**How to apply.** When adding more airship designs / variants: add them as elements in
`mcpirates:airships` pool with weights. Don't try to add them to `pillager_outpost/features`
or other vanilla pools, and don't write a Java placement listener — it's been tried and is
fragile. If we ever want airships at *other* structure types (villages, woodland mansions),
clone *their* start pool entries similarly.

---

## 2026-05-10 — Lithostitched 1.7.3 has Create-incompat regression; pin to 1.7.0

**Decision.** Pin to lithostitched `1.7.0` (NOT 1.7.3, the latest at time of writing) in
`gradle.properties` and `libs/`. Documented dep version in `neoforge.mods.toml` is
`[1.7.0,)`.

**Why.** With Create 6.0.10-280 + NeoForge 21.1.228 + lithostitched 1.7.3, the dev client
fails at registry init with
`IllegalStateException: Found unused register callbacks` thrown from
`Registrate.AbstractRegistrate.onRegister` during Create's `RegisterEvent` dispatch.
Downgrading lithostitched to 1.7.0 (the version shipped in the user's live `.minecraft`
mods folder, known-good baseline) resolves it; nothing else in the stack changed.

**Alternatives.** Identifying the exact lithostitched 1.7.3 commit that broke this and
filing upstream — worth doing, deferred. For now 1.7.0 has all the modifier types we need
(`add_template_pool_elements`, `add_features`, etc.).

**How to apply.** When upgrading lithostitched, boot `runClient` once and watch for the
exception before assuming compat.

---

## 2026-05-10 — Pirate bounty board: lithostitched-injected village piece, single jigsaw

**Decision.** Add a small village building (`mcpirates:village/common/pirate_bounty_board`)
via two `lithostitched:add_template_pool_elements` modifiers:

- `bounty_board_ctov.json` — gated on `mod_loaded: ctov`, targets all 21 CTOV village house
  pools (`ctov:village/<biome>/house`).
- `bounty_board_vanilla.json` — unconditional, targets the 5 vanilla
  `minecraft:village/<biome>/houses` pools. Lithostitched silently no-ops for users whose
  vanilla pools were emptied by another mod's `replace_template_pool_elements` modifier.

The piece itself is a 5×6×6 oak gazebo (4 spruce-fence corner posts, oak-slab roof, 2
hanging lanterns, jigsaw on the south wall) modeled after Bountiful's
`bounty_gazebo.nbt`, with a `minecraft:cobblestone` block in the dead center as a
placeholder for the future pirate-quest block. Single `minecraft:building_entrance`
jigsaw at `(2, 1, 5)` connects to vanilla / CTOV house entrance jigsaws —
`pool=minecraft:empty`, `final_state=minecraft:structure_void`, `joint=rollable`.

**Why.** Bountiful injects its own gazebo at runtime via a `ServerAboutToStartEvent`
listener that mutates the live template-pool registry (`BountifulSharedApi.registerJigsawPieces`
→ `addToStructurePool`). CTOV ships its own data-driven equivalent for Bountiful's
gazebo (`data/ctov/lithostitched/worldgen_modifier/bountiful/<biome>.json`). Following
CTOV's pattern means our piece works in CTOV villages without touching their pools at
runtime, and the same modifier shape applied to vanilla pools handles plain-Minecraft
villages too. No Java code path needed.

**Why hand-built NBT, not derived from Bountiful's gazebo.** mcpirates has no soft- or
hard-dep on Bountiful, and we don't want to ship Bountiful's NBT bytes. `tools/build_bounty_board.py`
emits the structure parametrically; only depends on `nbtlib` (already used by
`build_outpost_pieces.py`). License-clean and reproducible from the script alone.

**Why `lithostitched:guaranteed` with `count: 1`, `min_depth: 3`.** Same wrapper CTOV uses
for Bountiful's gazebo. `min_depth: 3` keeps the piece off the village center pieces
(houses-of-houses-of-center) so it blends with the residential ring; `count: 1` caps it
at one per village.

**Cost / known limits.**
- T&T (`kaisyn:` namespace) defines its own village types and uses entirely separate
  pool names (e.g. `kaisyn:village/sunflower_plains_farm/...`). Our modifier doesn't
  reach those — players who get a T&T-only village won't see the pirate board. Adding
  T&T support is a third modifier file enumerating its pool list; deferred.
- Vanilla `minecraft:village/<biome>/houses` injection co-exists with CTOV in the user's
  current modpack. CTOV typically captures vanilla village starts (it overrides the
  start pool), so the vanilla modifier is mostly insurance for non-CTOV setups.
- The PoC building has cobblestone in the center. When we add a real
  `mcpirates:pirate_quest_board` block, swap the palette entry in
  `tools/build_bounty_board.py` and re-run; no other file changes.

**How to apply.** To add another village mod's pools, copy `bounty_board_vanilla.json`,
add a `neoforge:conditions` `mod_loaded` guard for that mod, and list its house-tier pool
names in `template_pools`. To re-skin the piece, edit
`tools/build_bounty_board.py` and re-run; the NBT regenerates deterministically. To
test in dev: `./gradlew runClient`, fresh world, `/locate structure
minecraft:village_plains` (or any CTOV village biome), teleport, walk the perimeter —
should see the gazebo with a cobblestone block at one of the outer house slots.

---

## 2026-05-11 — Bounty pipeline v0.1: sheriff villager + tagged captain pillager + seal drop

**Decision.** A complete (if minimal) bounty loop is in place, in **three independent pieces**
so each can evolve without touching the others.

**Piece 1 — Sheriff villager + bounty board POI anchor.** `mcpirates:bounty_board` is a
plain wooden block (no block-entity, no GUI). Its only job is to be a Point of Interest
anchor that the new `mcpirates:sheriff` profession claims as a workstation. The block
replaces the cobblestone placeholder at the center of the village gazebo (re-emitted by
`tools/build_bounty_board.py`). For v0.1 the sheriff has **one** trade at tier 1:
`1 captain_seal → 10 emeralds`. No "buy a bounty map" trade yet — deferred to v0.2.

**Piece 2 — Captain pillager spawn.** When an airship lifts off
(`AirshipLiftoffTrigger.activateLever`), `CaptainSpawner.spawn` is called with the
SubLevel, the world position of the lever, the assembly offset, and the jigsaw rotation.
A vanilla `Pillager` is spawned into the SubLevel at NBT-local `(7, 6, 3)` — two blocks
aft of the cannon mount, between the helm levers, on the bridge. Three NBT flags shape
its behaviour: `NoAi=true` (it doesn't wander off the deck), `NoGravity=true` (it stays
anchored at its plot-local position regardless of how the SubLevel moves), and the
scoreboard-style tag `mcpirates.pirate_captain` (used by piece 3). Custom name
"Pirate Captain" + crossbow in mainhand sell the fantasy.

**Piece 3 — Captain death drop.** `CaptainDeath` listens on `LivingDeathEvent`, filters
on the `mcpirates.pirate_captain` tag, and drops a `captain_seal` ItemEntity. **Crucial
detail:** Sable stores SubLevel contents at "plot" coordinates in the same `ServerLevel`
as the playable world. If we spawn the seal at `victim.position()` it lands inside the
plot region (riding the wreckage); we instead transform via
`SubLevel.logicalPose().transformPosition(plotLocal)` to spawn the seal at the
world-rendered position, where it falls to real ground for the player to pick up.

**Why a tagged vanilla pillager, not a custom entity.** A custom `EntityType` requires
its own model, texture, renderer, attributes, AI registration, and spawn-egg item — a
full session of work for what we can express as `entity.addTag(String)` + a `getTags()`
check on death. The tag survives save/load and is namespaced (`mcpirates.…`) so we
won't clash with vanilla or other mods' tag conventions.

**POI block-state registration — NeoForge does it for us, don't do it twice.** An
earlier draft of `MCPPoiTypes` hooked `FMLCommonSetupEvent` and reflectively called
`PoiTypes.registerBlockStates(Holder, Set<BlockState>)` (the method is package-private
in Mojang's 1.21.1 mappings). The dev client immediately logged
`IllegalStateException: Block{mcpirates:bounty_board} is defined in more than one PoI type`
because NeoForge had already added the block to `TYPE_BY_STATE` as part of the registry-
add callback. The fix: **do nothing**. Registering a `PoiType` with non-empty
`matchingStates` via `DeferredRegister` is sufficient on NeoForge 21.1.228 — the auto-
registration is part of NeoForge's PoiTypes patch. Don't ship an AT, don't reflect.

**Verified end-to-end (session 2026-05-11 via MCP bridge):**

Full pipeline tested using the Tier-1 RCON bridge against a live `runServer`:
1. Sheriff villager: summoned at `(0, 200, 0)`, traded 5 seals → 50 emeralds.
   `read_entities Offers` confirmed the recipe is present on first interaction.
2. Pillager outpost at `(64, ~, 1168)`: TP'd Dev nearby; AirshipLiftoffTrigger
   fired honey-glue → assembly → cannon → brain registration → captain spawn
   in ~1 second. All log lines present.
3. Captain death drop: killed via `/kill`, `CaptainDeath` fired and a
   `mcpirates:captain_seal` ItemEntity appeared at the captain's world-rendered
   position, falling to ground as designed.

**Peaceful-mode footgun discovered.** Default `runs/server/server.properties`
shipped `difficulty=peaceful`. Pillagers `discard()` themselves within a tick
under peaceful regardless of `PersistenceRequired`, so the captain was killed
silently on every test. Changed default to `difficulty=easy`. If future tests
see "captain spawned but `@e[type=pillager]` finds nothing," check difficulty
first — easy to mis-diagnose as a SubLevel-visibility race.

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

**Deck Y is NBT 3, not 5.** The deck floor on `airship_small.nbt` is cobblestone at
NBT y=3 (verified by NBT inspection). Pillager feet should sit at y=4 (head at y≈5.95,
above the deck, clearing the y=10 envelope). The first draft had `CAPTAIN_DELTA = (0, 0, -2)`
relative to the lever at NBT (7, 6, 5), putting the captain at NBT y=6 — two blocks
above the deck, hovering like a ghost. Final delta is `(0, -2, -2)` → NBT (7, 4, 3).

**Generalized helper.** `CaptainSpawner.spawnAnchoredPillager(...)` now takes the
delta and an optional tag, so every airship spawns both a captain (with the
`mcpirates.pirate_captain` tag → seal drop on death) and a regular crewmate (no tag,
no special drop). The crewmate exists partly to confirm the anchor mechanism is
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

**Cannon split into AIM (always-on) + FIRE (runtime-toggled).** Earlier session shipped
a single `DEBUG_CANNON_ENABLED` boolean to silence cannons during debugging. Final
design (session 2026-05-11):
- {@code CANNON_AIM_ENABLED} — compile-time `true`. The cannon visually tracks the
  player during PURSUE; no projectiles spawned, just yaw/pitch updates.
- {@code CANNON_FIRE_ENABLED} — runtime `volatile boolean`, defaults to `false`.
  Toggled at chat via {@code /mcpirates fire on|off} (see `MCPCommands`). Stays in
  whatever state the last command set; resets to false on server restart.

Why this split: the user wants the visual feedback of an aiming cannon (sells the
"the pirates see you" fantasy) but doesn't want cannonball impacts during routine
debug sessions. Splitting AIM from FIRE keeps the demo-ready visuals on while
gating the actual harm. To fight a fully-armed airship, op-level player runs
{@code /mcpirates fire on}.

---

## 2026-05-11 — PURSUE engage range 12 chunks → 8 chunks (matched to "ship visible but not yet chasing")

**Decision.** {@code DISENGAGE_RANGE_SQ} dropped from {@code (12*16)^2} to
{@code (8*16)^2}. Was 192 blocks (exactly vanilla render distance, so the ship
started pursuing the instant it appeared on screen — felt jarring). Now 128 blocks,
leaving ~64 blocks of "ship is in view, looming, but not yet attacking" before
combat engages. Improves the "you see the pirate ship coming" gameplay beat.

Also affects `findEnemyPlayerOnAirship` upper bound (same constant), so the brain's
target acquisition matches.

**Known limitations (deferred to v0.2+).**

- **Captain doesn't shoot back.** `NoAi=true` is the simplest way to anchor it to the
  SubLevel deck without inventing custom AI. The airship's cannon (`AirshipBrain`)
  remains the only offensive system for now.
- **No Create-seat embedding.** Mounting the captain on a `create:*_seat` block would be
  the proper "captain at the helm" implementation. Defers to a session that can also
  modify the airship NBT to bake a seat in.
- **No ship-identity matching between seal and (yet-to-exist) bounty map.** All seals are
  interchangeable. When we add a buy-a-bounty-map trade, the right time to add a
  `ship_id` NBT field to both is together — designing the registry now would be premature.
- **Placeholder textures.** Three PNGs generated programmatically by
  `tools/build_placeholder_textures.py`: a wood-and-parchment bounty board, a red-and-gold
  wax seal, and a sheriff villager overlay with a red headband + gold chest-star. All
  visually distinct from vanilla assets so they're easy to spot when replacing.

**How to apply.**

- To swap the cobblestone-era texture for the proper block: already done — the NBT was
  regenerated from `build_bounty_board.py` after changing palette index 10 from
  `minecraft:cobblestone` to `mcpirates:bounty_board`. The PoC structure is identical
  in geometry; only that one block is different.
- To add a "buy a bounty map" trade: extend `SheriffTrades.onVillagerTrades` with a tier
  1 (or higher) `ItemListing` that emits a `filled_map` with a custom decoration. Tier
  thresholds are `trades.get(N)`.
- To swap any placeholder texture: replace the corresponding PNG and re-run nothing —
  textures are read straight from `assets/`.
- To test the loop: `./gradlew runClient` → fresh world → `/locate structure
  minecraft:pillager_outpost` → wait for airship to lift off → kill captain → walk to
  the village → find a sheriff villager next to a bounty board → trade.
