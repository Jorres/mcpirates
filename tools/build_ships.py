"""
build_ships.py — packages ship + pad source NBTs into the final structure resources.

Inputs (all required):
    tools/sources/<name>.nbt       — bare hull. Must contain exactly one down-facing
                                     jigsaw block (the keel connector) at the cell where
                                     the ship attaches to its pad. May contain trailing
                                     air rows from oversized structure-block save bounds
                                     — the script trims them automatically.
    tools/sources/<name>_pad.nbt   — pad design. Must contain exactly one up-facing
                                     jigsaw block (the ship connector). Outpost-attached
                                     pads must additionally contain one west-facing
                                     jigsaw (the outpost connector).

Outputs (regenerated each run):
    src/main/resources/data/mcpirates/structure/<name>.nbt
    src/main/resources/data/mcpirates/structure/<name>_pad.nbt
    src/main/resources/data/mcpirates/structure/base_plate_with_airship.nbt
    src/main/resources/data/mcpirates/structure/base_plate_with_<ship>.nbt

Pipeline:
    1. Read source. Trim trailing all-air rows (ships only) so the canopy-clearance
       buffer doesn't compound oversize.
    2. Add air_above buffer above the hull (ships only).
    3. Locate jigsaws in source by orientation prefix (down_ / up_ / west_) — positions
       come from the user's design. The script never invents jigsaw positions.
    4. Normalise the jigsaw block-NBT: name/target/pool/final_state/joint are overwritten
       to the values this mod's worldgen expects.
    5. For ships: stamp the ship_anchor block at anchor_src_pos.
    6. Write the result.

Jigsaw geometry recap (magnets, not overlap):
    The jigsaw assembler places piece B such that B's matching jigsaw lands at piece-A
    jigsaw position + 1 in A's facing direction. So pad's up-jigsaw and ship's down-
    jigsaw end up on ADJACENT cells (one block apart in Y), not the same cell. Author
    pad jigsaws at the y where the ship's down-jigsaw cell should sit one above; for
    "ship's hull-bottom row flush with pad-top layer", put pad's up-jigsaw at pad y=0
    so ship's keel cell lands at pad y=1 (= pad top layer cell).
"""
from __future__ import annotations

import argparse
import shutil
import zipfile
from pathlib import Path

from nbtlib import Compound, File, Int, List, String

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCES_DIR = REPO_ROOT / "tools" / "sources"
OUT_DIR = REPO_ROOT / "src" / "main" / "resources" / "data" / "mcpirates" / "structure"
NEOFORGE_RESOURCE_JAR = (
    REPO_ROOT / "build" / "moddev" / "artifacts"
    / "neoforge-21.1.228-client-extra-aka-minecraft-resources.jar"
)
VANILLA_BASE_PLATE_PATH = "data/minecraft/structure/pillager_outpost/base_plate.nbt"

KEEL_JIGSAW_NAME = "mcpirates:airship_keel"
LANDING_PAD_JIGSAW_NAME = "mcpirates:landing_pad_top"
AIR_BLOCK = "minecraft:air"
PAD_BLOCK = "minecraft:stone_bricks"
JIGSAW_BLOCK = "minecraft:jigsaw"
ANCHOR_BLOCK = "mcpirates:ship_anchor"

# ─────────────────────────────────────────────────────────────────────────────
# Per-ship configuration
# ─────────────────────────────────────────────────────────────────────────────
#
# Keys:
#   anchor_src_pos        Where the ship_anchor block goes in the source NBT.
#                         Must equal primary_lever_src_pos − AirshipKind.anchorToLeverDelta().
#   ship_keel_final_state Block the down-facing keel jigsaw becomes after JigsawReplacement-
#                         Processor runs. Use stone_bricks for outpost-attached ships
#                         (cell coincides with the pad's top layer at worldgen time) and
#                         air for hovering ships (cell sits in mid-air above the pad).
#   air_above             Air buffer above the hull, stamped over tree canopies.
#   outpost_attached      True → the pad source must include a west-facing jigsaw; the
#                         pad goes into the mcpirates:airship_pads pool. False → the pad
#                         only has an up-facing jigsaw and is placed standalone (galleon
#                         spawner / pirate_galleon structure).
# ─────────────────────────────────────────────────────────────────────────────
# Appendage configuration
# ─────────────────────────────────────────────────────────────────────────────
#
# Appendages are small pad-attached sub-pieces (ammo crates, banners, dock stairs)
# referenced from a pad jigsaw and a pool. They are authored entirely in MC: the user
# saves the appendage volume through a structure block (writes to tools/sources/<name>.nbt),
# and the script just copies the source verbatim into the resources structure folder.
# Jigsaw NBT inside the source must already be correct — the script does not normalise
# appendage jigsaws (unlike ship/pad jigsaws). Pool JSON wiring lives separately under
# data/mcpirates/worldgen/template_pool/<name>.json — one element + fallback=empty for
# "always appears" semantics.
APPENDAGES: list[str] = [
    "airship_small_ammo_box",
    "crossbow_board_ammo_box",
    "galleon_tent",
]


