# Decision log

Append-only. Each entry: date, decision, rationale, alternatives considered.

---

## 2026-05-10 — Pin to NeoForge 21.1.228 despite Aeronautics being built against 21.1.219

**Decision.** Use NeoForge `21.1.228` per project requirement.

**Why.** User requirement. Same MC (1.21.1), same loader major version, additive-only changes
in the patch range — published Aeronautics 1.2.1 jar should load against 228 fine.

**Risk.** Untested cross-version behavior; if we hit binding issues we drop to 219.

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
