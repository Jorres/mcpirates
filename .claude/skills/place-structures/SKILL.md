---
name: place-structures
description: Wipe the dev world, regenerate it as a superflat plains world, and lay out every mcpirates .nbt structure side-by-side at ground level with each structure's SAVE block visible and all appendages mated via their jigsaws. Use for visual review of all structures and quick in-editor iteration.
---

# place-structures

Run this from the project root.

## 1. Stop the server, set superflat, wipe + restart

Stop via RCON (`/stop`); if RCON isn't reachable, server is already down.

Patch `runs/server/server.properties` so the *new* world is superflat plains (the `\:` escapes are required for Java `.properties`):

- `level-type=minecraft\:flat`
- `generator-settings={"biome":"minecraft\:plains","features":false,"lakes":false,"layers":[{"block":"minecraft\:bedrock","height":1},{"block":"minecraft\:dirt","height":2},{"block":"minecraft\:grass_block","height":1}]}`
- `gamemode=creative`
- `force-gamemode=true`

Then run `tools/dev_restart_linux.sh --wipe` — it kills lingering JVMs, deletes `runs/server/world/`, relaunches server+client, and exits with `READY` once RCON is back. `ops.json` and `server.properties` are outside `world/` and persist across wipes.

## 2. Op + outfit `Dev`

Via RCON (the `mcp__minecraft__cmd` tool):

- `op Dev` — required for jigsaw/structure-block GUIs (op-level ≥ 2).
- `gamemode creative Dev`
- `give Dev minecraft:jigsaw 16`
- `give Dev minecraft:structure_block 4`

## 3. Discover structures + appendages

Parse every `.nbt` under `src/main/resources/data/mcpirates/structure/` (recursive) for:

- `size: [X, Y, Z]`
- each `minecraft:jigsaw` block-entity: local `pos`, NBT `name` / `target` / `pool` / `final_state`, and the `orientation` block-state from the palette entry.

Walk pool refs: each `.nbt` "appendage child" is found by following the parent's non-empty `pool` field to `src/main/resources/data/mcpirates/worldgen/template_pool/<pool>.json`, then taking its single `element.location` as the child structure id. Recurse until pool is `minecraft:empty` or absent.

A "root" is any `.nbt` not reachable as someone else's appendage. Typical roots in this project: `airship_small_pad`, `crossbow_board_pad`, `galleon_pad`, `village/common/sheriff_station`, etc. (Anchors like `airship_small`, `crossbow_board`, `galleon` are roots only if you want them shown free-floating without their pad.)

NBT parsing snippet (use the project venv):

```python
# /home/jorres/.../tools/mcp_minecraft/.venv/bin/python
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
            it, = self.r('>B'); n, = self.r('>i')
            return [self.p(it) for _ in range(n)]
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

## 4. Lay out side-by-side

Pick a base origin (`x=0, z=0`) and walk roots left-to-right along +X with a 3-block gap between bounding boxes (root + its appendages). For each root:

- Y level: `-60 + jitter`, where `jitter ∈ {0, 1, 2, 3}` cycled so adjacent structures vary in elevation (the user's "3-4 elevation" ask). The superflat ground is at `y=-61` (grass top), so `y=-60` is the floor.
- Place root: `place template <id> X Y Z`
- For each appendage, compute its origin from the mating-jigsaw alignment:
  - Parent's jigsaw at parent-local `(plx, ply, plz)` with face direction `D` → world `(X+plx, Y+ply, Z+plz)`.
  - Child's mating jigsaw is at child-local `(clx, cly, clz)` with face direction `-D` (opposite).
  - The two jigsaws sit at adjacent positions, face-to-face: parent jigsaw at world `P`, child jigsaw at world `P + unit(D)`.
  - So child origin = `P + unit(D) - (clx, cly, clz)`.
  - Face-direction unit vectors: `east=+X`, `west=-X`, `south=+Z`, `north=-Z`, `up=+Y`, `down=-Y`. The face direction is the first token of `orientation` (e.g. `east_up` → `east`).
- After placing, run `kill @e[type=item]` — chests/hoppers in some templates pop loot.

## 5. Per-structure SAVE block

For each root, drop a SAVE structure block one block west of its NW corner so its bounding-box overlay sits cleanly against the structure:

```
setblock <X-1> <Y> <Z> minecraft:structure_block[mode=save]{mode:"SAVE",name:"<id>",posX:1,posY:0,posZ:0,sizeX:<W>,sizeY:<H>,sizeZ:<D>,showboundingbox:1b,ignoreEntities:1b}
```

If the structure has a y-offset relative to its origin (rare), adjust `posY` to match. Place a SAVE block for **every** structure that ends up in the world — roots *and* appendages — so each can be re-saved independently.

## 6. Forceload + teleport

- `forceload add <minX> <minZ> <maxX> <maxZ>` over the whole layout area (round to chunk granularity if needed).
- `setworldspawn 0 -59 0` so future joins land in the layout.
- `tp Dev <somewhere central>` to drop the player in.

## Gotchas

- `setblock` returns `Could not set the block` when the new block's id+blockstate equals the existing one (NBT is ignored in the equality check). Rewriting a jigsaw or structure_block in place: clear to air first, then setblock.
- Jigsaw `orientation` is a blockstate, not NBT. `/data get block` won't show it. Probe with `/execute if block <pos> minecraft:jigsaw[orientation=<value>] run setblock <sentinel> <marker>` and look for the marker. Don't use `/say` for the probe — RCON doesn't echo chat broadcasts.
- Vanilla flat preset puts grass at `y=-61`, so structures placed at `y=-60` sit on the ground. `setworldspawn 0 64 0` (the old vanilla default) would put the player 124 blocks above the layout.
- `op Dev` writes to `runs/server/ops.json`, which is outside `world/` — survives `--wipe`. So step 2 only needs to run on first setup.
- `runs/server/world/generated/mcpirates/structures/<id>.nbt` is where in-game saves land; `src/main/resources/data/mcpirates/structure/<id>.nbt` is the source of truth. Extract by `cp`-ing one to the other when the user says "extract".
- For UI/jigsaw verification, the player must be both op AND in creative mode — opped survival shows no GUI; creative non-op shows no GUI either.
