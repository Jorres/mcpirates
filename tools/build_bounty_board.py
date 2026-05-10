"""
build_bounty_board.py — emit a small village structure piece for the pirate bounty board.

Output:
    src/main/resources/data/mcpirates/structure/village/common/pirate_bounty_board.nbt

The piece is a 5x6x6 oak-framed gazebo modeled after Bountiful's bounty_gazebo.nbt:
    y=0     : 5x5 oak-plank floor                                  (decorative footprint)
    y=1..3  : 4 spruce-fence corner posts; otherwise open-air walls (the "gazebo")
    y=2     : minecraft:cobblestone trophy block in the dead center (PoC placeholder
              — to be replaced with a real pirate-bounty quest block later)
    y=3     : 2 hanging lanterns at the north-mid ends (x=0 z=2 and x=4 z=2)
    y=4     : oak-slab roof ring + oak-plank ceiling (3x3 in center)
    jigsaw  : single south-up jigsaw at (2, 1, 5), name=target=minecraft:building_entrance,
              pool=minecraft:empty, final_state=minecraft:structure_void

That jigsaw lets the piece attach to vanilla / CTOV village houses that expose a
`minecraft:building_entrance` connector, which is the same jigsaw-style Bountiful's
gazebo uses. We deliberately don't depend on Bountiful's NBT — this builder is
self-contained, so mcpirates has no soft- or hard-dep on bountiful.

The NBT is intentionally hand-built (rather than copied from the user's local
bountiful jar) so the output is reproducible from this script alone.
"""

from __future__ import annotations

from pathlib import Path

from nbtlib import File, Compound, List, Int, String

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_PATH = (
    REPO_ROOT
    / "src" / "main" / "resources" / "data" / "mcpirates"
    / "structure" / "village" / "common" / "pirate_bounty_board.nbt"
)

SIZE_X, SIZE_Y, SIZE_Z = 5, 6, 6


def fence(north="false", south="false", east="false", west="false") -> Compound:
    return Compound({
        "Name": String("minecraft:spruce_fence"),
        "Properties": Compound({
            "north": String(north), "south": String(south),
            "east":  String(east),  "west":  String(west),
            "waterlogged": String("false"),
        }),
    })


