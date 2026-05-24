"""Strip the `mcpirates:outpost_anchor` jigsaw block from each ship-pad NBT.

These jigsaws used to be the receiver side of vanilla pillager_outpost integration
(base_plate_with_<ship>'s east-facing airship_anchor mated into them). We're killing
the integration; pads now exist as standalone PermittedShipOutpostStructure start
pieces. The receiver jigsaw is dead weight and its final_state (stone_bricks on
crossbow/ramship, air on airship_small) would otherwise leave a stray block at the
pad edge.

Run from project root:
    python tools/strip_outpost_anchor_jigsaws.py
"""
import shutil
from pathlib import Path

from nbtlib import File

REPO = Path(__file__).resolve().parents[1]
STRUCT_DIR = REPO / "src/main/resources/data/mcpirates/structure"
BACKUP_DIR = REPO / "tools/backups"

TARGET_NAME = "mcpirates:outpost_anchor"
PADS = ["airship_small_pad", "crossbow_board_pad", "ramship_pad"]


def strip(path: Path) -> int:
    f = File.load(str(path), gzipped=True)
    blocks = f["blocks"]
    keep = []
    removed = 0
    for b in blocks:
        nbt = b.get("nbt")
        if nbt is not None and str(nbt.get("name", "")) == TARGET_NAME:
            removed += 1
            continue
        keep.append(b)
    if removed == 0:
        return 0
    blocks.clear()
    blocks.extend(keep)
    f.save(str(path), gzipped=True)
    return removed


def main() -> None:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    for name in PADS:
        path = STRUCT_DIR / f"{name}.nbt"
        if not path.exists():
            raise SystemExit(f"missing: {path}")
        backup = BACKUP_DIR / f"{name}.before-anchor-strip.nbt"
        shutil.copy2(path, backup)
        n = strip(path)
        print(f"{name}: removed {n} outpost_anchor jigsaw(s) (backup: {backup.name})")


if __name__ == "__main__":
    main()
