"""Two-phase integration-test orchestrator with optional parallel slots.

For each (seed, scenario) tuple we run a two-phase test:
  Phase 1 (no unfurl) — boot, RCON `mcpirates test unfurl preview` to learn the
    candidate cell, force-load + probe, assert NO ship_anchor exists.
  Phase 2 (with unfurl) — re-boot (world wiped by doFirst), RCON `mcpirates
    test unfurl`, assert same cell + anchor present.

Slot allocation: build.gradle defines integrationServer1..4. Each slot has its
own gameDirectory (runs/integration_<N>) + ports (server 25665+N / RCON 25684+N).
Per-slot seed is passed via gradle property `-Pslot<N>Seed=<seed>` (build.gradle
doFirst reads it and stamps level-seed into server.properties).

Single-command entrypoints:
    ./gradlew runIntegrationTest                    # default: 1 seed, parallel=2
    python src/integrationTest/python/run.py --seeds 999 42 --parallel 2
"""

from __future__ import annotations

import argparse
import re
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from queue import Queue
from threading import Lock

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
from mcp_minecraft.rcon import RconClient, RconError  # noqa: E402

RCON_HOST = "127.0.0.1"
RCON_PASS = "dev"
MAX_SLOTS = 4

BOOT_TIMEOUT_S = 240
SHUTDOWN_TIMEOUT_S = 60

# Scenario tuple: (label, type, extra). type ∈ {"two_phase", "scan_only"}.
# - two_phase: full preview → no-anchor → unfurl → cell-matches → anchor-placed.
#   `extra` is the galleon bool flag.
# - scan_only: boot fresh world, call test scan_candidates, assert anchors=0.
#   `extra` is ignored. Proves the gate holds globally before any scroll touches it.
SCENARIOS: list[tuple[str, str, bool]] = [
    ("outpost",           "two_phase", False),
    ("galleon",           "two_phase", True),
    ("no_natural_spawn",  "scan_only", False),
]

# Region half-radius for scan_only. 3 → 7×7=49 regions per kind × 4 kinds = 196 chunks.
SCAN_HALF_RADIUS = 3

_PREVIEW_RE = re.compile(r"preview OK kind=(\S+)\s+cell=(-?\d+),(-?\d+)")
_UNFURL_RE = re.compile(
    r"kind=(\S+)\s+cell=(-?\d+),(-?\d+)\s+ship=(\S+)\s+anchor=(-?\d+),(-?\d+),(-?\d+)"
)

_print_lock = Lock()


def log(slot: int | None, msg: str) -> None:
    """Thread-safe log line with slot prefix."""
    with _print_lock:
        prefix = f"[slot{slot}]" if slot is not None else "      "
        print(f"{prefix} {msg}", flush=True)


def rcon_port(slot: int) -> int:
    return 25684 + slot


def port_open(host: str, port: int) -> bool:
    try:
        s = socket.create_connection((host, port), timeout=1)
        s.close()
        return True
    except OSError:
        return False


def wait_rcon_up(slot: int, timeout: float) -> bool:
    deadline = time.time() + timeout
    port = rcon_port(slot)
    while time.time() < deadline:
        if port_open(RCON_HOST, port):
            try:
                RconClient(RCON_HOST, port, RCON_PASS, timeout=3.0).cmd("seed")
                return True
            except (OSError, RconError):
                pass
        time.sleep(2.0)
    return False


def wait_rcon_down(slot: int, timeout: float) -> bool:
    deadline = time.time() + timeout
    port = rcon_port(slot)
    while time.time() < deadline:
        if not port_open(RCON_HOST, port):
            return True
        time.sleep(1.0)
    return False


def boot_server(slot: int, seed: int) -> subprocess.Popen[bytes]:
    gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    cmd = [str(gradlew), f"runIntegrationServer{slot}", f"-Pslot{slot}Seed={seed}"]
    log(slot, f"boot seed={seed}")
    return subprocess.Popen(
        cmd, cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )


