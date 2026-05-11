"""
import_sheriff_station.py — copy the user's hand-built sheriff station NBT into
the mod resources tree and stamp the village-entrance jigsaw onto its west face.

Why a script and not just `cp`:
  - The raw structure-block save has no jigsaw connector. Without one, the piece
    can be placed into a village pool but the jigsaw assembler has nothing to
    plug into the road network, so nothing ever spawns it.
  - We may re-save the building over and over while iterating. Each re-save
    overwrites the input NBT cleanly; this script re-applies the jigsaw at the
    known entrance coords on each run, so the resources copy stays in sync.

Input default:
    runs/server/world/generated/minecraft/structures/sheriff-station.nbt
Output:
    src/main/resources/data/mcpirates/structure/village/common/sheriff_station.nbt

Entrance face:
    West (x=0) — the user's building has the entrance opening at x=1, z=2..4,
    flanked by hanging lanterns at (0,3,1) and (0,3,5). The jigsaw goes one
    block west of the wall, at floor level (y=1), centred on z=3.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from nbtlib import File, Compound, List, Int, String

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INPUT = REPO_ROOT / "runs" / "server" / "world" / "generated" / "minecraft" / "structures" / "sheriff-station.nbt"
OUT_PATH = (
    REPO_ROOT
    / "src" / "main" / "resources" / "data" / "mcpirates"
    / "structure" / "village" / "common" / "sheriff_station.nbt"
)

# Entrance jigsaw position + orientation. The structure-block save has no jigsaw,
# so we stamp one onto the entrance face here. Update for each new layout iteration
# (the building shape keeps moving as the design evolves) — overridable via CLI.
#
# Current default (v3, 9x8x12, entrance on +Z / south face): the south wall opens
# at z=11 around x=2..4; lanterns at (1,3,10) and (5,3,10) flank the opening at
# eave height. Jigsaw goes at the centre of the opening at floor level, facing
# south so the village road connects in from the +Z side.
JIGSAW_POS = (3, 1, 11)
JIGSAW_ORIENTATION = "south_up"


def build_jigsaw_palette_entry(orientation: str) -> Compound:
    return Compound({
        "Name": String("minecraft:jigsaw"),
        "Properties": Compound({"orientation": String(orientation)}),
    })


def build_jigsaw_be() -> Compound:
    return Compound({
        "joint": String("rollable"),
        "name": String("minecraft:building_entrance"),
        "pool": String("minecraft:empty"),
        # final_state controls what's left in the world after the jigsaw resolves.
        # `structure_void` is the modder-friendly default (everything bountiful and
        # vanilla pieces use) — it leaves a translucent block that the chunk save
        # treats as "no block" without re-running terrain placement.
        "final_state": String("minecraft:structure_void"),
        "id": String("minecraft:jigsaw"),
        "target": String("minecraft:building_entrance"),
    })


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT,
                        help="Path to the raw structure-block save NBT")
    parser.add_argument("--output", type=Path, default=OUT_PATH,
                        help="Where to write the resources-tree NBT")
    parser.add_argument("--jigsaw-pos", type=int, nargs=3, metavar=("X", "Y", "Z"),
                        default=list(JIGSAW_POS),
                        help="NBT-local position to place the entrance jigsaw")
    parser.add_argument("--jigsaw-orientation", default=JIGSAW_ORIENTATION,
                        choices=["north_up", "south_up", "east_up", "west_up",
                                 "up_north", "up_south", "up_east", "up_west",
                                 "down_north", "down_south", "down_east", "down_west"],
                        help="Jigsaw `orientation` blockstate (defaults to v3's south_up)")
    args = parser.parse_args()
    jigsaw_pos = tuple(args.jigsaw_pos)

    nbt = File.load(args.input, gzipped=True)

    # Drop the file-level root tag wrapper. Structure NBT at file root has keys
    # like 'size', 'palette', 'blocks', etc. nbtlib loads gzipped structure NBT
    # as a File with those keys directly accessible.
    palette = nbt["palette"]
    blocks = nbt["blocks"]

    # 1. Strip every existing jigsaw block (we re-stamp our own). Mirrors
    #    build_outpost_pieces.py's approach: even if the source had user-placed
    #    jigsaws, we want exactly the one we add below.
    palette_was = len(palette)
    jigsaw_pal_indices = {
        i for i, e in enumerate(palette)
        if str(e.get("Name", "")) == "minecraft:jigsaw"
    }
    if jigsaw_pal_indices:
        kept = [b for b in blocks if int(b["state"]) not in jigsaw_pal_indices]
        del blocks[:]
        blocks.extend(kept)
        print(f"stripped {len(jigsaw_pal_indices)} existing jigsaw palette entries "
              f"({palette_was - len(blocks)} blocks removed)")
    else:
        print("no existing jigsaws to strip")

    # 2. Add our entrance jigsaw to the palette and place one block.
    new_state = len(palette)
    palette.append(build_jigsaw_palette_entry(args.jigsaw_orientation))

    blocks.append(Compound({
        "state": Int(new_state),
        "pos": List[Int]([Int(jigsaw_pos[0]), Int(jigsaw_pos[1]), Int(jigsaw_pos[2])]),
        "nbt": build_jigsaw_be(),
    }))
    print(f"stamped jigsaw at {jigsaw_pos} orientation={args.jigsaw_orientation} "
          f"name=minecraft:building_entrance")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    nbt.save(args.output, gzipped=True)
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
