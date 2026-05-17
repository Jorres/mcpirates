"""Discover mcpirates structures + appendage graph, classify, compute 4-row grid layout,
and emit Minecraft commands (one per line) to place the layout via /place template + SAVE blocks.

Run from the mcpirates project root:
    python tools/place_layout.py > /tmp/layout.mcfunction

Reads:
  src/main/resources/data/mcpirates/structure/**.nbt
  src/main/resources/data/mcpirates/worldgen/template_pool/*.json

Emits commands to stdout. Caller pipes lines into RCON.
"""
import gzip, json, struct, sys
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STRUCT_DIR = ROOT / "src/main/resources/data/mcpirates/structure"
POOL_DIR   = ROOT / "src/main/resources/data/mcpirates/worldgen/template_pool"

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
        nbt = b.get("nbt")
        if not nbt: continue
        if nbt.get("id") != "minecraft:jigsaw": continue
        state_idx = b["state"]
        props = palette[state_idx].get("Properties", {})
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
def load_pools():
    pools = {}
    for p in POOL_DIR.glob("*.json"):
        obj = json.loads(p.read_text(encoding="utf-8"))
        pool_id = f"{NS}:{p.stem}"
        elements = []
        for e in obj.get("elements", []):
            el = e.get("element", {})
            if el.get("element_type") == "minecraft:single_pool_element" and "location" in el:
                elements.append(el["location"])
        pools[pool_id] = elements
    return pools

# ---------- Load all ----------
def load_all():
    structures = {}
    for p in STRUCT_DIR.rglob("*.nbt"):
        s = parse_structure(p)
        structures[s.id] = s
    pools = load_pools()
    return structures, pools

# ---------- Classification ----------
def classify(structures, pools):
    """Return (pads, ships, sheriff_root). pads/ships are lists of struct ids."""
    pads = sorted([sid for sid in structures if sid.endswith("_pad")])
    # Ships = elements of any airships_* pool
    ships = set()
    for pid, elts in pools.items():
        if pid.startswith(f"{NS}:airships"):
            for e in elts:
                ships.add(e)
    ships = sorted(ships & set(structures))
    sheriff_root = f"{NS}:village/common/sheriff_station"
    if sheriff_root not in structures:
        sheriff_root = None
    return pads, ships, sheriff_root

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
def walk_appendages(parent_id, structures, pools, exclude_pool_prefixes=()):
    """Return list of (child_id, parent_id, parent_jigsaw, child_jigsaw) tuples,
    each entry naming one piece to place via mating-jigsaw alignment.
    Skips pools whose id starts with any prefix in exclude_pool_prefixes."""
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
            if any(pool.startswith(pref) for pref in exclude_pool_prefixes): continue
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
    structures, pools = load_all()
    pads, ships, sheriff_root = classify(structures, pools)
    commands = []
    # Forceload up front so /place template doesn't drop blocks into unloaded chunks.
    # Layout spans roughly X=-1..100, Z=-1..160. Round to chunk granularity.
    commands.append("# === pre-layout: forceload ===")
    commands.append("forceload add -16 -16 128 192")
    z_cursor = 0

    # Row 1: pads + non-ship appendages (exclude airships_* pools)
    row1_groups = []
    for pad in pads:
        edges = walk_appendages(pad, structures, pools, exclude_pool_prefixes=(f"{NS}:airships",))
        row1_groups.append((pad, pad, edges))
    commands.append(f"# === ROW 1: pads + appendages, z={z_cursor} ===")
    depth1 = build_row(commands, "row1-pad+appendages", row1_groups, z_cursor, structures)
    z_cursor += depth1 + ROW_GUTTER

    # Row 2: ships alone (no appendages)
    row2_groups = [(ship, ship, []) for ship in ships]
    commands.append(f"# === ROW 2: ships alone, z={z_cursor} ===")
    depth2 = build_row(commands, "row2-ships", row2_groups, z_cursor, structures)
    z_cursor += depth2 + ROW_GUTTER

    # Row 3: assembled = pad + all appendages (including ship via airships_* pool)
    row3_groups = []
    for pad in pads:
        edges = walk_appendages(pad, structures, pools)  # no exclusion
        row3_groups.append((pad + " (assembled)", pad, edges))
    commands.append(f"# === ROW 3: full assemblies, z={z_cursor} ===")
    depth3 = build_row(commands, "row3-assembled", row3_groups, z_cursor, structures)
    z_cursor += depth3 + ROW_GUTTER

    # Row 4: sheriff
    if sheriff_root:
        edges = walk_appendages(sheriff_root, structures, pools)
        row4_groups = [(sheriff_root, sheriff_root, edges)]
        commands.append(f"# === ROW 4: sheriff, z={z_cursor} ===")
        depth4 = build_row(commands, "row4-sheriff", row4_groups, z_cursor, structures)
        z_cursor += depth4 + ROW_GUTTER

    # Trailing setworldspawn + tp
    commands.append(f"# === post-layout: spawn + tp ===")
    centre_z = z_cursor // 2
    commands.append(f"setworldspawn 0 -59 {centre_z}")
    commands.append(f"tp Dev 0 -59 {centre_z}")
    commands.append("kill @e[type=item]")

    sys.stdout.write("\n".join(commands) + "\n")

if __name__ == "__main__":
    main()
