"""
build_outpost_pieces.py — derive mcpirates' outpost-extending NBTs from upstream sources.

Outputs two structure templates into src/main/resources/data/mcpirates/structure/:

1. base_plate_with_airship.nbt — clone of vanilla minecraft:pillager_outpost/base_plate
   with its east-facing feature_plates jigsaw at (15, 0, 8) repointed to mcpirates:airships
   (target mcpirates:airship_keel). The west and south feature_plate jigsaws are unchanged
   so the outpost still grows tents / cages on those two sides.

2. airship_small.nbt — clone of the user's structure-block-saved airship with a west-facing
   jigsaw injected at (0, 0, 5). That jigsaw's name is mcpirates:airship_keel, matching the
   target our cloned base_plate looks for, so the worldgen jigsaw assembler attaches the ship
   in the same pass that places the watchtower (no bounding-box rejection, no cross-chunk
   write race).

Re-run any time you re-save the airship in dev MC, or want to refresh from upstream.
"""
from __future__ import annotations

import argparse
import gzip
import shutil
from pathlib import Path

from nbtlib import File, Compound, List, Int, Double, String

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "src" / "main" / "resources" / "data" / "mcpirates" / "structure"

VANILLA_BASE_PLATE_PATH = (
    "data/minecraft/structure/pillager_outpost/base_plate.nbt"
)
NEOFORGE_RESOURCE_JAR = (
    REPO_ROOT
    / "build"
    / "moddev"
    / "artifacts"
    / "neoforge-21.1.228-client-extra-aka-minecraft-resources.jar"
)


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


def find_jigsaw_state_indices(palette, orientation: str | None = None) -> list[int]:
    out = []
    for i, p in enumerate(palette):
        if str(p.get("Name", "")) != "minecraft:jigsaw":
            continue
        props = dict(p.get("Properties", {}))
        if orientation is None or str(props.get("orientation", "")) == orientation:
            out.append(i)
    return out


def extract_vanilla_base_plate(out_path: Path) -> None:
    """Pull vanilla base_plate.nbt out of the NeoForge resource jar (cached by gradle)."""
    import zipfile

    if not NEOFORGE_RESOURCE_JAR.exists():
        raise SystemExit(
            f"Vanilla resource jar not found at {NEOFORGE_RESOURCE_JAR}\n"
            "Run `./gradlew createMinecraftArtifacts` first."
        )
    with zipfile.ZipFile(NEOFORGE_RESOURCE_JAR) as zf:
        with zf.open(VANILLA_BASE_PLATE_PATH) as src, open(out_path, "wb") as dst:
            shutil.copyfileobj(src, dst)


def build_base_plate_with_airship() -> None:
    src_temp = REPO_ROOT / "build" / "tmp" / "vanilla_base_plate.nbt"
    src_temp.parent.mkdir(parents=True, exist_ok=True)
    extract_vanilla_base_plate(src_temp)

    nbt_file = File.parse(gzip.open(src_temp, "rb"))
    palette = nbt_file["palette"]

    east_jigsaw_states = set(find_jigsaw_state_indices(palette, "east_up"))
    if not east_jigsaw_states:
        raise SystemExit("No east_up jigsaw found in vanilla base_plate — vanilla schema may have changed.")

    repointed = 0
    for block in nbt_file["blocks"]:
        if int(block["state"]) not in east_jigsaw_states:
            continue
        nbt = block.get("nbt")
        if nbt is None:
            continue
        if str(nbt.get("name", "")) != "minecraft:plate_entry":
            continue
        # repoint to our airships pool while keeping orientation/position
        nbt["target"] = String("mcpirates:airship_keel")
        nbt["pool"] = String("mcpirates:airships")
        nbt["name"] = String("mcpirates:airship_anchor")
        # final_state stays whatever vanilla had (likely minecraft:cobblestone or air —
        # leave alone so the foundation looks natural after the jigsaw is consumed)
        repointed += 1

    if repointed == 0:
        raise SystemExit("Could not find the east-facing feature_plate jigsaw in vanilla base_plate.")
    if repointed > 1:
        print(f"NOTE: repointed {repointed} jigsaws (expected 1) — vanilla may now have more east jigsaws.")

    dst = OUT_DIR / "base_plate_with_airship.nbt"
    dst.parent.mkdir(parents=True, exist_ok=True)
    nbt_file.save(str(dst), gzipped=True)
    print(f"Wrote {dst.relative_to(REPO_ROOT)}  (repointed {repointed} east jigsaw(s))")


