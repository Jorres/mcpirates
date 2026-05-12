"""
build_ships.py — single pipeline that builds every airship resource the mod ships.

Replaces the previous one-off scripts (import_airship.py, build_outpost_pieces.py,
build_galleon_pad.py, wrap_ship.py, unwrap_ship.py). Reads raw structure-block saves
from tools/sources/, injects the airship_keel jigsaw at the configured position +
orientation, and produces every derived NBT the mod needs:

    src/main/resources/data/mcpirates/structure/
        airship_small.nbt           # outpost-attached small ship + internal pad
        crossbow_board.nbt          # outpost-attached patrol ship + internal pad
        galleon.nbt                 # bare ship hull (no pad — attaches to galleon_pad)
        galleon_pad.nbt             # standalone parent piece with pad + air column
        base_plate_with_airship.nbt # outpost variant repointed to the airships pool

Usage:
    python tools/build_ships.py                # rebuild everything (with backup)
    python tools/build_ships.py --ship galleon # rebuild one ship + its pad
    python tools/build_ships.py --no-backup    # skip the auto-backup step

The per-ship config lives in SHIPS below. Two ship-types are supported:

    "internal_pad"  — wraps the raw source ship with a stone-brick pad layer + air
                      shroud and shifts the body away from the connector. Used for
                      outpost-attached ships where the airship_keel jigsaw must face
                      the outpost's east-facing feature_plates connector. The pad
                      sits inside the ship NBT; the outpost provides only its own
                      base plate at the connection point.

    "external_pad"  — leaves the ship NBT bare (just hull + a center-bottom airship_keel
                      jigsaw facing down). Generates a SEPARATE galleon_pad.nbt parent
                      piece with the matching upward-facing jigsaw. Used for ships that
                      stand alone in their own worldgen structure (the galleon).

If you add a new ship, add a SHIPS entry and re-run. The source NBT lives in
tools/sources/<name>.nbt — re-save the ship in dev MC, copy the structure-block
output there, then re-run this script.
"""
from __future__ import annotations

import argparse
import gzip
import shutil
import zipfile
from datetime import datetime
from pathlib import Path

from nbtlib import Compound, Double, File, Int, List, String

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCES_DIR = REPO_ROOT / "tools" / "sources"
BACKUPS_DIR = REPO_ROOT / "tools" / "backups"
OUT_DIR = REPO_ROOT / "src" / "main" / "resources" / "data" / "mcpirates" / "structure"
NEOFORGE_RESOURCE_JAR = (
    REPO_ROOT / "build" / "moddev" / "artifacts"
    / "neoforge-21.1.228-client-extra-aka-minecraft-resources.jar"
)
VANILLA_BASE_PLATE_PATH = "data/minecraft/structure/pillager_outpost/base_plate.nbt"

KEEL_JIGSAW_NAME = "mcpirates:airship_keel"
LANDING_PAD_JIGSAW_NAME = "mcpirates:landing_pad_top"
PAD_BLOCK = "minecraft:stone_bricks"
AIR_BLOCK = "minecraft:air"
JIGSAW_BLOCK = "minecraft:jigsaw"
ANCHOR_BLOCK = "mcpirates:ship_anchor"

# ─────────────────────────────────────────────────────────────────────────────
# Per-ship configuration
# ─────────────────────────────────────────────────────────────────────────────