def stop_server(slot: int, server: subprocess.Popen[bytes]) -> None:
    try:
        RconClient(RCON_HOST, rcon_port(slot), RCON_PASS, timeout=5.0).cmd("stop")
    except (OSError, RconError) as e:
        log(slot, f"warn: /stop failed: {e!r}")
    if not wait_rcon_down(slot, SHUTDOWN_TIMEOUT_S):
        log(slot, f"warn: shutdown timeout; killing")
        server.kill()
    try:
        server.wait(timeout=30)
    except subprocess.TimeoutExpired:
        server.kill()


def scan_for_anchor(rcon: RconClient, cell: tuple[int, int]) -> tuple[int, int, int] | None:
    """Force-load 3x3 chunks around the cell and scan a coarse XYZ grid for any
    mcpirates:ship_anchor. Returns the first hit's coords or None."""
    cx, cz = cell
    x0, z0 = cx * 16 - 16, cz * 16 - 16
    x1, z1 = cx * 16 + 31, cz * 16 + 31
    rcon.cmd(f"forceload add {x0} {z0} {x1} {z1}")
    time.sleep(2)
    for y in (60, 65, 70, 75, 80, 85, 90, 95, 100, 110, 120):
        for dx in range(-16, 32, 4):
            for dz in range(-16, 32, 4):
                x = cx * 16 + dx
                z = cz * 16 + dz
                res = rcon.cmd(f"execute if block {x} {y} {z} mcpirates:ship_anchor run say A")
                if "A" in res:
                    return (x, y, z)
    return None


def run_scan_only(slot: int, seed: int) -> tuple[bool, float, str]:
    """Boot a fresh world (no permits), force-load all permit-gated structure candidate
    cells in (-N..N)² regions per kind, assert zero ship_anchor placements. Proves the
    gate's hard-deny path holds across an N-region grid without any scroll interaction."""
    t0 = time.time()
    label = f"seed={seed} no_natural_spawn"
    server = boot_server(slot, seed)
    if not wait_rcon_up(slot, BOOT_TIMEOUT_S):
        server.kill()
        return False, time.time() - t0, f"{label}: boot timeout"

    # Big RCON timeout because force-loading ~200 chunks on first generation can take
    # several seconds on the server thread.
    rcon = RconClient(RCON_HOST, rcon_port(slot), RCON_PASS, timeout=180.0)
    out = rcon.cmd(f"mcpirates test scan_candidates {SCAN_HALF_RADIUS}")
    log(slot, f"scan_candidates: {out.strip()}")
    stop_server(slot, server)

    if "anchors=0" not in out:
        return False, time.time() - t0, f"{label}: FAIL {out.strip()}"
    return True, time.time() - t0, f"{label}: PASS {out.strip()}"