def main() -> None:
    palette = List[Compound]([
        Compound({"Name": String("minecraft:oak_planks")}),                                        # 0
        Compound({"Name": String("minecraft:air")}),                                                # 1
        fence(south="true", east="true"),                                                           # 2  SE corner-bottom
        fence(north="true", east="true"),                                                           # 3  NE corner
        fence(south="true", west="true"),                                                           # 4  SW corner-bottom
        fence(north="true", west="true"),                                                           # 5  NW corner
        Compound({"Name": String("minecraft:spruce_fence"),                                          # 6  plain post (mid-Y stack)
                  "Properties": Compound({"north": String("false"), "south": String("false"),
                                          "east": String("false"), "west": String("false"),
                                          "waterlogged": String("false")})}),
        Compound({"Name": String("minecraft:lantern"),                                              # 7
                  "Properties": Compound({"hanging": String("true"), "waterlogged": String("false")})}),
        Compound({"Name": String("minecraft:oak_slab"),                                             # 8
                  "Properties": Compound({"type": String("bottom"), "waterlogged": String("false")})}),
        Compound({"Name": String("minecraft:jigsaw"),                                               # 9
                  "Properties": Compound({"orientation": String("south_up")})}),
        Compound({"Name": String("minecraft:cobblestone")}),                                        # 10 trophy block (placeholder)
    ])
    OAK_PLANKS, AIR = 0, 1
    FENCE_SE_BTM, FENCE_NE, FENCE_SW_BTM, FENCE_NW, FENCE_POST = 2, 3, 4, 5, 6
    LANTERN, OAK_SLAB, JIGSAW, COBBLE = 7, 8, 9, 10

    blocks: list[Compound] = []

    def put(state: int, x: int, y: int, z: int, nbt: Compound | None = None) -> None:
        e = Compound({"state": Int(state), "pos": List[Int]([Int(x), Int(y), Int(z)])})
        if nbt is not None:
            e["nbt"] = nbt
        blocks.append(e)

    # y=0: 5x5 oak-plank floor
    for x in range(SIZE_X):
        for z in range(SIZE_X):
            put(OAK_PLANKS, x, 0, z)

    # y=1: corner posts (with the matching adjacencies for the bottom of each post),
    #      everything else inside is air. The jigsaw replaces the south-mid air block.
    fence_states_y1 = {(0, 0): FENCE_SE_BTM, (0, 4): FENCE_NE,
                       (4, 0): FENCE_SW_BTM, (4, 4): FENCE_NW}
    # NOTE on coordinate convention used here:
    #   In bountiful's gazebo the "north_up" jigsaw points along +Z (south).
    #   We mirror its layout exactly: jigsaw at (2, 1, 5) facing south_up, so the
    #   piece attaches to a building's north wall.
    # Floor of the gazebo (y=1): corner posts + air everywhere else inside the 5x5.
    for x in range(SIZE_X):
        for z in range(SIZE_X):
            if (x, z) in fence_states_y1:
                put(fence_states_y1[(x, z)], x, 1, z)
            else:
                put(AIR, x, 1, z)
    # South-edge row (z=5) is also air at y=1, except the jigsaw at (2,1,5).
    for x in range(SIZE_X):
        if x == 2:
            put(JIGSAW, 2, 1, 5, Compound({
                "joint": String("rollable"),
                "name": String("minecraft:building_entrance"),
                "pool": String("minecraft:empty"),
                "final_state": String("minecraft:structure_void"),
                "id": String("minecraft:jigsaw"),
                "target": String("minecraft:building_entrance"),
            }))
        else:
            put(AIR, x, 1, 5)

    # y=2: cobblestone trophy at center, four corner posts, lanterns/walls handled at y=3.
    for x in range(SIZE_X):
        for z in range(SIZE_Z):
            if (x, z) in fence_states_y1:
                put(FENCE_POST, x, 2, z)
            elif x == 2 and z == 2:
                put(COBBLE, 2, 2, 2)
            else:
                put(AIR, x, 2, z)

    # y=3: corner posts + 2 hanging lanterns at the mid east/west walls; rest air.
    for x in range(SIZE_X):
        for z in range(SIZE_Z):
            if (x, z) in fence_states_y1:
                put(FENCE_POST, x, 3, z)
            elif (x, z) in {(0, 2), (4, 2)}:
                put(LANTERN, x, 3, z)
            else:
                put(AIR, x, 3, z)

    # y=4: slab roof ring + 3x3 inner oak-plank ceiling.
    for x in range(SIZE_X):
        for z in range(SIZE_Z):
            if z == 5:
                put(AIR, x, 4, z)
            elif 1 <= x <= 3 and 1 <= z <= 3:
                put(OAK_PLANKS, x, 4, z)
            else:
                put(OAK_SLAB, x, 4, z)

    # y=5: a single oak slab on top of the center roof piece, otherwise air.
    for x in range(SIZE_X):
        for z in range(SIZE_Z):
            if x == 2 and z == 2:
                put(OAK_SLAB, 2, 5, 2)
            else:
                put(AIR, x, 5, z)

    nbt_root = Compound({
        "size": List[Int]([Int(SIZE_X), Int(SIZE_Y), Int(SIZE_Z)]),
        "palette": palette,
        "blocks": List[Compound](blocks),
        "entities": List[Compound](),
        "DataVersion": Int(3955),  # 1.21.1
    })

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    f = File(nbt_root)
    f.gzipped = True
    f.save(str(OUT_PATH))
    print(f"Wrote {OUT_PATH.relative_to(REPO_ROOT)}  ({len(blocks)} blocks, {len(palette)} palette entries)")


if __name__ == "__main__":
    main()
