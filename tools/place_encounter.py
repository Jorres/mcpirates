"""Emit MC commands for the /place-encounter air-combat test scenario.

Layout:
  - Ramship at (0, -58, 0).  Footprint X=0..8, Z=0..21. (acts as hostile target.)
  - Airship_small at (31, -58, 105).  Footprint X=31..37, Z=105..116.
    Nose = NORTH (its NBT-default forward), pointing at the ramship which is
    ~100 blocks NORTH and ~30 blocks WEST.
  - Airship_small's ship_anchor block (NBT (3,3,5) → world (34,-55,110)) is
    cleared to air so the brain never auto-promotes it; the user can ride it
    as their own ship without becoming a pirate.
  - Dev TP'd to (38, -55, 110) — just east of the airship_small deck level.

Run from project root:
    python tools/place_encounter.py > /tmp/encounter.mcfunction
"""
import sys
from pathlib import Path

FLOOR_Y = -58

# Ramship origin (NW corner of NBT). Size [9, 26, 22].
RAMSHIP_ORIGIN = (0, FLOOR_Y, 0)
RAMSHIP_SIZE   = (9, 26, 22)

# Airship_small origin. Size [7, 25, 12]. NBT-default forward = NORTH (-Z),
# so placing at default rotation makes its nose point at -Z. Putting it
# at Z=105 (south of ramship at Z=0..21) means the nose points at the ramship.
AIRSHIP_ORIGIN = (31, FLOOR_Y, 105)
AIRSHIP_SIZE   = (7, 25, 12)
# AnchorNbtPositions.AIRSHIP_SMALL = (3, 3, 5). World = origin + that.
AIRSHIP_ANCHOR_NBT_REL = (3, 3, 5)

# Player TP target — just east of the airship_small at deck height.
PLAYER_TP = (38, -55, 110)

def main():
    out = []
    out.append("# === pre-layout: forceload ===")
    out.append("forceload add -16 -16 64 144")

    # Ramship
    rx, ry, rz = RAMSHIP_ORIGIN
    rw, rh, rd = RAMSHIP_SIZE
    out.append(f"# --- ramship (target) @ {RAMSHIP_ORIGIN}")
    out.append(f"place template mcpirates:ramship {rx} {ry} {rz}")
    out.append(f"setblock {rx} {ry} {rz-1} air")
    out.append(
        f'setblock {rx} {ry} {rz-1} minecraft:structure_block[mode=save]'
        f'{{mode:"SAVE",name:"mcpirates:ramship",'
        f'posX:0,posY:0,posZ:1,sizeX:{rw},sizeY:{rh},sizeZ:{rd},'
        f'showboundingbox:1b,ignoreEntities:1b}}'
    )

    # Airship_small (player's ship)
    ax, ay, az = AIRSHIP_ORIGIN
    aw, ah, ad = AIRSHIP_SIZE
    out.append(f"# --- airship_small (player) @ {AIRSHIP_ORIGIN}")
    out.append(f"place template mcpirates:airship_small {ax} {ay} {az}")
    out.append(f"setblock {ax} {ay} {az-1} air")
    out.append(
        f'setblock {ax} {ay} {az-1} minecraft:structure_block[mode=save]'
        f'{{mode:"SAVE",name:"mcpirates:airship_small",'
        f'posX:0,posY:0,posZ:1,sizeX:{aw},sizeY:{ah},sizeZ:{ad},'
        f'showboundingbox:1b,ignoreEntities:1b}}'
    )

    # Strip the player ship's anchor so the brain never auto-promotes it.
    arx, ary, arz = (ax + AIRSHIP_ANCHOR_NBT_REL[0],
                     ay + AIRSHIP_ANCHOR_NBT_REL[1],
                     az + AIRSHIP_ANCHOR_NBT_REL[2])
    out.append(f"# --- strip player-ship anchor at world {(arx, ary, arz)}")
    out.append(f"setblock {arx} {ary} {arz} air")

    # Pre-glue the airship_small so it becomes a Sable contraption immediately
    # (skips the Aeronautics-lever assembly dance the user would otherwise need).
    # Bbox covers the full hull volume (size + 1 to make /simulated glue inclusive).
    # Not gluing the ramship — its anchor + activateAnchor path is under test.
    gx1, gy1, gz1 = ax, ay, az
    gx2, gy2, gz2 = ax + aw - 1, ay + ah - 1, az + ad - 1
    out.append(f"# --- glue airship_small bbox ({gx1},{gy1},{gz1})..({gx2},{gy2},{gz2})")
    out.append(f"simulated glue {gx1} {gy1} {gz1} {gx2} {gy2} {gz2}")

    # Player + spawn.
    px, py, pz = PLAYER_TP
    out.append("# === post-layout: spawn + tp ===")
    out.append(f"setworldspawn {px} {py} {pz}")
    out.append(f"tp Dev {px} {py} {pz} facing {ax + aw // 2} {py} {az + ad // 2}")
    out.append("kill @e[type=item]")

    sys.stdout.write("\n".join(out) + "\n")

if __name__ == "__main__":
    main()