SHIPS: dict[str, dict] = {
    # Outpost-attached: wraps with internal pad + west-facing keel jigsaw, matches the
    # outpost's east-facing feature_plates connector at base_plate (15, 0, 8).
    #
    # Pad sits at NBT y=0 so it lands AT the outpost's base plate level. The ship body
    # stays at NBT y=body_y_offset (= 3 by default), leaving a 2-block air gap between
    # pad and keel — the airship visually floats just above its docking surface, like a
    # moored ship hovering over a quay. Earlier build_outpost_pieces.py raised the pad
    # to y=2 with the ship right above; that put the pad floating 2 blocks above the
    # outpost which looked wrong.
    #
    # The keel jigsaw lives at (0, 0, cz) within the pad layer with
    # final_state=stone_bricks so its cell remains pad after JigsawReplacementProcessor
    # runs (no visible hole in the pad).
    "airship_small": {
        "internal_pad": True,
        "shift_east": 5,        # ship body shifted this many blocks east of jigsaw
        "body_y_offset": 3,     # ship body starts at this NBT y (air at y=1..body_y_offset-1)
        # Tall air buffer above the body — worldgen stamps NBT air over any vegetation
        # blocks in the same chunk, preventing tree canopies from clipping the envelope.
        # 16 covers most overworld trees; giant jungle / cross-chunk canopy from
        # neighbouring chunks can still bleed in.
        "air_above": 16,
        "jigsaw_orientation": "west_up",
        # Source-NBT position of the ship_anchor block. Must be (primary_lever_src_pos
        # − AirshipSmallKind.anchorToLeverDelta()) in NBT coords. Lever at source
        # (3, 3, 6), delta (0, 0, +1) → anchor at (3, 3, 5). Currently an air cell.
        "anchor_src_pos": (3, 3, 5),
    },
    "crossbow_board": {
        "internal_pad": True,
        "shift_east": 5,
        "body_y_offset": 3,
        "air_above": 16,
        "jigsaw_orientation": "west_up",
        # Left primary lever at source (2, 3, 9); anchor at (2, 3, 8).
        "anchor_src_pos": (2, 3, 8),
    },
    # Standalone with external parent pad: ship NBT is bare, with a center-bottom
    # down-facing jigsaw that attaches to galleon_pad's up-facing connector.
    "galleon": {
        "external_pad": True,
        # Pad parent piece dimensions and jigsaw position.
        "pad_size_x": 16,
        "pad_size_z": 32,
        "pad_jigsaw_y": 20,     # ship hovers at this height above the pad surface
        # Tall air buffer above the ship's highest hull block. Same tree-clearance
        # rationale as airship_small / crossbow_board's air_above — see those configs.
        "pad_buffer_above": 16,
        "jigsaw_orientation": "down_south",
        # Left primary throttle at source (3, 9, 14); anchor at (3, 9, 13).
        "anchor_src_pos": (3, 9, 13),
    },
}


# ─────────────────────────────────────────────────────────────────────────────
# NBT helpers
# ─────────────────────────────────────────────────────────────────────────────


def read_nbt(path: Path) -> File:
    with gzip.open(path, "rb") as f:
        return File.parse(f)


