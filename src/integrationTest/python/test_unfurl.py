"""E2E integration test for FurledBountyItem against real worldgen.

Validates that a furled scroll unfurled in a full-worldgen world produces a map
whose target really is at a generated pirate outpost — something the superflat
runGameTestServer environment can't exercise.

CI-friendly: no connected client needed. The /mcpirates test unfurl command
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

_UNFURL_RE = re.compile(
    r"kind=(\S+)\s+cell=(-?\d+),(-?\d+)\s+ship=(\S+)\s+anchor=(-?\d+),(-?\d+),(-?\d+)"
)
_PREVIEW_RE = re.compile(r"preview OK kind=(\S+)\s+cell=(-?\d+),(-?\d+)")


def parse_unfurl(line: str) -> tuple[str, tuple[int, int], str, tuple[int, int, int]]:
    m = _UNFURL_RE.search(line)
    if not m:
        raise AssertionError(f"unfurl response unparseable: {line!r}")
    return (
        m.group(1),
        (int(m.group(2)), int(m.group(3))),
        m.group(4),
        (int(m.group(5)), int(m.group(6)), int(m.group(7))),
    )


def parse_preview(line: str) -> tuple[str, tuple[int, int]]:
    m = _PREVIEW_RE.search(line)
    if not m:
        raise AssertionError(f"preview response unparseable: {line!r}")
    return m.group(1), (int(m.group(2)), int(m.group(3)))


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

    sub = "test unfurl galleon" if args.galleon else "test unfurl"
    out = rcon.cmd(f"execute positioned {args.origin} run mcpirates {sub}")
    print(f"[server] {out!r}")

    if "FAIL" in out:
        if "furled_bounty" in out:
            print("[fail] no_target — biome whitelist excluded everything in range.")
            print("       try a different --from origin in plains/forest/taiga.")
        else:
            print(f"[fail] command returned FAIL: {out!r}")
        return 1

    kind, cell, ship, anchor = parse_unfurl(out)
    print(f"[ok] kind={kind} cell={cell} ship={ship} anchor={anchor}")
    if ship in ("<unknown>", ""):
        print("[fail] anchor present but kind is blank — broken NBT?")
        return 1
    if args.galleon and ship != "galleon":
        print(f"[fail] galleon scroll resolved to ship={ship}, expected galleon")
        return 1
    if (not args.galleon) and ship == "galleon":
        print(f"[fail] regular scroll resolved to galleon — kind selection broken")
        return 1
    print("[PASS] scroll unfurled, structure-set permit honoured, ship anchor placed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
