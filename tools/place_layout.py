"""Discover mcpirates structures + appendage graph, classify, compute 5-row grid layout,
and emit Minecraft commands (one per line) to place the layout via /place template + SAVE blocks.

Run from the mcpirates project root:
    python tools/place_layout.py > /tmp/layout.mcfunction

Reads:
  src/main/resources/data/mcpirates/structure/**.nbt
  src/main/resources/data/mcpirates/worldgen/template_pool/*.json
  src/main/resources/data/mcpirates/worldgen/processor_list/*.json  (row 5 only)

Emits commands to stdout. Caller pipes lines into RCON.

Rows:
  1 - pads + non-ship appendages
  2 - ships alone
  3 - pad + appendages + ship (full assembly)
  4 - sheriff_station + appendages
  5 - per-ship variants for any pool element whose `processors` is a string ref
      to a processor_list containing a mcpirates:variant_swap processor. Uses
      `/mcpirates place_variant <kind> <index>` to force a specific palette.
"""
import argparse, gzip, json, struct, sys
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STRUCT_DIR    = ROOT / "src/main/resources/data/mcpirates/structure"
POOL_DIR      = ROOT / "src/main/resources/data/mcpirates/worldgen/template_pool"
PROC_LIST_DIR = ROOT / "src/main/resources/data/mcpirates/worldgen/processor_list"

NS = "mcpirates"
FLOOR_Y = -58       # superflat grass at y=-61; leave 2-block air gap under each piece
ROW_GUTTER = 10
PIECE_GUTTER = 6

# ---------- NBT reader (matches the skill's snippet) ----------
class NBT:
    def __init__(self, d): self.b = BytesIO(d)
    def r(self, f): return struct.unpack(f, self.b.read(struct.calcsize(f)))
    def rs(self):
        n, = self.r('>H'); return self.b.read(n).decode('utf-8')
    def p(self, t=None):
        if t is None:
            t, = self.r('>B'); self.rs(); return self.p(t)
        if t == 1: return self.r('>b')[0]
        if t == 2: return self.r('>h')[0]
        if t == 3: return self.r('>i')[0]
        if t == 4: return self.r('>q')[0]
        if t == 5: return self.r('>f')[0]
        if t == 6: return self.r('>d')[0]
        if t == 7:
            n, = self.r('>i'); return self.b.read(n)
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
        if t == 11:
            n, = self.r('>i'); return [self.r('>i')[0] for _ in range(n)]
        if t == 12:
            n, = self.r('>i'); return [self.r('>q')[0] for _ in range(n)]
        raise ValueError(t)

def read_nbt(path):
    raw = path.read_bytes()
    if len(raw) >= 2 and raw[0] == 0x1f and raw[1] == 0x8b:
        raw = gzip.decompress(raw)
    return NBT(raw).p()

# ---------- Structure model ----------
class Struct:
    __slots__ = ("id", "size", "jigsaws")
    def __init__(self, sid, size, jigsaws):
        self.id, self.size, self.jigsaws = sid, size, jigsaws

def parse_structure(path):
    root = read_nbt(path)
    sz = root["size"]
    palette = root.get("palette", [])
    jigsaws = []
    for b in root.get("blocks", []):
        state_idx = b["state"]
        palette_entry = palette[state_idx]
        # Identify by palette block name (matches MC's own behavior) — some structure_block
        # saves omit nbt.id even though the BE data (name/target/pool/joint) is present.
        if palette_entry.get("Name") != "minecraft:jigsaw": continue
        nbt = b.get("nbt")
        if not nbt: continue
        props = palette_entry.get("Properties", {})
        jigsaws.append({
            "pos": tuple(b["pos"]),
            "name": nbt.get("name"),
            "target": nbt.get("target"),
            "pool": nbt.get("pool"),
            "final_state": nbt.get("final_state"),
            "orientation": props.get("orientation"),
            "joint": nbt.get("joint"),
        })
    return Struct(rel_id(path), (sz[0], sz[1], sz[2]), jigsaws)

def rel_id(path):
    rel = path.relative_to(STRUCT_DIR).as_posix()
    return f"{NS}:" + rel[:-len(".nbt")]