SHIPS: dict[str, dict] = {
    "airship_small": {
        "anchor_src_pos": (3, 3, 5),
        "ship_keel_final_state": "minecraft:stone_bricks",
        "air_above": 16,
        "outpost_attached": True,
    },
    "crossbow_board": {
        "anchor_src_pos": (2, 3, 8),
        "ship_keel_final_state": "minecraft:stone_bricks",
        "air_above": 16,
        "outpost_attached": True,
    },
    "galleon": {
        "anchor_src_pos": (3, 9, 13),
        "ship_keel_final_state": "minecraft:air",
        "air_above": 16,
        "outpost_attached": False,
    },
}


# ─────────────────────────────────────────────────────────────────────────────
# NBT helpers
# ─────────────────────────────────────────────────────────────────────────────


def read_nbt(path: Path) -> File:
    return File.load(str(path), gzipped=True)


def write_nbt(nbt: File, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    nbt.save(str(path), gzipped=True)


def palette_idx(palette: list, name: str, props: dict | None = None) -> int:
    """Find-or-append. Mutates the palette list in place."""
    props_str = {k: str(v) for k, v in (props or {}).items()}
    for i, entry in enumerate(palette):
        if str(entry.get("Name", "")) != name:
            continue
        existing = {k: str(v) for k, v in dict(entry.get("Properties", {})).items()}
        if existing == props_str:
            return i
    new_entry = Compound({"Name": String(name)})
    if props:
        new_entry["Properties"] = Compound({k: String(v) for k, v in props_str.items()})
    palette.append(new_entry)
    return len(palette) - 1


def make_block(state: int, x: int, y: int, z: int, block_nbt: Compound | None = None) -> Compound:
    entry = Compound({"state": Int(state), "pos": List[Int]([Int(x), Int(y), Int(z)])})
    if block_nbt is not None:
        entry["nbt"] = block_nbt
    return entry


def ship_anchor_nbt(kind_name: str) -> Compound:
    """Block-NBT for MCPShipAnchorBlockEntity. The {@code kind} field is baked at build
    time; AirshipKinds.byName resolves it at trigger time."""
    return Compound({
        "id": String(ANCHOR_BLOCK),
        "kind": String(kind_name),
    })


def keel_jigsaw_nbt(final_state: str) -> Compound:
    """Down-facing keel jigsaw on the SHIP. Incoming socket for the pad's up-facing
    landing_pad_top. final_state replaces the jigsaw cell post-worldgen — stone_bricks
    for outpost-attached ships (cell coincides with pad top layer), air for galleon."""
    return Compound({k: String(v) for k, v in {
        "name": KEEL_JIGSAW_NAME,
        "target": LANDING_PAD_JIGSAW_NAME,
        "pool": "minecraft:empty",
        "final_state": final_state,
        "joint": "rollable",
    }.items()})


def pad_up_jigsaw_nbt(ship_pool: str) -> Compound:
    """Up-facing jigsaw on the PAD. Outgoing socket targeting the per-ship pool so each
    pad always pairs with its specific ship; ship-vs-ship mixing happens in the
    airship_pads pool, not the airships pool."""
    return Compound({k: String(v) for k, v in {
        "name": LANDING_PAD_JIGSAW_NAME,
        "target": KEEL_JIGSAW_NAME,
        "pool": ship_pool,
        "final_state": "minecraft:air",
        "joint": "rollable",
    }.items()})


def pad_west_jigsaw_nbt() -> Compound:
    """West-facing jigsaw on outpost-attached PADs. Incoming socket from the outpost's
    east-facing connector. After matching, this jigsaw is consumed; final_state keeps
    the pad surface continuous if anything sees the cell post-replacement."""
    return Compound({k: String(v) for k, v in {
        "name": KEEL_JIGSAW_NAME,
        "target": LANDING_PAD_JIGSAW_NAME,
        "pool": "minecraft:empty",
        "final_state": PAD_BLOCK,
        "joint": "rollable",
    }.items()})


def find_jigsaw_by_orientation(palette: list, blocks, prefix: str):
    """Find the first jigsaw block whose orientation starts with {@code prefix} (e.g.
    "down_", "up_", "west_"). Returns (block_index_in_blocks, position_tuple) or
    (None, None). The exact orientation suffix is preserved by callers — only the facing
    prefix identifies the jigsaw's role.
    """
    matching_states = set()
    for i, p in enumerate(palette):
        if str(p.get("Name", "")) != JIGSAW_BLOCK:
            continue
        orientation = str(dict(p.get("Properties", {})).get("orientation", ""))
        if orientation.startswith(prefix):
            matching_states.add(i)
    if not matching_states:
        return None, None
    for j, b in enumerate(blocks):
        if int(b["state"]) in matching_states:
            return j, (int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2]))
    return None, None


