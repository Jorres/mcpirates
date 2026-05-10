"""
import_airship.py — convert a structure-block-saved airship NBT into an outpost-ready piece.

Reads a vanilla-format structure NBT (saved via Structure Block in dev MC), injects a single
downward-pointing jigsaw block at the center of its bottom layer (so it can attach to the
landing pad's upward jigsaw), and writes the result into the mod's resources.

Usage:
    python tools/import_airship.py <input-nbt> [--name airship_small]

Re-run any time you re-save the ship.
"""
from __future__ import annotations

import argparse
import gzip
from pathlib import Path

from nbtlib import File, Compound, List, Int, String

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = REPO_ROOT / "src" / "main" / "resources" / "data" / "mcpirates" / "structure"

JIGSAW_NAME = "minecraft:jigsaw"
ATTACH_JIGSAW_NBT = {
    "name": "mcpirates:airship_keel",
    "target": "mcpirates:landing_pad_top",
    "pool": "minecraft:empty",
    "final_state": "minecraft:air",
    "joint": "rollable",
}
ATTACH_ORIENTATION = "down_south"


def find_or_add_palette_index(palette, block_name: str, properties: dict | None = None) -> int:
    for i, entry in enumerate(palette):
        if str(entry.get("Name", "")) != block_name:
            continue
        existing_props = {k: str(v) for k, v in dict(entry.get("Properties", {})).items()}
        if existing_props == (properties or {}):
            return i
    new_entry = Compound({"Name": String(block_name)})
    if properties:
        new_entry["Properties"] = Compound({k: String(v) for k, v in properties.items()})
    palette.append(new_entry)
    return len(palette) - 1


def inject_attach_jigsaw(nbt: Compound) -> None:
    size = list(nbt["size"])
    sx, sy, sz = int(size[0]), int(size[1]), int(size[2])
    cx, cz = sx // 2, sz // 2

    palette = nbt["palette"]
    jigsaw_idx = find_or_add_palette_index(
        palette, JIGSAW_NAME, {"orientation": ATTACH_ORIENTATION}
    )

    blocks = nbt["blocks"]
    # remove any block currently at the attach position so the jigsaw isn't shadowed
    keep = []
    for b in blocks:
        pos = list(b["pos"])
        if int(pos[0]) == cx and int(pos[1]) == 0 and int(pos[2]) == cz:
            continue
        keep.append(b)
    blocks.clear()
    blocks.extend(keep)

    new_block = Compound({
        "state": Int(jigsaw_idx),
        "pos": List[Int]([Int(cx), Int(0), Int(cz)]),
        "nbt": Compound({k: String(v) for k, v in ATTACH_JIGSAW_NBT.items()}),
    })
    blocks.append(new_block)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", help="Path to structure-block-saved NBT file.")
    parser.add_argument(
        "--name",
        default="airship_small",
        help="Output resource name (default: airship_small) — file will be <name>.nbt.",
    )
    args = parser.parse_args()

    src = Path(args.input)
    if not src.exists():
        raise SystemExit(f"Input not found: {src}")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    dst = OUTPUT_DIR / f"{args.name}.nbt"

    with gzip.open(src, "rb") as f:
        nbt_file = File.parse(f)

    inject_attach_jigsaw(nbt_file)

    nbt_file.save(str(dst), gzipped=True)

    print(f"Wrote {dst.relative_to(REPO_ROOT)}  (size {list(nbt_file['size'])})")


if __name__ == "__main__":
    main()