# ---------- Pool index ----------
def _unwrap_element(el):
    """Peel off lithostitched:guaranteed (or any other) delegate wrappers until we
    reach a minecraft:single_pool_element with a location. Returns the inner element
    or None if it isn't a single-pool-element at the bottom."""
    while isinstance(el, dict) and el.get("element_type", "").startswith("lithostitched:") and "delegate" in el:
        el = el["delegate"]
    if isinstance(el, dict) and el.get("element_type") == "minecraft:single_pool_element":
        return el
    return None

def load_pools():
    """Return ({pool_id: [child_id, ...]}, {(pool_id, child_id): processors_ref_or_None}).

    `processors_ref` is the string id (e.g. `mcpirates:ship_variants`) when the
    element references an external processor list; None for inline empty lists.
    Used by row 5 to discover which ships have variant pools."""
    pools = {}
    proc_refs = {}
    for p in POOL_DIR.glob("*.json"):
        obj = json.loads(p.read_text(encoding="utf-8"))
        pool_id = f"{NS}:{p.stem}"
        elements = []
        for e in obj.get("elements", []):
            inner = _unwrap_element(e.get("element"))
            if inner is None or "location" not in inner:
                continue
            loc = inner["location"]
            elements.append(loc)
            procs = inner.get("processors")
            # External ref looks like a bare string; inline list looks like {"processors": [...]}.
            if isinstance(procs, str):
                proc_refs[(pool_id, loc)] = procs
        pools[pool_id] = elements
    return pools, proc_refs

# ---------- Load all ----------
def load_all():
    structures = {}
    for p in STRUCT_DIR.rglob("*.nbt"):
        s = parse_structure(p)
        structures[s.id] = s
    pools, proc_refs = load_pools()
    return structures, pools, proc_refs

# ---------- Classification ----------
PAD_JIGSAW_NAME = f"{NS}:landing_pad_top"

def classify(structures, pools):
    """Return (pads, ships, sheriff_root, ship_pool_ids).
    Ships are any pool elements reachable from a landing_pad_top jigsaw — agnostic
    to the pool's name, so pools renamed off the legacy `airships_*` prefix
    (e.g. `width_9_landing_pad`) still resolve correctly."""
    pads = sorted([sid for sid in structures if sid.endswith("_pad")])
    ship_pool_ids = set()
    ships = set()
    for pad in pads:
        s = structures[pad]
        for j in s.jigsaws:
            if j.get("name") != PAD_JIGSAW_NAME: continue
            pool = j.get("pool")
            if not pool or pool not in pools: continue
            ship_pool_ids.add(pool)
            for e in pools[pool]:
                if e in structures: ships.add(e)
    ships = sorted(ships)
    sheriff_root = f"{NS}:village/common/sheriff_station"
    if sheriff_root not in structures:
        sheriff_root = None
    return pads, ships, sheriff_root, ship_pool_ids

# ---------- Mating-jigsaw alignment ----------
FACE_UNIT = {
    "east":  (1, 0, 0),
    "west":  (-1, 0, 0),
    "south": (0, 0, 1),
    "north": (0, 0, -1),
    "up":    (0, 1, 0),
    "down":  (0, -1, 0),
}

def face_of(orientation):
    return orientation.split("_", 1)[0] if orientation else None

def find_jig_by_name(s, name):
    for j in s.jigsaws:
        if j["name"] == name: return j
    return None

# ---------- Appendage graph walk ----------
def walk_appendages(parent_id, structures, pools, exclude_pool_ids=()):
    """Return list of (child_id, parent_id, parent_jigsaw, child_jigsaw) tuples,
    each entry naming one piece to place via mating-jigsaw alignment.
    Skips pools whose id is in exclude_pool_ids (set or iterable of pool ids)."""
    exclude = set(exclude_pool_ids)
    out = []
    seen = set()
    def rec(pid):
        if pid in seen: return
        seen.add(pid)
        s = structures.get(pid)
        if not s: return
        for j in s.jigsaws:
            pool = j.get("pool")
            if not pool or pool == "minecraft:empty": continue
            if pool in exclude: continue
            elements = pools.get(pool, [])
            for child_id in elements:
                child = structures.get(child_id)
                if not child: continue
                target = j.get("target")
                cj = find_jig_by_name(child, target) if target else None
                if cj is None: continue
                out.append((child_id, pid, j, cj))
                rec(child_id)
    rec(parent_id)
    return out