def build_airship(input_path: Path) -> None:
    """
    Build a 10x13x10 airship piece by:
      1. Wrapping the user's saved ship in air + a stone-brick pad at y=2.
      2. Shifting the ship body east by 5 blocks (so it's farther from the watchtower)
         and up by 3 blocks (so it sits cleanly on the pad above any terrain undulation).
      3. Adding a west-facing jigsaw block at (0, 0, cz) so the piece attaches to
         base_plate's east jigsaw.

    Layout (X=west→east, Y=down→up, Z=north→south):
      y=0..1      : explicit air, full 10x10 footprint  → clears sand at connection level
      y=2         : stone-brick pad, full 10x10        → visible landing platform
      y=3..12     : ship body, only at x=5..9          → 5 blocks of clearance west of ship
      jigsaw      : west-face at (0, 0, cz)            → attaches to base_plate east jigsaw
    """
    if not input_path.exists():
        raise SystemExit(
            f"Input airship NBT not found: {input_path}\n"
            "Either save a structure-block NBT in dev MC and pass --airship <path>, "
            "or keep the existing src/main/resources/.../airship_small.nbt and re-run."
        )

    src = File.parse(gzip.open(input_path, "rb"))
    src_size = list(src["size"])
    sx, sy, sz = int(src_size[0]), int(src_size[1]), int(src_size[2])

    # Output canvas dimensions.
    east_offset = 5    # ship body shifts east by this many blocks
    pad_y = 2          # pad sits this many blocks above connection level
    out_size_x = sx + east_offset
    out_size_y = sy + pad_y + 1
    out_size_z = sz
    cz = out_size_z // 2

    # Build a fresh palette: stone_bricks, air, jigsaw, then everything from the source ship.
    out_palette = List[Compound]()
    out_palette.append(Compound({"Name": String("minecraft:stone_bricks")}))
    out_palette.append(Compound({"Name": String("minecraft:air")}))
    out_palette.append(Compound({
        "Name": String("minecraft:jigsaw"),
        "Properties": Compound({"orientation": String("west_up")}),
    }))
    STONE_IDX, AIR_IDX, JIGSAW_IDX = 0, 1, 2

    # Append source palette and remember the index offset so we can remap source block states.
    src_palette = src["palette"]
    src_palette_offset = len(out_palette)
    src_jigsaw_states = set()
    for i, entry in enumerate(src_palette):
        out_palette.append(entry)
        if str(entry.get("Name", "")) == "minecraft:jigsaw":
            src_jigsaw_states.add(i)  # source-relative index

    out_blocks = List[Compound]()

    def add_block(idx: int, x: int, y: int, z: int, nbt: Compound | None = None) -> None:
        entry = Compound({
            "state": Int(idx),
            "pos": List[Int]([Int(x), Int(y), Int(z)]),
        })
        if nbt is not None:
            entry["nbt"] = nbt
        out_blocks.append(entry)

    # y=0..1: air across the full footprint
    for y in range(pad_y):
        for x in range(out_size_x):
            for z in range(out_size_z):
                add_block(AIR_IDX, x, y, z)

    # y=pad_y: stone-brick pad
    for x in range(out_size_x):
        for z in range(out_size_z):
            add_block(STONE_IDX, x, pad_y, z)

    # y=pad_y+1 .. : ship body (skip jigsaw blocks from source — we add our own connector)
    for b in src["blocks"]:
        s = int(b["state"])
        if s in src_jigsaw_states:
            continue
        sp = list(b["pos"])
        bx, by, bz = int(sp[0]), int(sp[1]), int(sp[2])
        out_x = bx + east_offset
        out_y = by + pad_y + 1
        out_z = bz
        # Carry over per-block NBT (signs, banners, chests etc.) untouched.
        per_block_nbt = b.get("nbt")
        add_block(s + src_palette_offset, out_x, out_y, out_z, per_block_nbt)

    # Connector jigsaw at (0, 0, cz) — replaces the air block we placed there above.
    # The placement order means later entries are visited last but vanilla MC honors the
    # last-written state per cell; Mojang's StructureTemplate.placeInWorld iterates the list
    # in order and lets later entries overwrite, so this is fine.
    add_block(JIGSAW_IDX, 0, 0, cz, Compound({
        "name": String("mcpirates:airship_keel"),
        "target": String("mcpirates:airship_anchor"),
        "pool": String("minecraft:empty"),
        "final_state": String("minecraft:air"),
        "joint": String("rollable"),
    }))

    # Carry over entities from the source (honey-glue, super-glue, hangings, etc.) and
    # translate them by the same (east_offset, pad_y+1, 0) shift we applied to blocks.
    # Vanilla's StructureTemplate uses the top-level `blockPos` + `pos` for placement and
    # rewrites the entity's nbt.Pos / nbt.UUID, so we only touch those two fields.
    # HoneyGlueEntity stores its bounds as From/To relative to Pos, so they need no fixup.
    out_entities = List[Compound]()
    src_entities = src.get("entities", List[Compound]())
    for ent in src_entities:
        new_ent = Compound(dict(ent))  # shallow copy of the top-level fields
        bp = list(ent["blockPos"])
        new_ent["blockPos"] = List[Int]([
            Int(int(bp[0]) + east_offset),
            Int(int(bp[1]) + pad_y + 1),
            Int(int(bp[2])),
        ])
        ep = list(ent["pos"])
        new_ent["pos"] = List[Double]([
            Double(float(ep[0]) + east_offset),
            Double(float(ep[1]) + pad_y + 1),
            Double(float(ep[2])),
        ])
        out_entities.append(new_ent)

    out_nbt = Compound({
        "DataVersion": src.get("DataVersion", Int(3953)),
        "size": List[Int]([Int(out_size_x), Int(out_size_y), Int(out_size_z)]),
        "palette": out_palette,
        "blocks": out_blocks,
        "entities": out_entities,
    })
    out_file = File(out_nbt)

    dst = OUT_DIR / "airship_small.nbt"
    out_file.save(str(dst), gzipped=True)
    print(f"Wrote {dst.relative_to(REPO_ROOT)}  "
          f"(size {out_size_x}x{out_size_y}x{out_size_z}, "
          f"ship body at x={east_offset}..{east_offset + sx - 1}, "
          f"pad y={pad_y}, west-face jigsaw at (0, 0, {cz}), "
          f"{len(out_entities)} entit{'y' if len(out_entities) == 1 else 'ies'} carried over)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--airship",
        default=str(OUT_DIR / "airship_small.nbt"),
        help=("Path to the airship NBT to use as input. Defaults to the existing committed "
              "src/main/resources/.../airship_small.nbt — passing the dev save path "
              "(runs/client/saves/<world>/generated/.../first.nbt) refreshes from a "
              "freshly saved structure-block."),
    )
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    build_base_plate_with_airship()
    build_airship(Path(args.airship))


if __name__ == "__main__":
    main()