def write_nbt(nbt: File, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    nbt.save(str(path), gzipped=True)


def palette_idx(palette: list, name: str, props: dict | None = None) -> int:
    """Find or add an entry. Mutates the palette list in place."""
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
    """Block-NBT for MCPShipAnchorBlockEntity. Carries the AirshipKind name baked at
    build time; AirshipKinds.byName(kind) resolves it back to a kind instance at
    trigger time. The {@code id} field is required so MC reconstructs the BE type."""
    return Compound({
        "id": String(ANCHOR_BLOCK),
        "kind": String(kind_name),
    })


def keel_jigsaw_nbt(final_state: str = "minecraft:air") -> Compound:
    """Block-NBT for the airship_keel connector on a ship piece. {@code final_state}
    decides what the cell becomes after JigsawReplacementProcessor runs:
    {@code "minecraft:air"} for external-pad ships (the connector sits inside the pad's
    air column anyway), {@code "minecraft:stone_bricks"} for internal-pad ships (the
    connector lives in the pad layer; air would punch a visible hole)."""
    return Compound({k: String(v) for k, v in {
        "name": KEEL_JIGSAW_NAME,
        "target": LANDING_PAD_JIGSAW_NAME,
        "pool": "minecraft:empty",
        "final_state": final_state,
        "joint": "rollable",
    }.items()})


def landing_pad_jigsaw_nbt() -> Compound:
    """Block-NBT for the parent pad's upward connector. Points back at airship_keel and
    expands the ship-pool to attach the actual hull above the pad."""
    return Compound({k: String(v) for k, v in {
        "name": LANDING_PAD_JIGSAW_NAME,
        "target": KEEL_JIGSAW_NAME,
        "pool": "mcpirates:galleon",  # only the galleon uses external pad today
        "final_state": "minecraft:air",
        "joint": "rollable",
    }.items()})


def strip_keel_jigsaws(nbt: File) -> int:
    """Remove any pre-existing airship_keel jigsaws from the source NBT before injecting
    a fresh one — keeps the pipeline idempotent if someone re-runs with a previously-
    processed NBT as input. Returns the number stripped."""
    kept = []
    stripped = 0
    for b in nbt["blocks"]:
        block_nbt = b.get("nbt")
        if block_nbt is not None and str(block_nbt.get("name", "")) == KEEL_JIGSAW_NAME:
            stripped += 1
            continue
        kept.append(b)
    nbt["blocks"] = List[Compound](kept)
    return stripped


# ─────────────────────────────────────────────────────────────────────────────
# Build operations
# ─────────────────────────────────────────────────────────────────────────────


def build_ship_with_internal_pad(name: str, cfg: dict) -> None:
    """Wrap a raw source ship with a stone-brick pad at NBT y=0, an air gap, then the
    ship body at NBT y=body_y_offset, and finally an air buffer above. Pad at y=0 lands
    at the outpost's base-plate level (the outpost's east jigsaw is at base_plate y=0).
    The body offset leaves visible airspace between the pad and the ship's keel so the
    airship reads as "hovering above a dock", not "embedded in a stone slab".

    Layout (NBT-frame, post-wrap):
      y=0                                   : stone-brick pad, full output footprint.
                                              The keel jigsaw replaces (0, 0, cz) with
                                              final_state=stone_bricks so the cell stays
                                              pad after JigsawReplacementProcessor.
      y=1..body_y_offset-1                  : air (visual gap below the keel).
      y=body_y_offset..body_y_offset+sy-1   : ship body (source y=0..sy-1), shifted
                                              east by `shift_east` so the pad has clear
                                              space west of the body for the outpost
                                              attachment.
      y=body_y_offset+sy..end               : `air_above` layers of air (tree-canopy
                                              clearance).
    """
    src = read_nbt(SOURCES_DIR / f"{name}.nbt")
    stripped = strip_keel_jigsaws(src)
    if stripped:
        print(f"  [{name}] stripped {stripped} pre-existing keel jigsaw(s) from source")

    sx, sy, sz = (int(x) for x in src["size"])
    east_offset = cfg["shift_east"]
    body_y_offset = cfg.get("body_y_offset", 3)
    air_above = cfg.get("air_above", 0)
    out_sx = sx + east_offset
    out_sy = body_y_offset + sy + air_above
    out_sz = sz
    cz = out_sz // 2

    # Build fresh palette starting with stone/air/jigsaw/anchor.
    palette: list[Compound] = []
    pad_idx = palette_idx(palette, PAD_BLOCK)
    air_idx = palette_idx(palette, AIR_BLOCK)
    jigsaw_idx = palette_idx(palette, JIGSAW_BLOCK, {"orientation": cfg["jigsaw_orientation"]})
    anchor_idx = palette_idx(palette, ANCHOR_BLOCK)

    # Append source palette, tracking the index offset so we can remap source states.
    src_palette = list(src["palette"])
    src_idx_offset = len(palette)
    for entry in src_palette:
        palette.append(entry)

    out_blocks: list[Compound] = []
    def add(state: int, x: int, y: int, z: int, block_nbt: Compound | None = None) -> None:
        out_blocks.append(make_block(state, x, y, z, block_nbt))

    src_block_index = {(int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2])): b
                       for b in src["blocks"]}

    body_y_end = body_y_offset + sy  # exclusive

    # The anchor block is stamped at the configured source position, mapped through
    # the same shift_east + body_y_offset transform as the rest of the ship body.
    anchor_src = cfg.get("anchor_src_pos")
    if anchor_src is None:
        raise SystemExit(f"ship '{name}' is missing anchor_src_pos in SHIPS config")
    anchor_out_pos = (
        anchor_src[0] + east_offset,
        anchor_src[1] + body_y_offset,
        anchor_src[2],
    )

    for y in range(out_sy):
        for x in range(out_sx):
            for z in range(out_sz):
                if y == 0:
                    # Pad layer. The jigsaw cell is overwritten below — but the
                    # jigsaw's final_state=stone_bricks makes the post-replacement
                    # state match the pad anyway.
                    if x == 0 and z == cz:
                        add(jigsaw_idx, x, y, z,
                            keel_jigsaw_nbt(final_state=PAD_BLOCK))
                    else:
                        add(pad_idx, x, y, z)
                elif y < body_y_offset:
                    # Air gap between pad and ship keel.
                    add(air_idx, x, y, z)
                elif y < body_y_end:
                    # Ship body — source y = output y - body_y_offset.
                    # The anchor block overrides whatever the source had at this cell
                    # (cfg["anchor_src_pos"] should be an air cell anyway).
                    if (x, y, z) == anchor_out_pos:
                        add(anchor_idx, x, y, z, ship_anchor_nbt(name))
                        continue
                    sx_in = x - east_offset
                    sy_in = y - body_y_offset
                    if 0 <= sx_in < sx and 0 <= z < sz:
                        src_b = src_block_index.get((sx_in, sy_in, z))
                        if src_b is not None:
                            add(int(src_b["state"]) + src_idx_offset, x, y, z, src_b.get("nbt"))
                        else:
                            add(air_idx, x, y, z)
                    else:
                        add(air_idx, x, y, z)
                else:
                    # Top air-buffer.
                    add(air_idx, x, y, z)

    out_nbt = File(Compound({
        "size": List[Int]([Int(out_sx), Int(out_sy), Int(out_sz)]),
        "palette": List[Compound](palette),
        "blocks": List[Compound](out_blocks),
        "entities": src.get("entities", List[Compound]()),
        "DataVersion": src.get("DataVersion", Int(3955)),
    }))
    write_nbt(out_nbt, OUT_DIR / f"{name}.nbt")
    print(f"  [{name}] wrote {out_sx}x{out_sy}x{out_sz}, {len(out_blocks)} blocks "
          f"(keel jigsaw at (0, 0, {cz}) facing {cfg['jigsaw_orientation']}, "
          f"pad y=0, air y=1..{body_y_offset-1}, body y={body_y_offset}..{body_y_end-1}, "
          f"anchor at {anchor_out_pos})")