def effective_hull_height(src: File) -> int:
    """Topmost non-air Y + 1. Trims trailing all-air rows in user saves where the
    structure-block bounds were taller than the actual ship."""
    air_idx = next((i for i, p in enumerate(src["palette"])
                    if str(p.get("Name", "")) == AIR_BLOCK), None)
    top = -1
    for b in src["blocks"]:
        if air_idx is not None and int(b["state"]) == air_idx:
            continue
        top = max(top, int(b["pos"][1]))
    return top + 1


# ─────────────────────────────────────────────────────────────────────────────
# Build operations
# ─────────────────────────────────────────────────────────────────────────────


def build_ship(name: str, cfg: dict) -> None:
    """Bare hull NBT: source (trailing air trimmed) + air buffer above + anchor block.
    The down-facing keel jigsaw must already be in the source — script preserves its
    position and only rewrites its NBT to the canonical name/target/pool/final_state."""
    src = read_nbt(SOURCES_DIR / f"{name}.nbt")
    palette = list(src["palette"])
    blocks = list(src["blocks"])

    sx, declared_sy, sz = (int(x) for x in src["size"])
    sy = effective_hull_height(src)
    if sy < declared_sy:
        print(f"  [{name}] trimmed {declared_sy - sy} trailing air row(s) from source")
    air_above = cfg.get("air_above", 16)
    out_sx, out_sy, out_sz = sx, sy + air_above, sz

    _, keel_pos = find_jigsaw_by_orientation(palette, blocks, "down_")
    if keel_pos is None:
        raise SystemExit(
            f"{name}: source NBT has no down-facing jigsaw. Place a 'minecraft:jigsaw' "
            f"block oriented down_* at the desired keel position and re-save."
        )

    anchor_pos = cfg["anchor_src_pos"]
    final_state = cfg["ship_keel_final_state"]

    # Drop existing blocks at keel/anchor positions; we overwrite them.
    blocks = [b for b in blocks
              if (int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])) not in
                 (tuple(keel_pos), tuple(anchor_pos))]

    air_idx = palette_idx(palette, AIR_BLOCK)
    jigsaw_idx = palette_idx(palette, JIGSAW_BLOCK, {"orientation": "down_south"})
    anchor_idx = palette_idx(palette, ANCHOR_BLOCK)

    src_blocks = {(int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])): b
                  for b in blocks}

    out_blocks: list[Compound] = []
    for y in range(out_sy):
        for x in range(out_sx):
            for z in range(out_sz):
                if (x, y, z) == tuple(keel_pos):
                    out_blocks.append(make_block(jigsaw_idx, x, y, z,
                                                 keel_jigsaw_nbt(final_state)))
                elif (x, y, z) == tuple(anchor_pos):
                    out_blocks.append(make_block(anchor_idx, x, y, z,
                                                 ship_anchor_nbt(name)))
                elif y < sy and (x, y, z) in src_blocks:
                    sb = src_blocks[(x, y, z)]
                    out_blocks.append(make_block(int(sb["state"]), x, y, z, sb.get("nbt")))
                else:
                    out_blocks.append(make_block(air_idx, x, y, z))

    write_nbt(File(Compound({
        "size": List[Int]([Int(out_sx), Int(out_sy), Int(out_sz)]),
        "palette": List[Compound](palette),
        "blocks": List[Compound](out_blocks),
        "entities": src.get("entities", List[Compound]()),
        "DataVersion": src.get("DataVersion", Int(3955)),
    })), OUT_DIR / f"{name}.nbt")

    print(f"  [{name}] wrote bare ship {out_sx}x{out_sy}x{out_sz} "
          f"(hull y=0..{sy-1}, buffer y={sy}..{out_sy-1}, "
          f"keel at {tuple(keel_pos)}, anchor at {tuple(anchor_pos)})")


