"""
build_ships.py — packages ship + pad source NBTs into the final structure resources.

The build script is now a thin pass-through: jigsaw POSITIONS and jigsaw NBT
(name/target/pool/final_state) come straight from the source NBTs the user authored
in-game. The script never moves, inserts, or rewrites jigsaws on ship/pad pieces — every
jigsaw in the output was placed by the user in the source, with the metadata the user
set. The only programmatic touches are:

    * Ships: trim trailing all-air rows, add an `air_above` buffer over the hull, and
             stamp the ship_anchor block at `anchor_src_pos`. The keel jigsaw is copied
             verbatim from the source.
    * Pads:  shift source content up by `pad_lift` rows (the lower rows become absent,
             so worldgen preserves the existing terrain block underneath the deck). No
             jigsaw is moved; if the source has the up-jigsaw at y=0, the lifted output
             has it at y=lift.
    * base_plate_with_airship*.nbt: vanilla pillager_outpost/base_plate is cloned and
             its east_up plate_entry jigsaw is re-targeted at our pool. This is the one
             place we mutate a jigsaw's NBT, because the source piece is vanilla and we
             don't author it ourselves.

Inputs (all required):
    tools/sources/<name>.nbt       — bare ship hull. Trailing all-air rows are trimmed.
    tools/sources/<name>_pad.nbt   — pad. Must include whatever jigsaw set the user wants
                                     (outpost-anchor on west face, ship-mount on top,
                                     appendage sockets on east face, etc.). The script
                                     does not validate; whatever jigsaws are in source
                                     end up in output.

Outputs (regenerated each run):
    src/main/resources/data/mcpirates/structure/<name>.nbt
    src/main/resources/data/mcpirates/structure/<name>_pad.nbt
    src/main/resources/data/mcpirates/structure/base_plate_with_airship.nbt
    src/main/resources/data/mcpirates/structure/base_plate_with_<ship>.nbt
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

# Jigsaw socket names live in the source NBTs the user authors — the build script does
# not reference any pad/ship socket name. The only socket name we still need here is the
# one base_plate (vanilla) must target on the pad's west face, since base_plate is the
# only piece we mutate programmatically (vanilla doesn't ship a version that points at
# our pool).
OUTPOST_ANCHOR_JIGSAW_NAME = "mcpirates:outpost_anchor"
AIR_BLOCK = "minecraft:air"
JIGSAW_BLOCK = "minecraft:jigsaw"
ANCHOR_BLOCK = "mcpirates:ship_anchor"

# ─────────────────────────────────────────────────────────────────────────────
# Per-ship configuration
# ─────────────────────────────────────────────────────────────────────────────
#
# Keys:
#   anchor_src_pos    Where the ship_anchor block goes in the source NBT.
#                     Must equal primary_lever_src_pos − AirshipKind.anchorToLeverDelta().
#   air_above         Air buffer above the hull, stamped over tree canopies.
#   outpost_attached  Routes the pad output. True → the airship_pads pool (chained from
#                     pillager outpost base_plate). False → standalone, placed via /place
#                     or /mcpirates galleon spawn at a Y the caller chooses.
#   pad_lift          Number of cells to shift pad content UP in the output NBT. The
#                     bottom `pad_lift` rows of the output are absent (no blocks), so
#                     placement preserves the existing terrain block. Used so the deck
#                     sits ON the grass instead of buried in it.
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
        "air_above": 16,
        "outpost_attached": True,
        # pad_lift=1: pad bottom row is absent in output → terrain preserved → deck sits
        # one cell above the grass block. pad_lift=0 buries the deck in the grass cell.
        "pad_lift": 1,
    },
    "crossbow_board": {
        "anchor_src_pos": (2, 3, 8),
        "air_above": 16,
        "outpost_attached": True,
        "pad_lift": 1,
    },
    "galleon": {
        "anchor_src_pos": (3, 9, 13),
        "air_above": 16,
        "outpost_attached": False,
        # Standalone-spawned via /mcpirates galleon spawn — caller picks world Y, so no
        # depth bias from base_plate.
        "pad_lift": 0,
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
    """Bare hull NBT: source hull copied verbatim (jigsaws and all), with two programmatic
    edits: (1) trim trailing all-air rows from the hull height, (2) add an `air_above`
    buffer over the hull so vegetation can't poke through. The ship_anchor block is also
    stamped at `anchor_src_pos` (overwriting whatever the source had there — anchor pos
    is part of the build contract because the runtime needs to find it deterministically).
    All other source cells — including the keel jigsaw and its block-NBT — pass through
    unchanged."""
    src = read_nbt(SOURCES_DIR / f"{name}.nbt")
    palette = list(src["palette"])

    sx, declared_sy, sz = (int(x) for x in src["size"])
    sy = effective_hull_height(src)
    if sy < declared_sy:
        print(f"  [{name}] trimmed {declared_sy - sy} trailing air row(s) from source")
    air_above = cfg.get("air_above", 16)
    out_sx, out_sy, out_sz = sx, sy + air_above, sz

    anchor_pos = cfg["anchor_src_pos"]
    air_idx = palette_idx(palette, AIR_BLOCK)
    anchor_idx = palette_idx(palette, ANCHOR_BLOCK)

    # Index source blocks; drop only the anchor cell (we re-emit it with the anchor BE).
    src_blocks = {(int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])): b
                  for b in src["blocks"]
                  if (int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])) != tuple(anchor_pos)}

    out_blocks: list[Compound] = []
    for y in range(out_sy):
        for x in range(out_sx):
            for z in range(out_sz):
                if (x, y, z) == tuple(anchor_pos):
                    out_blocks.append(make_block(anchor_idx, x, y, z, ship_anchor_nbt(name)))
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
          f"(hull y=0..{sy-1}, buffer y={sy}..{out_sy-1}, anchor at {tuple(anchor_pos)})")


def build_pad(name: str, cfg: dict) -> None:
    """Pad NBT: source copied verbatim with content shifted up by `pad_lift` rows. Every
    block (jigsaws included) keeps its source NBT and lands at world-space y = source_y
    + pad_lift. The bottom `pad_lift` rows of the output are absent from the blocks list
    so MC's structure placement preserves the underlying terrain block (dirt/grass) —
    that's what gets the deck sitting ON the grass instead of buried.

    Note that `pad_lift > 0` shifts EVERY jigsaw up by `pad_lift` rows in the output,
    including any chain-anchor jigsaw on the west face. For outpost-attached pads the
    base_plate_with_airship piece chains its east jigsaw to the pad at the world Y of
    the pad's bottom row (= pad output y=0 = preserved-terrain row), which means with
    pad_lift > 0 the chain anchor on the pad must NOT be at source y=0 — the source
    must place it at source y=pad_lift so it lands at output y=2*pad_lift... actually
    no — the matching happens at the FIRST jigsaw of the correct name MC finds in the
    placed piece. Source jigsaw positions are entirely up to the author."""
    src_path = SOURCES_DIR / f"{name}_pad.nbt"
    if not src_path.exists():
        raise SystemExit(f"{name}_pad: source NBT missing at {src_path}")
    src = read_nbt(src_path)
    sx, sy, sz = (int(v) for v in src["size"])
    palette = list(src["palette"])

    lift = cfg.get("pad_lift", 0)
    sy_out = sy + lift

    out_blocks: list[Compound] = []
    for b in src["blocks"]:
        sx_, sy_, sz_ = int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])
        out_blocks.append(make_block(int(b["state"]), sx_, sy_ + lift, sz_, b.get("nbt")))

    write_nbt(File(Compound({
        "size": List[Int]([Int(sx), Int(sy_out), Int(sz)]),
        "palette": List[Compound](palette),
        "blocks": List[Compound](out_blocks),
        "entities": src.get("entities", List[Compound]()),
        "DataVersion": src.get("DataVersion", Int(3955)),
    })), OUT_DIR / f"{name}_pad.nbt")

    style = "outpost-attached" if cfg["outpost_attached"] else "standalone"
    lift_info = f", lifted +{lift}" if lift else ""
    print(f"  [{name}_pad] wrote {style} pad {sx}x{sy_out}x{sz}{lift_info}")


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
        block_nbt["target"] = String(OUTPOST_ANCHOR_JIGSAW_NAME)  # matches pad-west name
        block_nbt["pool"] = String(pool)
        block_nbt["name"] = String("mcpirates:airship_anchor")
        # placement_priority biases MC's jigsaw chain order: higher = processed first.
        # Vanilla feature_plates/towers default to 0; bumping our pad east jigsaw to a
        # positive value makes the pad chain before feature_plates / fence borders, so
        # the pad's bbox is reserved first and other pieces fit around it. Without this
        # the chain order is RNG-driven and the pad sometimes gets rejected for bbox
        # conflict with a feature_plate that grabbed the area first. Symptom: outpost
        # spawns with watchtower but no pad/ship.
        block_nbt["placement_priority"] = Int(10)
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
