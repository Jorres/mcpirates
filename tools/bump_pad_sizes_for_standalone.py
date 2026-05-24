"""Bump each ship-pad NBT's size_y to contain its future jigsaw-attached ship.

Why this exists: when a pad serves as the START PIECE of a jigsaw structure (which is
what PermittedShipOutpostStructure does), JigsawPlacement's tryPlacingChildren uses the
parent's bbox as the local-shape constraint for interior up-facing jigsaws (see
JigsawPlacement.java:343 — boundingbox.isInside(blockpos2) → true → mutableobject1 =
local). The child ship's deflated bbox must fit inside the parent pad's bbox; a 3-tall
pad can't contain a 25-tall ship → rejected.

Vanilla pillager_outpost works around this with use_expansion_hack=true, but the
expansion only happens for CHILD pieces (line 427), never the start. The clean fix is
to grow the pad's nominal bbox to encompass the ship — extra layers are implicit
structure_void (no block placement), so visually nothing changes.

Per-pad target: ship_size_y + 2 (1 for the y=2 mating layer, 1 safety margin).
Run from project root:
    python tools/bump_pad_sizes_for_standalone.py
"""
import shutil
from pathlib import Path

from nbtlib import File, Int, IntArray

REPO = Path(__file__).resolve().parents[1]
STRUCT_DIR = REPO / "src/main/resources/data/mcpirates/structure"
BACKUP_DIR = REPO / "tools/backups"

# pad name -> ship NBT name it mates with (we read ship size_y dynamically)
PAD_SHIP_PAIRS = {
    "airship_small_pad": "airship_small",
    "crossbow_board_pad": "crossbow_board",
    "ramship_pad": "ramship",
    # galleon_pad was bumped earlier (size [16, 34, 32]); no-op when run again.
    "galleon_pad": "galleon",
}


def ship_size_y(name: str) -> int:
    f = File.load(str(STRUCT_DIR / f"{name}.nbt"), gzipped=True)
    return int(f["size"][1])


def bump(pad_name: str, target_y: int) -> tuple[int, int]:
    path = STRUCT_DIR / f"{pad_name}.nbt"
    f = File.load(str(path), gzipped=True)
    size = f["size"]
    old_y = int(size[1])
    if old_y >= target_y:
        return (old_y, old_y)
    # size is a TAG_List of 3 ints in this NBT (not IntArray). nbtlib gives us a List.
    size[1] = Int(target_y)
    f.save(str(path), gzipped=True)
    return (old_y, target_y)


def main() -> None:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    for pad, ship in PAD_SHIP_PAIRS.items():
        ship_y = ship_size_y(ship)
        target = ship_y + 2
        pad_path = STRUCT_DIR / f"{pad}.nbt"
        backup = BACKUP_DIR / f"{pad}.before-size-bump.nbt"
        if not backup.exists():
            shutil.copy2(pad_path, backup)
        old, new = bump(pad, target)
        if old == new:
            print(f"{pad}: y={old} already >= {target} (ship {ship} y={ship_y}) — skip")
        else:
            print(f"{pad}: y {old} -> {new} (to fit ship {ship} y={ship_y})")


if __name__ == "__main__":
    main()