def build_pad(name: str, cfg: dict) -> None:
    """Pad NBT: source verbatim + normalised jigsaw NBT. The up-facing jigsaw is
    required. Outpost-attached pads additionally require a west-facing jigsaw."""
    src_path = SOURCES_DIR / f"{name}_pad.nbt"
    if not src_path.exists():
        raise SystemExit(
            f"{name}_pad: source NBT missing at {src_path}. All pads must be authored "
            f"by hand — there is no programmatic fallback."
        )
    src = read_nbt(src_path)
    sx, sy, sz = (int(v) for v in src["size"])
    palette = list(src["palette"])
    blocks = list(src["blocks"])

    _, up_pos = find_jigsaw_by_orientation(palette, blocks, "up_")
    if up_pos is None:
        raise SystemExit(
            f"{name}_pad: source has no up-facing jigsaw. Place a 'minecraft:jigsaw' "
            f"block oriented up_* at the cell where the ship's keel should attach."
        )

    outpost_attached = cfg["outpost_attached"]
    if outpost_attached:
        _, west_pos = find_jigsaw_by_orientation(palette, blocks, "west_")
        if west_pos is None:
            print(f"  [{name}_pad] WARN: outpost_attached=True but no west-facing "
                  f"jigsaw in source — this pad won't chain from outposts until you "
                  f"add one. Ship-pad attachment still works.")
    else:
        west_pos = None

    # Drop blocks at jigsaw positions — we overwrite with normalised NBT.
    overwrite = {tuple(up_pos)}
    if west_pos is not None:
        overwrite.add(tuple(west_pos))
    blocks = [b for b in blocks
              if (int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])) not in overwrite]

    # Preserve the original jigsaw blockstates (so we don't lose the user's orientation
    # rotation suffix). Reuse palette indexes already in the source.
    up_state = None
    west_state = None
    for b in src["blocks"]:
        pos = (int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2]))
        if pos == tuple(up_pos):
            up_state = int(b["state"])
        elif west_pos is not None and pos == tuple(west_pos):
            west_state = int(b["state"])

    src_blocks = {(int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])): b
                  for b in blocks}

    ship_pool = f"mcpirates:airships_{name}_only"

    out_blocks: list[Compound] = []
    for y in range(sy):
        for x in range(sx):
            for z in range(sz):
                if (x, y, z) == tuple(up_pos):
                    out_blocks.append(make_block(up_state, x, y, z,
                                                 pad_up_jigsaw_nbt(ship_pool)))
                elif west_pos is not None and (x, y, z) == tuple(west_pos):
                    out_blocks.append(make_block(west_state, x, y, z,
                                                 pad_west_jigsaw_nbt()))
                elif (x, y, z) in src_blocks:
                    sb = src_blocks[(x, y, z)]
                    out_blocks.append(make_block(int(sb["state"]), x, y, z, sb.get("nbt")))
                # else: cell absent from source → skip (will be air on placement default)

    write_nbt(File(Compound({
        "size": List[Int]([Int(sx), Int(sy), Int(sz)]),
        "palette": List[Compound](palette),
        "blocks": List[Compound](out_blocks),
        "entities": src.get("entities", List[Compound]()),
        "DataVersion": src.get("DataVersion", Int(3955)),
    })), OUT_DIR / f"{name}_pad.nbt")

    style = "outpost-attached" if outpost_attached else "standalone"
    info = f"up jigsaw at {tuple(up_pos)} -> {ship_pool}"
    if west_pos is not None:
        info += f", west jigsaw at {tuple(west_pos)}"
    print(f"  [{name}_pad] wrote {style} pad {sx}x{sy}x{sz} ({info})")


