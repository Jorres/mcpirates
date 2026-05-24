---
name: place-structures
description: Wipe the dev world to superflat plains, then lay out mcpirates structures in a five-row grid for visual review. Row 1 = each pad with its non-ship appendages, row 2 = each ship alone, row 3 = each pad fully assembled (pad + appendages + ship), row 4 = sheriff structures, row 5 = every variant of each ship whose pool element references a variant_swap processor list. Pieces are placed as raw templates (jigsaw blocks remain visible). Each placed piece gets a SAVE structure block adjacent to (outside) its footprint with the NBT's name and a bounding box matching its NBT size.
---

# place-structures

Run from the project root.

## 1. Stop server, set superflat, wipe + restart

Stop via RCON (`/stop`); if RCON isn't reachable, server is already down.

Patch `runs/server/server.properties` (the `\:` escapes are required for Java `.properties`):

- `level-type=minecraft\:flat`
- `generator-settings={"biome":"minecraft\:plains","features":false,"lakes":false,"layers":[{"block":"minecraft\:bedrock","height":1},{"block":"minecraft\:dirt","height":2},{"block":"minecraft\:grass_block","height":1}]}`
- `gamemode=creative`
- `force-gamemode=true`

Run `tools/dev_restart_linux.sh --wipe` — kills lingering JVMs, deletes `runs/server/world/`, relaunches server+client, exits `READY` once RCON is back. `ops.json` + `server.properties` survive `--wipe`.

## 2. Op + outfit Dev (first run only)

Via RCON:

- `op Dev` — required for jigsaw/structure-block GUIs (op-level ≥ 2).
- `gamemode creative Dev`
- `give Dev minecraft:jigsaw 16`
- `give Dev minecraft:structure_block 8`
- `item replace entity Dev armor.head with create:goggles 1` — equips Create's Engineer's Goggles (the Create item the user calls "architect goggles") directly to the head slot so Dev is wearing them on load, not holding them. If Create renames the item, search `item.create.*goggles` in sources.
- `gamerule doDaylightCycle false` and `time set day` — pin the world to noon so the bbox overlays read cleanly and structures aren't reviewed in the dark.
- `mcpirates lift off` — **REQUIRED** before placement. Auto-liftoff is ON by default; without this toggle, every ship template placed by `/place template` gets assembled into a SubLevel the same tick and its world blocks are stripped, leaving pads visible but ships looking empty.

## 3. Generate + execute the layout

`tools/place_layout.py` does all the heavy lifting: parses NBTs, walks pool graphs (including `lithostitched:guaranteed` delegates), computes the 5-row grid with mating-jigsaw alignment, and emits one Minecraft command per line on stdout. Pipe its output into RCON in a single shot:

```bash
python tools/place_layout.py | while read -r line; do
  [ -z "$line" ] && continue
  case "$line" in \#*) continue ;; esac
  echo "$line"
done | xargs -I{} mcrcon -H 127.0.0.1 -P 25575 -p dev -- "{}"
```

Or, simpler, open one persistent RCON connection from Python and stream lines through it (see how the skill was driven in agent transcripts).

What the tool produces:

- **Row 1**: each pad + its non-ship appendages.
- **Row 2**: each ship alone.
- **Row 3**: each pad fully assembled (pad + appendages + ship). Pools with multiple ship elements (e.g. `airships_width_9_landing_pad` for ramship + firecracker) produce one assembly per ship.
- **Row 4**: `sheriff_station` + its appendages.
- **Row 5**: for every ship pool element whose `processors` is a *string ref* to a processor list containing a `mcpirates:variant_swap` processor, one placement per palette index of its first family — emitted as `mcpirates place_variant <kind> <i> X Y Z`. The SAVE block label is `<kind>_var<i>_<colorA>_<colorB>`.

All rows use `/place template` (jigsaws stay visible) except row 5, which uses `/mcpirates place_variant` (also leaves jigsaws). The tool emits a final `forceload add`, `setworldspawn`, and `tp Dev` so you land in the middle of the layout.

After running, do `kill @e[type=item]` once to clean up any loot chests/hoppers spilled.

## 4. Underlying mechanics (for when the tool needs editing)

These notes back the implementation in `tools/place_layout.py`; touching them is only necessary when adding a new row type or fixing an alignment bug.

**Grid origin + spacing.** Origin `(x=0, z=0)`, floor `y=-58` (superflat grass at `y=-61`; 2-block air gap so bbox overlays read cleanly). Rows along +X with a 6-block intra-row gutter between groups, +Z gutter of 10 between rows.

**Mating-jigsaw alignment.** Parent jigsaw at parent-local `(plx,ply,plz)` with face `D` from `orientation` (first token, e.g. `east_up` → `east`). Child's mating jigsaw is the one whose `name` equals the parent's `target`. World pos of parent jigsaw `P = parentOrigin + (plx,ply,plz)`. Child jigsaw sits at `P + unit(D)`. Child origin = `P + unit(D) - childJigsawLocalPos`. Unit vectors: `east=+X`, `west=-X`, `south=+Z`, `north=-Z`, `up=+Y`, `down=-Y`.

**Ship pool detection.** A pool is treated as a ship pool if any pad's `mcpirates:landing_pad_top` jigsaw points at it — agnostic of pool name prefix. This is why renaming a pool away from `airships_*` still works as long as the pad references it.

**SAVE block.** One block NORTH (`z-1`) of the piece's NW corner, with `posZ:1` to wrap the structure. NBT `size` straight from the template.

**Variants (row 5).** `mcpirates place_variant <kind> <i> X Y Z` loads the live `processor_list` registry entry, finds the first `variant_swap` processor, builds a deterministic single-palette version via `withForcedPicks([i, 0, 0, ...])`, and calls `placeInWorld`. Only the *first* family varies; other families default to palette 0. Re-ordering the JSON's `families` map changes which family the index drives.

## Gotchas

- `setblock` returns `Could not set the block` when the new block's id+blockstate equals the existing one (NBT is ignored in the equality check). To rewrite a jigsaw or structure_block in place: clear to air first, then setblock. `tools/place_layout.py` already does this for SAVE blocks.
- Jigsaw `orientation` is a blockstate, not NBT. `/data get block` won't show it. Probe with `/execute if block <pos> minecraft:jigsaw[orientation=<value>] run setblock <sentinel> <marker>`. Don't use `/say` for the probe — RCON doesn't echo chat broadcasts.
- `/place template` places jigsaw blocks as-is (visible). `/place feature` and `/place jigsaw` run the assembler and replace jigsaws with their `final_state` — wrong for this skill.
- Superflat grass is at `y=-61`. Placing pieces at `y=-60` sits them on the ground. `setworldspawn 0 64 0` (vanilla default) would put the player 124 blocks above the layout.
- `op Dev` writes to `runs/server/ops.json`, outside `world/` — survives `--wipe`. Section 2 only runs on first setup.
- The SAVE block must be OUTSIDE the piece's footprint (the tool uses `z-1`). Placing it inside overwrites a real block.
- For UI/jigsaw verification, the player must be **both** op AND in creative mode — opped survival shows no GUI; creative non-op shows no GUI either.
- `mcpirates place_variant` requires op level ≥ 2 (same as everything under `/mcpirates`). Section 2 already sets this.
