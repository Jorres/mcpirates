---
name: place-structures
description: Wipe the dev world to superflat plains, then lay out mcpirates structures in a four-row grid for visual review. Row 1 = each pad with its non-ship appendages, row 2 = each ship alone, row 3 = each pad fully assembled (pad + appendages + ship), row 4 = sheriff structures. Pieces are placed as raw templates (jigsaw blocks remain visible). Each placed piece gets a SAVE structure block adjacent to (outside) its footprint with the NBT's name and a bounding box matching its NBT size.
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

## 3. Discover structures

Parse every `.nbt` under `src/main/resources/data/mcpirates/structure/` (recursive). Capture `size: [W,H,D]` and each `minecraft:jigsaw` block-entity (local `pos`, NBT `name` / `target` / `pool` / `final_state`, and `orientation` from the palette entry).

Walk pool refs to find appendages: parent jigsaw's `pool` → `src/main/resources/data/mcpirates/worldgen/template_pool/<pool>.json` → element `location`s. Recurse until pool is `minecraft:empty` or absent. Pools starting with `airships_` (or `airships`) reach a **ship**; everything else reached is an **appendage**.

Classify (skip ones that don't fit any category — e.g. `evokership`, `sublevel_for_tests`, `base_plate_with_*`):

- **Pads**: ids ending in `_pad`. Their appendage tree = pool descendants excluding ship pools.
- **Ships**: ids that appear as elements of any `mcpirates:airships_*` pool.
- **Sheriff**: `village/common/sheriff_station` (root) + its appendage descendants.

NBT parsing (uses the project venv at `tools/mcp_minecraft/.venv/bin/python`):

```python
import gzip, struct
from io import BytesIO
class NBT:
    def __init__(self, d): self.b = BytesIO(d)
    def r(self, f): return struct.unpack(f, self.b.read(struct.calcsize(f)))
    def rs(self): n, = self.r('>H'); return self.b.read(n).decode()
    def p(self, t=None):
        if t is None: t, = self.r('>B'); self.rs(); return self.p(t)
        if t == 1: return self.r('>b')[0]
        if t == 2: return self.r('>h')[0]
        if t == 3: return self.r('>i')[0]
        if t == 4: return self.r('>q')[0]
        if t == 5: return self.r('>f')[0]
        if t == 6: return self.r('>d')[0]
        if t == 7: n, = self.r('>i'); return self.b.read(n)
        if t == 8: return self.rs()
        if t == 9:
            it, = self.r('>B'); n, = self.r('>i'); return [self.p(it) for _ in range(n)]
        if t == 10:
            o = {}
            while True:
                tt, = self.r('>B')
                if tt == 0: break
                nm = self.rs(); o[nm] = self.p(tt)
            return o
        if t == 11: n, = self.r('>i'); return [self.r('>i')[0] for _ in range(n)]
        raise ValueError(t)
```

## 4. Four-row grid

Origin `(x=0, z=0)`, floor `y=-58` (superflat grass top sits at `y=-61`; we leave a 2-block air gap below each piece so the bbox overlay reads cleanly and the player can walk underneath).

Rows run along +X, separated along +Z by a 10-block gutter. Within a row, pieces are placed left-to-right with a 6-block gutter between adjacent **group bounding boxes** (a "group" is one pad + its appendages, or one full assembly, etc.). Compute each row's depth as `max(group.depth)` and step `z` by `rowDepth + 8` between rows.

- **Row 1 — pads with appendages**: for each pad, place pad with `/place template <id> X Y Z`, then place each appendage at the mating-jigsaw delta (section 5). Skip ship pool refs.
- **Row 2 — ships alone**: each ship placed standalone, no appendages.
- **Row 3 — full assemblies**: for each pad that has a ship in its pool graph, place pad + every appendage + ship via mating-jigsaw deltas.
- **Row 4 — sheriff**: `sheriff_station` + its appendages.

All placements use `/place template <id> <X> <Y> <Z>` so jigsaw blocks remain visible (they are NOT replaced by `final_state`). After each row, run `kill @e[type=item]` — chests/hoppers in some templates spill loot.

## 5. Mating-jigsaw alignment

For each parent→child appendage:

- Parent jigsaw at parent-local `(plx, ply, plz)` with face direction `D` (first token of `orientation`, e.g. `east_up` → `east`).
- Child's mating jigsaw is the one whose `name` equals the parent's `target`, in the child NBT.
- World position of parent jigsaw: `P = (parentX+plx, parentY+ply, parentZ+plz)`.
- Child jigsaw sits face-to-face at `P + unit(D)`.
- Child origin: `P + unit(D) - (clx, cly, clz)`.
- Face unit vectors: `east=+X`, `west=-X`, `south=+Z`, `north=-Z`, `up=+Y`, `down=-Y`.

If a pad/ship pair fails to resolve a mating jigsaw (e.g. `galleon_pad` has no `landing_pad_top`), skip row 3 for that pair and log the mismatch.

## 6. SAVE structure block per piece

For **every** placed piece (every row, every appendage, every ship instance), place a SAVE block one block WEST of the piece's NW corner so its bounding-box overlay surrounds the piece without overlapping any block of it:

```
setblock <X-1> <Y> <Z> minecraft:structure_block[mode=save]{mode:"SAVE",name:"<id>",posX:1,posY:0,posZ:0,sizeX:<W>,sizeY:<H>,sizeZ:<D>,showboundingbox:1b,ignoreEntities:1b}
```

`posX:1,posY:0,posZ:0` aims the box at the piece's origin (one block east of the SAVE block); `sizeX/Y/Z` come straight from the NBT `size` tag; `showboundingbox:1b` draws the dashed outline.

## 7. Forceload + teleport

- `forceload add <minX> <minZ> <maxX> <maxZ>` over the entire layout, rounded out to chunk granularity.
- `setworldspawn 0 -59 0`.
- `tp Dev <central x> -59 <central z>`.

## Gotchas

- `setblock` returns `Could not set the block` when the new block's id+blockstate equals the existing one (NBT is ignored in the equality check). To rewrite a jigsaw or structure_block in place: clear to air first, then setblock.
- Jigsaw `orientation` is a blockstate, not NBT. `/data get block` won't show it. Probe with `/execute if block <pos> minecraft:jigsaw[orientation=<value>] run setblock <sentinel> <marker>`. Don't use `/say` for the probe — RCON doesn't echo chat broadcasts.
- `/place template` places jigsaw blocks as-is (visible). `/place feature` and `/place jigsaw` run the assembler and replace jigsaws with their `final_state` — wrong for this skill.
- Superflat grass is at `y=-61`. Placing pieces at `y=-60` sits them on the ground. `setworldspawn 0 64 0` (vanilla default) would put the player 124 blocks above the layout.
- `op Dev` writes to `runs/server/ops.json`, outside `world/` — survives `--wipe`. Section 2 only runs on first setup.
- The SAVE block must be OUTSIDE the piece's footprint (we use `X-1`). Placing it inside overwrites a real block.
- For UI/jigsaw verification, the player must be **both** op AND in creative mode — opped survival shows no GUI; creative non-op shows no GUI either.
