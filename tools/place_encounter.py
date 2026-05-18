"""Emit MC commands for the /place-encounter air-combat test scenario.

Layout (defaults):
  - Pirate ship at (0, -58, 0). Kind selected via --pirate (default: ramship).
    Acts as the hostile target. Its anchor is left intact so AirshipLiftoffTrigger
    activates it the moment a player on a SubLevel enters trigger range.
  - Airship_small at (31, -58, 105) acts as the player's ride. Nose = NORTH
    (its NBT-default forward), pointing at the pirate ship which is ~100 blocks
    NORTH and ~30 blocks WEST.
  - Airship_small's ship_anchor block (NBT (3,3,5) → world (34,-55,110)) is
    cleared to air so the brain never auto-promotes it; the user can ride it
    without becoming a pirate.
  - Dev TP'd to (38, -55, 110) — just east of the airship_small deck level.

Run from project root:
    python tools/place_encounter.py [--pirate <kind>] > /tmp/encounter.mcfunction
"""
import argparse, gzip, struct, sys
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STRUCT_DIR = ROOT / "src/main/resources/data/mcpirates/structure"

FLOOR_Y = -58

# Mirrors com.mcpirates.airship.ships.AnchorNbtPositions.BY_NAME — the NBT-frame
# offset of each kind's ship_anchor block from the structure's NW origin.
ANCHOR_NBT_REL = {
    "airship_small":  (3, 3,  5),
    "crossbow_board": (2, 3,  8),
    "galleon":        (3, 9, 13),
    "ramship":        (3, 4,  8),
}

PLAYER_KIND = "airship_small"
PLAYER_ORIGIN = (31, FLOOR_Y, 105)
PLAYER_TP = (38, -55, 110)


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


def read_nbt_size(kind):
    path = STRUCT_DIR / f"{kind}.nbt"
    raw = path.read_bytes()
    if len(raw) >= 2 and raw[0] == 0x1f and raw[1] == 0x8b:
        raw = gzip.decompress(raw)
    root = NBT(raw).p()
    sz = root["size"]
    return (sz[0], sz[1], sz[2])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pirate", default="ramship", choices=sorted(ANCHOR_NBT_REL.keys()),
                    help="kind of pirate ship to spawn (default: ramship)")
    args = ap.parse_args()

    pirate_kind = args.pirate
    if pirate_kind == PLAYER_KIND:
        sys.exit(f"pirate kind {pirate_kind} clashes with fixed player ship kind {PLAYER_KIND}")

    pirate_size = read_nbt_size(pirate_kind)
    player_size = read_nbt_size(PLAYER_KIND)

    # Pirate ship origin: NW corner at z=0. Player at z=105 → ~100 blocks south.
    # The 30-block lateral offset keeps the two hulls from overlapping for the
    # largest pirate kind (galleon, 12×15×28) without flying off the forceloaded box.
    pirate_origin = (0, FLOOR_Y, 0)
    px_origin, py_origin, pz_origin = pirate_origin
    pw, ph, pd = pirate_size

    ax, ay, az = PLAYER_ORIGIN
    aw, ah, ad = player_size

    out = []
    out.append("# === pre-layout: forceload ===")
    forceload_min_x = min(px_origin, ax) - 16
    forceload_max_x = max(px_origin + pw, ax + aw) + 16
    forceload_min_z = min(pz_origin, az) - 16
    forceload_max_z = max(pz_origin + pd, az + ad) + 16
    out.append(f"forceload add {forceload_min_x} {forceload_min_z} {forceload_max_x} {forceload_max_z}")

    out.append(f"# --- pirate ({pirate_kind}) @ {pirate_origin} size {pirate_size}")
    out.append(f"place template mcpirates:{pirate_kind} {px_origin} {py_origin} {pz_origin}")
    out.append(f"setblock {px_origin} {py_origin} {pz_origin-1} air")
    out.append(
        f'setblock {px_origin} {py_origin} {pz_origin-1} minecraft:structure_block[mode=save]'
        f'{{mode:"SAVE",name:"mcpirates:{pirate_kind}",'
        f'posX:0,posY:0,posZ:1,sizeX:{pw},sizeY:{ph},sizeZ:{pd},'
        f'showboundingbox:1b,ignoreEntities:1b}}'
    )

    out.append(f"# --- player ({PLAYER_KIND}) @ {PLAYER_ORIGIN} size {player_size}")
    out.append(f"place template mcpirates:{PLAYER_KIND} {ax} {ay} {az}")
    out.append(f"setblock {ax} {ay} {az-1} air")
    out.append(
        f'setblock {ax} {ay} {az-1} minecraft:structure_block[mode=save]'
        f'{{mode:"SAVE",name:"mcpirates:{PLAYER_KIND}",'
        f'posX:0,posY:0,posZ:1,sizeX:{aw},sizeY:{ah},sizeZ:{ad},'
        f'showboundingbox:1b,ignoreEntities:1b}}'
    )

    # Strip player ship's anchor — the brain skips ships without an anchor BE,
    # so the player can ride airship_small without becoming a pirate target.
    pa_rel = ANCHOR_NBT_REL[PLAYER_KIND]
    arx, ary, arz = ax + pa_rel[0], ay + pa_rel[1], az + pa_rel[2]
    out.append(f"# --- strip player-ship anchor at world {(arx, ary, arz)}")
    out.append(f"setblock {arx} {ary} {arz} air")

    # Pre-glue the player ship so it becomes a Sable contraption immediately;
    # the pirate ship is intentionally NOT pre-glued — its activateAnchor flow
    # is the thing under test.
    gx1, gy1, gz1 = ax, ay, az
    gx2, gy2, gz2 = ax + aw - 1, ay + ah - 1, az + ad - 1
    out.append(f"# --- glue player ship bbox ({gx1},{gy1},{gz1})..({gx2},{gy2},{gz2})")
    out.append(f"simulated glue {gx1} {gy1} {gz1} {gx2} {gy2} {gz2}")

    tx, ty, tz = PLAYER_TP
    out.append("# === post-layout: spawn + tp ===")
    out.append(f"setworldspawn {tx} {ty} {tz}")
    out.append(f"tp Dev {tx} {ty} {tz} facing {ax + aw // 2} {ty} {az + ad // 2}")
    out.append("kill @e[type=item]")

    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