def run_two_phase(slot: int, seed: int, scenario: str, galleon: bool) -> tuple[bool, float, str]:
    """Returns (pass, duration_s, message)."""
    t0 = time.time()
    label = f"seed={seed} scenario={scenario}"

    # Phase 1: boot, preview, scan, assert empty.
    server = boot_server(slot, seed)
    if not wait_rcon_up(slot, BOOT_TIMEOUT_S):
        server.kill()
        return False, time.time() - t0, f"{label}: phase 1 boot timeout"

    rcon = RconClient(RCON_HOST, rcon_port(slot), RCON_PASS, timeout=30.0)
    sub = "test unfurl preview galleon" if galleon else "test unfurl preview"
    out = rcon.cmd(f"execute positioned 0 64 0 run mcpirates {sub}")
    m = _PREVIEW_RE.search(out)
    if not m:
        stop_server(slot, server)
        return False, time.time() - t0, f"{label}: preview unparseable: {out!r}"
    preview_cell = (int(m.group(2)), int(m.group(3)))
    log(slot, f"phase1 preview cell={preview_cell}")

    found = scan_for_anchor(rcon, preview_cell)
    if found is not None:
        stop_server(slot, server)
        return False, time.time() - t0, (
            f"{label}: ship_anchor at {found} BEFORE unfurl — permit gate broken")
    log(slot, f"phase1.OK no anchor at {preview_cell}")
    stop_server(slot, server)

    # Phase 2: re-boot (world wiped by doFirst), unfurl, verify same cell + anchor.
    server = boot_server(slot, seed)
    if not wait_rcon_up(slot, BOOT_TIMEOUT_S):
        server.kill()
        return False, time.time() - t0, f"{label}: phase 2 boot timeout"

    rcon = RconClient(RCON_HOST, rcon_port(slot), RCON_PASS, timeout=30.0)
    sub = "test unfurl galleon" if galleon else "test unfurl"
    out = rcon.cmd(f"execute positioned 0 64 0 run mcpirates {sub}")
    m = _UNFURL_RE.search(out)
    if not m:
        stop_server(slot, server)
        return False, time.time() - t0, f"{label}: unfurl unparseable: {out!r}"
    cell = (int(m.group(2)), int(m.group(3)))
    ship = m.group(4)
    anchor = (int(m.group(5)), int(m.group(6)), int(m.group(7)))
    log(slot, f"phase2 unfurl cell={cell} ship={ship} anchor={anchor}")

    if cell != preview_cell:
        stop_server(slot, server)
        return False, time.time() - t0, (
            f"{label}: phase 2 cell {cell} != phase 1 prediction {preview_cell}")
    if ship == "<unknown>" or not ship:
        stop_server(slot, server)
        return False, time.time() - t0, f"{label}: anchor kind blank"
    if galleon and ship != "galleon":
        stop_server(slot, server)
        return False, time.time() - t0, f"{label}: expected galleon, got {ship}"
    if (not galleon) and ship == "galleon":
        stop_server(slot, server)
        return False, time.time() - t0, f"{label}: non-galleon picked galleon"
    stop_server(slot, server)

    return True, time.time() - t0, f"{label}: PASS cell={cell} ship={ship}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--seeds", nargs="+", type=int, default=[999],
                    help="World seeds to test. Default: 999.")
    ap.add_argument("--parallel", type=int, default=2,
                    help=f"Parallel server slots (1..{MAX_SLOTS}). Default 2.")
    args = ap.parse_args()

    if args.parallel < 1 or args.parallel > MAX_SLOTS:
        print(f"--parallel must be 1..{MAX_SLOTS}")
        return 2

    # Refuse to start if any of the slot ports we'll use is already open.
    for slot in range(1, args.parallel + 1):
        if port_open(RCON_HOST, rcon_port(slot)):
            print(f"[fail-env] RCON {rcon_port(slot)} already open; stop slot {slot}'s server first")
            return 2

    tasks: list[tuple[int, str, str, bool]] = []
    for seed in args.seeds:
        for label, kind, galleon in SCENARIOS:
            tasks.append((seed, label, kind, galleon))
    print(f"running {len(tasks)} (seed, scenario) task(s) across {args.parallel} slot(s)")
    print(f"  seeds: {args.seeds}")
    print(f"  scenarios: {[s[0] for s in SCENARIOS]}")

    # Slot pool: ThreadPoolExecutor + Queue. Each task acquires a slot, runs, releases.
    slots: Queue[int] = Queue()
    for s in range(1, args.parallel + 1):
        slots.put(s)

    results: list[tuple[bool, float, str]] = []
    wall_start = time.time()

    def task_wrapper(seed: int, scenario: str, kind: str, galleon: bool) -> tuple[bool, float, str]:
        slot = slots.get()
        try:
            if kind == "two_phase":
                return run_two_phase(slot, seed, scenario, galleon)
            elif kind == "scan_only":
                return run_scan_only(slot, seed)
            else:
                return False, 0.0, f"unknown scenario kind: {kind}"
        finally:
            slots.put(slot)

    with ThreadPoolExecutor(max_workers=args.parallel) as ex:
        futures = [ex.submit(task_wrapper, seed, scen, k, g) for seed, scen, k, g in tasks]
        for f in as_completed(futures):
            results.append(f.result())

    wall_total = time.time() - wall_start

    print("\n=== summary ===")
    fails = 0
    for ok, dur, msg in results:
        tag = "PASS" if ok else "FAIL"
        print(f"  [{tag}] ({dur:5.1f}s) {msg}")
        if not ok:
            fails += 1
    print(f"\nwall-clock: {wall_total:.1f}s "
          f"({sum(d for _, d, _ in results):.1f}s of CPU-task-time, "
          f"speedup={sum(d for _, d, _ in results)/wall_total:.2f}x)")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