def build_ship_with_external_pad(name: str, cfg: dict) -> None:
    """Bare ship NBT (no pad) with a center-bottom downward keel jigsaw + a separate
    `<name>_pad.nbt` parent piece (stone pad + air column + upward connector)."""
    src = read_nbt(SOURCES_DIR / f"{name}.nbt")
    stripped = strip_keel_jigsaws(src)
    if stripped:
        print(f"  [{name}] stripped {stripped} pre-existing keel jigsaw(s) from source")

    sx, sy, sz = (int(x) for x in src["size"])

    # Inject keel jigsaw at the center-bottom of the source NBT — facing whatever the
    # config asked for (currently down_south to attach to the pad's upward connector).
    palette = list(src["palette"])
    jigsaw_idx = palette_idx(palette, JIGSAW_BLOCK, {"orientation": cfg["jigsaw_orientation"]})
    anchor_idx = palette_idx(palette, ANCHOR_BLOCK)
    cx, cz = sx // 2, sz // 2

    # Filter: drop the cell where the keel jigsaw lands, AND the cell where the anchor
    # block lands (we replace whatever was there — should be air per cfg).
    anchor_src = cfg.get("anchor_src_pos")
    if anchor_src is None:
        raise SystemExit(f"ship '{name}' is missing anchor_src_pos in SHIPS config")
    anchor_tuple = (anchor_src[0], anchor_src[1], anchor_src[2])
    blocks = []
    for b in src["blocks"]:
        bp = (int(b["pos"][0]), int(b["pos"][1]), int(b["pos"][2]))
        if bp == (cx, 0, cz):
            continue  # keel jigsaw cell
        if bp == anchor_tuple:
            continue  # anchor cell
        blocks.append(b)
    blocks.append(make_block(jigsaw_idx, cx, 0, cz, keel_jigsaw_nbt()))
    blocks.append(make_block(anchor_idx, anchor_src[0], anchor_src[1], anchor_src[2],
                             ship_anchor_nbt(name)))

    out_nbt = File(Compound({
        "size": List[Int]([Int(sx), Int(sy), Int(sz)]),
        "palette": List[Compound](palette),
        "blocks": List[Compound](blocks),
        "entities": src.get("entities", List[Compound]()),
        "DataVersion": src.get("DataVersion", Int(3955)),
    }))
    write_nbt(out_nbt, OUT_DIR / f"{name}.nbt")
    print(f"  [{name}] wrote bare ship {sx}x{sy}x{sz}, "
          f"keel jigsaw at ({cx}, 0, {cz}) facing {cfg['jigsaw_orientation']}, "
          f"anchor at {anchor_tuple}")

    # Now the parent pad piece.
    pad_sx = cfg["pad_size_x"]
    pad_sz = cfg["pad_size_z"]
    pad_jy = cfg["pad_jigsaw_y"]
    pad_sy = pad_jy + sy + cfg["pad_buffer_above"]

    pad_palette: list[Compound] = []
    pad_pad_idx = palette_idx(pad_palette, PAD_BLOCK)
    pad_air_idx = palette_idx(pad_palette, AIR_BLOCK)
    pad_jigsaw_idx = palette_idx(pad_palette, JIGSAW_BLOCK, {"orientation": "up_north"})

    jigsaw_x, jigsaw_z = pad_sx // 2, pad_sz // 2

    pad_blocks: list[Compound] = []
    for y in range(pad_sy):
        for x in range(pad_sx):
            for z in range(pad_sz):
                if y == 0:
                    pad_blocks.append(make_block(pad_pad_idx, x, y, z))
                elif y == pad_jy and x == jigsaw_x and z == jigsaw_z:
                    pad_blocks.append(make_block(pad_jigsaw_idx, x, y, z, landing_pad_jigsaw_nbt()))
                else:
                    pad_blocks.append(make_block(pad_air_idx, x, y, z))

    pad_nbt = File(Compound({
        "size": List[Int]([Int(pad_sx), Int(pad_sy), Int(pad_sz)]),
        "palette": List[Compound](pad_palette),
        "blocks": List[Compound](pad_blocks),
        "entities": List[Compound](),
        "DataVersion": Int(3955),
    }))
    write_nbt(pad_nbt, OUT_DIR / f"{name}_pad.nbt")
    print(f"  [{name}_pad] wrote {pad_sx}x{pad_sy}x{pad_sz}, "
          f"upward jigsaw at ({jigsaw_x}, {pad_jy}, {jigsaw_z})")


