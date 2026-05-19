"""
build_sheriff_test_arena.py — emit a 7x1x7 stone-floor NBT used as the @GameTest
template for SheriffMenuTests. Gives the test a flat platform to spawn a villager
on; no ship anchors, no entities, no side effects with mcpirates code paths.

Output:
    src/main/resources/data/mcpirates/structure/sheriff_test_arena.nbt
"""
from __future__ import annotations

from pathlib import Path

from nbtlib import File, Compound, Int, String, List, Double

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT = REPO_ROOT / "src" / "main" / "resources" / "data" / "mcpirates" / "structure" / "sheriff_test_arena.nbt"

W, H, D = 7, 1, 7
DATA_VERSION = 3955  # MC 1.21.1


def main() -> None:
    blocks = List[Compound]()
    for x in range(W):
        for z in range(D):
            blocks.append(Compound({
                "pos": List[Int]([Int(x), Int(0), Int(z)]),
                "state": Int(0),
            }))

    palette = List[Compound]([Compound({"Name": String("minecraft:stone")})])

    root = Compound({
        "DataVersion": Int(DATA_VERSION),
        "size": List[Int]([Int(W), Int(H), Int(D)]),
        "blocks": blocks,
        "palette": palette,
        "entities": List[Compound](),
    })

    nbt = File(root, gzipped=True)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    nbt.save(str(OUT), gzipped=True)
    print(f"Wrote {OUT.relative_to(REPO_ROOT)}  ({W}x{H}x{D} stone)")


if __name__ == "__main__":
    main()