def build_appendage(name: str) -> None:
    """Copy an appendage source NBT into the resources structure dir. No transformation
    — jigsaw NBT must already be correct in the source (set via /data merge block in
    MC before saving, or edited directly)."""
    src = SOURCES_DIR / f"{name}.nbt"
    if not src.exists():
        raise SystemExit(f"{name}: appendage source NBT missing at {src}")
    dst = OUT_DIR / f"{name}.nbt"
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, dst)
    print(f"  [{name}] copied source -> structure/{name}.nbt")


def build_base_plate_with_airship(pool: str, out_name: str) -> None:
    """Clone vanilla pillager_outpost/base_plate, repoint its east-facing feature_plates
    jigsaw at (15, 0, 8) at our pool. Called once for the default airship_pads pool and
    once per outpost-attached ship for the single-pad variants `/mcpirates outpost spawn
    <ship>` uses."""
    if not NEOFORGE_RESOURCE_JAR.exists():
        raise SystemExit(f"Vanilla resource jar not found: {NEOFORGE_RESOURCE_JAR}\n"
                         "Run `./gradlew createMinecraftArtifacts` first.")

    tmp = REPO_ROOT / "build" / "tmp" / "vanilla_base_plate.nbt"
    if not tmp.exists():
        tmp.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(NEOFORGE_RESOURCE_JAR) as zf, \
             zf.open(VANILLA_BASE_PLATE_PATH) as src, open(tmp, "wb") as dst:
            shutil.copyfileobj(src, dst)

    nbt = read_nbt(tmp)
    palette = nbt["palette"]
    east_jigsaw_states = {
        i for i, p in enumerate(palette)
        if str(p.get("Name", "")) == JIGSAW_BLOCK
        and str(dict(p.get("Properties", {})).get("orientation", "")) == "east_up"
    }
    if not east_jigsaw_states:
        raise SystemExit("No east_up jigsaw in vanilla base_plate — vanilla schema may have changed.")

    repointed = 0
    for block in nbt["blocks"]:
        if int(block["state"]) not in east_jigsaw_states:
            continue
        block_nbt = block.get("nbt")
        if block_nbt is None or str(block_nbt.get("name", "")) != "minecraft:plate_entry":
            continue
        block_nbt["target"] = String(KEEL_JIGSAW_NAME)  # matches pad's west-facing keel
        block_nbt["pool"] = String(pool)
        block_nbt["name"] = String("mcpirates:airship_anchor")
        repointed += 1

    if repointed == 0:
        raise SystemExit("Could not find vanilla east plate_entry jigsaw to repoint.")
    write_nbt(nbt, OUT_DIR / f"{out_name}.nbt")
    print(f"  [{out_name}] repointed {repointed} east jigsaw(s) -> {pool}")


# ─────────────────────────────────────────────────────────────────────────────
# Driver
# ─────────────────────────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ship", choices=list(SHIPS),
                        help="Rebuild only this ship + its pad. Default: all ships.")
    parser.add_argument("--skip-base-plate", action="store_true",
                        help="Skip rebuilding base_plate_with_airship.nbt and variants.")
    args = parser.parse_args()

    ships = [args.ship] if args.ship else list(SHIPS)
    for name in ships:
        cfg = SHIPS[name]
        print(f"\nbuilding {name}…")
        build_ship(name, cfg)
        build_pad(name, cfg)

    if not args.ship:
        print("\nbuilding appendages…")
        for app in APPENDAGES:
            build_appendage(app)

    outpost_ships = [n for n in ships if SHIPS[n]["outpost_attached"]]
    if outpost_ships and not args.skip_base_plate:
        print("\nbuilding outpost base plate variants…")
        build_base_plate_with_airship("mcpirates:airship_pads", "base_plate_with_airship")
        for n in outpost_ships:
            build_base_plate_with_airship(f"mcpirates:airship_pads_{n}_only",
                                          f"base_plate_with_{n}")

    print("\ndone.")


if __name__ == "__main__":
    main()