# ---------- Group placement ----------
def place_piece(commands, sid, structures, x, y, z):
    """Emit /place template + SAVE block for one piece at origin (x,y,z).
    Returns (minX, minY, minZ, maxX, maxY, maxZ) world AABB inclusive."""
    s = structures[sid]
    w, h, d = s.size
    commands.append(f"place template {sid} {x} {y} {z}")
    # SAVE block one north (-Z) of NW corner, same Y as piece origin.
    # Lives in the inter-row gutter / outside the piece footprint and offsets +Z=1
    # back to the piece origin so the bbox overlay wraps the structure exactly.
    save_x, save_y, save_z = x, y, z - 1
    commands.append(f'setblock {save_x} {save_y} {save_z} air')
    commands.append(
        f'setblock {save_x} {save_y} {save_z} '
        f'minecraft:structure_block[mode=save]'
        f'{{mode:"SAVE",name:"{sid}",'
        f'posX:0,posY:0,posZ:1,'
        f'sizeX:{w},sizeY:{h},sizeZ:{d},'
        f'showboundingbox:1b,ignoreEntities:1b}}'
    )
    return (x, y, z, x+w-1, y+h-1, z+d-1)

def compute_child_origin(parent_origin, parent_jig, child_jig):
    px, py, pz = parent_origin
    plx, ply, plz = parent_jig["pos"]
    face = face_of(parent_jig["orientation"])
    ux, uy, uz = FACE_UNIT[face]
    # World pos of parent jigsaw block
    P = (px + plx, py + ply, pz + plz)
    # Child jigsaw must sit at P + unit(D)
    child_jig_world = (P[0] + ux, P[1] + uy, P[2] + uz)
    clx, cly, clz = child_jig["pos"]
    return (child_jig_world[0] - clx, child_jig_world[1] - cly, child_jig_world[2] - clz)

def place_group(commands, root_id, structures, root_origin, edges):
    """Place a root and its edges (list of (child_id, parent_id, pj, cj))."""
    placed_origin = {root_id: root_origin}
    bboxes = [place_piece(commands, root_id, structures, *root_origin)]
    for child_id, parent_id, pj, cj in edges:
        po = placed_origin.get(parent_id)
        if po is None: continue
        co = compute_child_origin(po, pj, cj)
        placed_origin[child_id] = co
        bboxes.append(place_piece(commands, child_id, structures, *co))
    # group AABB
    xs1 = min(b[0] for b in bboxes); ys1 = min(b[1] for b in bboxes); zs1 = min(b[2] for b in bboxes)
    xs2 = max(b[3] for b in bboxes); ys2 = max(b[4] for b in bboxes); zs2 = max(b[5] for b in bboxes)
    return (xs1, ys1, zs1, xs2, ys2, zs2)

# ---------- Variant row (row 5) ----------
def load_variant_palettes(proc_list_ref):
    """Read a processor_list JSON, find the first mcpirates:variant_swap processor,
    and return (first_family_name, [palette, ...]) — palette is the raw JSON dict
    with `weight` and `variants`. Returns None if no variant_swap is present."""
    if not proc_list_ref or not proc_list_ref.startswith(f"{NS}:"):
        return None
    fp = PROC_LIST_DIR / (proc_list_ref[len(NS) + 1:] + ".json")
    if not fp.exists():
        return None
    obj = json.loads(fp.read_text(encoding="utf-8"))
    for proc in obj.get("processors", []):
        if proc.get("processor_type") != f"{NS}:variant_swap":
            continue
        families = proc.get("families", {})
        if not families:
            continue
        first_key = next(iter(families))
        return first_key, families[first_key]
    return None

