"""build_ships.py — patches the vanilla pillager_outpost base_plate to attach our pads.

Ships, pads, and appendages are hand-authored in MC and saved directly into
src/main/resources/data/mcpirates/structure/ — this script no longer touches them.

The one remaining build step is mutating vanilla NBT: we clone
data/minecraft/structure/pillager_outpost/base_plate.nbt out of the
neoforge-client-extra resource jar and repoint its east-facing feature_plates jigsaw
at our pad pool. That produces base_plate_with_airship.nbt (default pool) and
base_plate_with_<ship>.nbt for each outpost-attached ship — the only NBTs that
can't live as hand-authored sources, because they derive from a vanilla file we
don't ship as source.

Run from the project root:
    python tools/build_ships.py            # builds default + all per-ship variants
    python tools/build_ships.py --ship X   # builds default + the X-only variant
"""
from __future__ import annotations

import argparse
import shutil
import zipfile
from pathlib import Path

from nbtlib import File, Int, String

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "src" / "main" / "resources" / "data" / "mcpirates" / "structure"
NEOFORGE_RESOURCE_JAR = (
    REPO_ROOT / "build" / "moddev" / "artifacts"
    / "neoforge-21.1.228-client-extra-aka-minecraft-resources.jar"
)
VANILLA_BASE_PLATE_PATH = "data/minecraft/structure/pillager_outpost/base_plate.nbt"

OUTPOST_ANCHOR_JIGSAW_NAME = "mcpirates:outpost_anchor"
JIGSAW_BLOCK = "minecraft:jigsaw"

# Outpost-attached ships — each gets its own single-ship base_plate variant.
# The standalone galleon is not listed: it is spawned by /mcpirates galleon spawn,
# not chained from a pillager outpost.
OUTPOST_SHIPS = ("airship_small", "crossbow_board", "ramship")


def read_nbt(path: Path) -> File:
    return File.load(str(path), gzipped=True)


def write_nbt(nbt: File, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    nbt.save(str(path), gzipped=True)


def build_base_plate_with_airship(pool: str, out_name: str) -> None:
    """Clone vanilla pillager_outpost/base_plate, repoint its east-facing feature_plates
    jigsaw at (15, 0, 8) at our pool."""
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
        block_nbt["target"] = String(OUTPOST_ANCHOR_JIGSAW_NAME)
        block_nbt["pool"] = String(pool)
        block_nbt["name"] = String("mcpirates:airship_anchor")
        # placement_priority biases MC's jigsaw chain order: higher = processed first.
        # Vanilla feature_plates/towers default to 0; bumping our pad east jigsaw to a
        # positive value makes the pad chain before feature_plates / fence borders, so
        # the pad's bbox is reserved first and other pieces fit around it. Without this
        # the chain order is RNG-driven and the pad sometimes gets rejected for bbox
        # conflict with a feature_plate that grabbed the area first.
        block_nbt["placement_priority"] = Int(10)
        repointed += 1

    if repointed == 0:
        raise SystemExit("Could not find vanilla east plate_entry jigsaw to repoint.")
    write_nbt(nbt, OUT_DIR / f"{out_name}.nbt")
    print(f"  [{out_name}] repointed {repointed} east jigsaw(s) -> {pool}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ship", choices=OUTPOST_SHIPS,
                        help="Build only the default + this ship's variant. Default: all.")
    args = parser.parse_args()

    print("building outpost base plate variants…")
    build_base_plate_with_airship("mcpirates:airship_pads", "base_plate_with_airship")
    ships = [args.ship] if args.ship else list(OUTPOST_SHIPS)
    for n in ships:
        build_base_plate_with_airship(f"mcpirates:airship_pads_{n}_only",
                                      f"base_plate_with_{n}")
    print("done.")


if __name__ == "__main__":
    main()
