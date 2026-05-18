"""Flip `reversed` on every aeronautics:andesite_propeller in galleon.nbt.

Hypothesis under test: galleon's eight propellers were authored with
reversed=false and produce thrust pointing the wrong way (ship runs from the
target instead of toward it). This script flips the palette entries in place;
all 8 blocks reference them, so a palette-level edit re-orients every prop.

Edits palette entries only — block positions and counts are untouched.

Run from project root:
    python tools/patch_galleon_propellers.py
"""
import shutil
from pathlib import Path

from nbtlib import File, String

REPO = Path(__file__).resolve().parents[1]
NBT_PATH = REPO / "src/main/resources/data/mcpirates/structure/galleon.nbt"
BACKUP_PATH = REPO / "tools/backups/galleon.before-propeller-flip.nbt"


def main() -> None:
    if not NBT_PATH.exists():
        raise SystemExit(f"missing: {NBT_PATH}")
    BACKUP_PATH.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(NBT_PATH, BACKUP_PATH)
    print(f"backup: {BACKUP_PATH}")

    f = File.load(str(NBT_PATH), gzipped=True)
    palette = f["palette"]
    flipped = []
    for i, entry in enumerate(palette):
        name = str(entry["Name"])
        if name != "aeronautics:andesite_propeller":
            continue
        if "Properties" not in entry:
            continue
        props = entry["Properties"]
        if "reversed" not in props:
            continue
        old = str(props["reversed"])
        new = "true" if old == "false" else "false"
        props["reversed"] = String(new)
        flipped.append((i, str(entry.get("Properties", {}).get("facing", "?")), old, new))

    if not flipped:
        raise SystemExit("no andesite_propeller palette entries found — nothing flipped")

    for i, facing, old, new in flipped:
        print(f"  palette[{i}] facing={facing}: reversed {old} -> {new}")

    f.save(str(NBT_PATH), gzipped=True)
    print(f"wrote: {NBT_PATH}")


if __name__ == "__main__":
    main()