def build_base_plate_with_airship(pool: str, out_name: str) -> None:
    """Clone vanilla pillager_outpost/base_plate, repoint its east-facing feature_plates
    jigsaw at (15, 0, 8) to {@code pool}, and write the result to
    src/.../structure/{@code out_name}.nbt.

    Called once with the default 50/50 pool for normal worldgen, then once per ship for
    the single-ship variants the {@code /mcpirates outpost spawn <ship>} command places."""
    if not NEOFORGE_RESOURCE_JAR.exists():
        raise SystemExit(f"Vanilla resource jar not found: {NEOFORGE_RESOURCE_JAR}\n"
                         "Run `./gradlew createMinecraftArtifacts` first.")

    # Extract vanilla NBT (cached under build/tmp so we only do this once per session).
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
        block_nbt["target"] = String(KEEL_JIGSAW_NAME)
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


def do_backup() -> Path | None:
    """Auto-backup is disabled — {@code tools/backups/original/} is the only kept copy
    (the earliest pre-corruption snapshot of airship_small + friends, useful for
    recovering source data if a script messes things up). Re-enable by un-no-op-ing
    this function if you need timestamped backups again."""
    return None


def build_ship(name: str) -> None:
    cfg = SHIPS[name]
    print(f"\nbuilding {name}…")
    if cfg.get("internal_pad"):
        build_ship_with_internal_pad(name, cfg)
    elif cfg.get("external_pad"):
        build_ship_with_external_pad(name, cfg)
    else:
        raise ValueError(f"ship {name!r} has neither internal_pad nor external_pad in config")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ship", choices=list(SHIPS),
                        help="Rebuild only this ship (and its pad if external). "
                             "Default: all ships.")
    parser.add_argument("--no-backup", action="store_true",
                        help="(no-op) — auto-backup is disabled; flag preserved for "
                             "script-call compatibility.")
    parser.add_argument("--skip-base-plate", action="store_true",
                        help="Skip rebuilding base_plate_with_airship.nbt.")
    args = parser.parse_args()

    if not args.no_backup:
        do_backup()

    ships = [args.ship] if args.ship else list(SHIPS)
    for name in ships:
        build_ship(name)

    # Rebuild outpost base plate variants whenever any outpost-attached ship is in scope.
    # We always build the default (50/50 pool) plus one per outpost-attached ship that's
    # in scope, where the variant points at a single-ship pool. The single-ship variants
    # are the targets of `/mcpirates outpost spawn <ship>`.
    outpost_ships = [n for n in ships if SHIPS[n].get("internal_pad")]
    if outpost_ships and not args.skip_base_plate:
        print("\nbuilding outpost base plate variants…")
        build_base_plate_with_airship("mcpirates:airships", "base_plate_with_airship")
        for n in outpost_ships:
            build_base_plate_with_airship(f"mcpirates:airships_{n}_only",
                                          f"base_plate_with_{n}")

    print("\ndone.")


if __name__ == "__main__":
    main()