def place_variant_piece(commands, ship_id, idx, structures, x, y, z, label):
    """Emit /mcpirates place_variant + SAVE block for one variant placement.
    Same x/y/z + bbox convention as place_piece, label is the SAVE bbox name."""
    s = structures[ship_id]
    w, h, d = s.size
    # ship_id is `mcpirates:airship_small`; the command takes the bare kind name.
    kind = ship_id.split(":", 1)[1] if ":" in ship_id else ship_id
    commands.append(f"mcpirates place_variant {kind} {idx} {x} {y} {z}")
    save_x, save_y, save_z = x, y, z - 1
    commands.append(f'setblock {save_x} {save_y} {save_z} air')
    commands.append(
        f'setblock {save_x} {save_y} {save_z} '
        f'minecraft:structure_block[mode=save]'
        f'{{mode:"SAVE",name:"{label}",'
        f'posX:0,posY:0,posZ:1,'
        f'sizeX:{w},sizeY:{h},sizeZ:{d},'
        f'showboundingbox:1b,ignoreEntities:1b}}'
    )
    return (x, y, z, x + w - 1, y + h - 1, z + d - 1)

def build_variant_row(commands, row_label, ship_variant_specs, z_top, structures):
    """Lay variant ships left-to-right. ship_variant_specs is a list of
    (ship_id, palette_idx, label). Returns row depth."""
    cursor_x = 0
    max_depth_z = z_top
    for ship_id, idx, label in ship_variant_specs:
        s = structures[ship_id]
        w, h, d = s.size
        x, y, z = cursor_x, FLOOR_Y, z_top
        commands.append(f"# --- {row_label}: {label} @ ({x},{y},{z})")
        bb = place_variant_piece(commands, ship_id, idx, structures, x, y, z, label)
        cursor_x = bb[3] + 1 + PIECE_GUTTER
        max_depth_z = max(max_depth_z, bb[5])
    return max_depth_z - z_top + 1

# ---------- Build row ----------
def build_row(commands, row_label, groups, z_top, structures):
    """Lay groups left-to-right starting at x=0, z=z_top. Returns row depth (z extent)."""
    cursor_x = 0
    max_depth_z = z_top
    for label, root_id, edges in groups:
        # Pre-pass: compute group bbox at trial origin (cursor_x, FLOOR_Y, z_top)
        trial_origin = (cursor_x, FLOOR_Y, z_top)
        # Compute footprint without placing — replay edges with a dummy collector
        bx1, by1, bz1, bx2, by2, bz2 = simulate_group_bbox(root_id, structures, trial_origin, edges)
        # shift everything if trial bbox extends west of cursor_x (e.g. negative dx)
        shift_x = cursor_x - bx1
        shift_z = z_top - bz1
        actual_origin = (trial_origin[0] + shift_x, trial_origin[1], trial_origin[2] + shift_z)
        commands.append(f"# --- {row_label}: {label} @ {actual_origin}")
        gb = place_group(commands, root_id, structures, actual_origin, edges)
        # Advance cursor
        cursor_x = gb[3] + 1 + PIECE_GUTTER
        max_depth_z = max(max_depth_z, gb[5])
    return max_depth_z - z_top + 1  # row depth (z extent)

def simulate_group_bbox(root_id, structures, root_origin, edges):
    placed_origin = {root_id: root_origin}
    s = structures[root_id]
    bboxes = [(root_origin[0], root_origin[1], root_origin[2],
               root_origin[0]+s.size[0]-1, root_origin[1]+s.size[1]-1, root_origin[2]+s.size[2]-1)]
    for child_id, parent_id, pj, cj in edges:
        po = placed_origin.get(parent_id)
        if po is None: continue
        co = compute_child_origin(po, pj, cj)
        placed_origin[child_id] = co
        cs = structures[child_id]
        bboxes.append((co[0], co[1], co[2], co[0]+cs.size[0]-1, co[1]+cs.size[1]-1, co[2]+cs.size[2]-1))
    return (min(b[0] for b in bboxes), min(b[1] for b in bboxes), min(b[2] for b in bboxes),
            max(b[3] for b in bboxes), max(b[4] for b in bboxes), max(b[5] for b in bboxes))

