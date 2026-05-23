"""E2E integration test for FurledBountyItem against real worldgen.

Validates that a furled scroll unfurled in a full-worldgen world produces a map
whose target really is at a generated pirate outpost — something the superflat
runGameTestServer environment can't exercise.

CI-friendly: no connected client needed. The /mcpirates testunfurl command
runs against a server-side FakePlayer (NeoForge's headless ServerPlayer
subclass), so this script only needs the integration server running.

Workflow:
    1. ./gradlew runIntegrationServer   # background, takes ~10s to be ready
    2. python src/integrationTest/python/test_unfurl.py

For CI (single command, no manual lifecycle):
    ./gradlew runIntegrationTest

Integration server lives on alt ports (server 25666, RCON 25685) so it can run
alongside the regular dev runServer (25565/25575).

Exit codes: 0 = pass, 1 = real fail, 2 = environmental (server not reachable).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Locate tools/mcp_minecraft from this file's position (src/integrationTest/python/).
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
from mcp_minecraft.rcon import RconClient, RconError  # noqa: E402

RCON_HOST = "127.0.0.1"
RCON_PORT = 25685
RCON_PASS = "dev"

# addTargetDecoration stamps the exact BlockPos returned by findNearestMapStructure
# into the stack component, so the only slack here is /locate's chunk-rounded answer.
TOLERANCE_BLOCKS = 16


_UNFURL_RE = re.compile(
    r"map_id=(-?\d+)\s+target=(-?\d+),(-?\d+)\s+ship=(\S+)\s+anchor=(-?\d+),(-?\d+),(-?\d+)"
)
_LOCATE_RE = re.compile(r"\[(-?\d+),\s*~,\s*(-?\d+)\]")


def parse_unfurl(line: str) -> tuple[int, int, int, str, tuple[int, int, int]]:
    m = _UNFURL_RE.search(line)
    if not m:
        raise AssertionError(f"unfurl response unparseable: {line!r}")
    return (
        int(m.group(1)),
        int(m.group(2)),
        int(m.group(3)),
        m.group(4),
        (int(m.group(5)), int(m.group(6)), int(m.group(7))),
    )


def parse_locate(line: str) -> tuple[int, int] | None:
    m = _LOCATE_RE.search(line)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--galleon", action="store_true",
                    help="Test galleon-bounty path instead of regular outpost.")
    ap.add_argument("--from", dest="origin", default="0 64 0",
                    help="Origin coords for the search, as '<x> <y> <z>' "
                         "passed to /execute positioned. Default '0 64 0'.")
    args = ap.parse_args()

    try:
        rcon = RconClient(RCON_HOST, RCON_PORT, RCON_PASS, timeout=10.0)
        rcon.cmd("seed")
    except (OSError, RconError) as e:
        print(f"[fail-env] cannot reach RCON at {RCON_HOST}:{RCON_PORT}: {e!r}")
        print("           start the server with: ./gradlew runIntegrationServer")
        return 2

    sub = "testunfurl galleon" if args.galleon else "testunfurl"
    out = rcon.cmd(f"execute positioned {args.origin} run mcpirates {sub}")
    print(f"[server] {out!r}")

    if "FAIL" in out:
        if "furled_bounty" in out:
            print("[fail] no_target — biome whitelist excluded everything in range.")
            print("       try a different --from origin in plains/forest/taiga.")
        else:
            print(f"[fail] command returned FAIL: {out!r}")
        return 1

    map_id, tx, tz, ship, anchor = parse_unfurl(out)
    print(f"[ok] map_id={map_id} target=({tx}, {tz}) ship={ship} anchor={anchor}")
    if ship in ("<unknown>", ""):
        print("[fail] anchor present but kind is blank — broken NBT?")
        return 1

    structure_tag = "#mcpirates:pirate_galleons" if args.galleon else "#mcpirates:pirate_outposts"
    locate = rcon.cmd(
        f"execute positioned {tx} 64 {tz} run locate structure {structure_tag}"
    )
    print(f"[server] {locate!r}")

    coords = parse_locate(locate)
    if coords is None:
        print(f"[fail] locate found no structure near map target")
        return 1
    sx, sz = coords
    dist = ((sx - tx) ** 2 + (sz - tz) ** 2) ** 0.5
    print(f"[info] nearest structure at ({sx}, {sz}); "
          f"distance from map target: {dist:.1f} blocks "
          f"(tolerance {TOLERANCE_BLOCKS})")

    if dist > TOLERANCE_BLOCKS:
        print(f"[fail] map target {dist:.1f} blocks from nearest real structure")
        return 1

    print("[PASS] map points at a real generated structure")
    return 0


if __name__ == "__main__":
    sys.exit(main())