# ---------- Main ----------
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ships-only", action="store_true",
                        help="Emit only the ships-alone row (skip pads, assemblies, sheriff).")
    args = parser.parse_args()

    structures, pools, proc_refs = load_all()
    pads, ships, sheriff_root, ship_pool_ids = classify(structures, pools)
    commands = []
    commands.append("# === pre-layout: forceload ===")
    # Generous box covering the worst-case 5-row layout (row 5 with all 5 ships ×
    # 3 variants stretches +X out past 200). If you add more variants per ship or
    # more ships, bump the X bound.
    commands.append("forceload add -16 -16 256 256")
    z_cursor = 0

    if not args.ships_only:
        # Row 1: pads + non-ship appendages (exclude any pool reachable from landing_pad_top)
        row1_groups = []
        for pad in pads:
            edges = walk_appendages(pad, structures, pools, exclude_pool_ids=ship_pool_ids)
            row1_groups.append((pad, pad, edges))
        commands.append(f"# === ROW 1: pads + appendages, z={z_cursor} ===")
        depth1 = build_row(commands, "row1-pad+appendages", row1_groups, z_cursor, structures)
        z_cursor += depth1 + ROW_GUTTER

    # Ships alone (only row in --ships-only; row 2 otherwise)
    ships_groups = [(ship, ship, []) for ship in ships]
    label = "ROW: ships" if args.ships_only else "ROW 2: ships alone"
    commands.append(f"# === {label}, z={z_cursor} ===")
    depth_ships = build_row(commands, "ships", ships_groups, z_cursor, structures)
    z_cursor += depth_ships + ROW_GUTTER

    if not args.ships_only:
        # One row-3 slot per (pad, ship) — sharing a pad pool would otherwise stack
        # multiple ships at the same mating coords and overwrite each other.
        row3_groups = []
        for pad in pads:
            non_ship_edges = walk_appendages(pad, structures, pools, exclude_pool_ids=ship_pool_ids)
            pad_ships = []
            s = structures[pad]
            for j in s.jigsaws:
                if j.get("name") != PAD_JIGSAW_NAME: continue
                pool = j.get("pool")
                if pool in ship_pool_ids:
                    for ship_id in pools.get(pool, []):
                        if ship_id in structures and (pad, j, ship_id) not in pad_ships:
                            pad_ships.append((pad, j, ship_id))
            if not pad_ships:
                continue
            for _, pad_jig, ship_id in pad_ships:
                ship_jig = find_jig_by_name(structures[ship_id], pad_jig.get("target"))
                if ship_jig is None: continue
                edges = list(non_ship_edges)
                edges.append((ship_id, pad, pad_jig, ship_jig))
                row3_groups.append((f"{pad} + {ship_id}", pad, edges))
        commands.append(f"# === ROW 3: full assemblies, z={z_cursor} ===")
        depth3 = build_row(commands, "row3-assembled", row3_groups, z_cursor, structures)
        z_cursor += depth3 + ROW_GUTTER

        if sheriff_root:
            edges = walk_appendages(sheriff_root, structures, pools)
            row4_groups = [(sheriff_root, sheriff_root, edges)]
            commands.append(f"# === ROW 4: sheriff, z={z_cursor} ===")
            depth4 = build_row(commands, "row4-sheriff", row4_groups, z_cursor, structures)
            z_cursor += depth4 + ROW_GUTTER

        # Row 5: variants — for every ship pool element with a string `processors`
        # ref, load its processor_list, find the first variant_swap family, and
        # place one ship per palette index using `mcpirates place_variant`.
        variant_specs = []  # (ship_id, idx, label)
        for pool_id in sorted(ship_pool_ids):
            for ship_id in pools.get(pool_id, []):
                if ship_id not in structures: continue
                ref = proc_refs.get((pool_id, ship_id))
                if not ref: continue
                loaded = load_variant_palettes(ref)
                if loaded is None: continue
                family_name, palettes = loaded
                kind = ship_id.split(":", 1)[1] if ":" in ship_id else ship_id
                for i, pal in enumerate(palettes):
                    colors = "_".join(pal.get("variants", []))
                    variant_specs.append((ship_id, i, f"{kind}_var{i}_{colors}"))
        if variant_specs:
            commands.append(f"# === ROW 5: ship variants, z={z_cursor} ===")
            depth5 = build_variant_row(commands, "row5-variants", variant_specs, z_cursor, structures)
            z_cursor += depth5 + ROW_GUTTER

    commands.append(f"# === post-layout: spawn + tp ===")
    centre_z = z_cursor // 2
    commands.append(f"setworldspawn 0 -57 {centre_z}")
    commands.append(f"tp Dev 0 -57 {centre_z}")
    commands.append("kill @e[type=item]")

    sys.stdout.write("\n".join(commands) + "\n")

if __name__ == "__main__":
    main()
