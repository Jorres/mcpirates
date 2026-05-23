"""Integration-test orchestrator: boots runIntegrationServer, runs every scenario,
sends /stop, returns nonzero if anything failed. Designed for unattended CI.

Single-command entry:
    ./gradlew runIntegrationTest

Or directly:
    python src/integrationTest/python/run.py

If port 25685 (RCON) is already listening, we assume an integration server is
already running and skip the boot/stop dance — useful for iterating against a
locally-running server without restarting it each time.
"""

from __future__ import annotations

import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
from mcp_minecraft.rcon import RconClient, RconError  # noqa: E402

RCON_HOST = "127.0.0.1"
RCON_PORT = 25685
RCON_PASS = "dev"

# Scenarios to run sequentially against one server boot. Each tuple is
# (label, extra args passed to test_unfurl.py).
SCENARIOS: list[tuple[str, list[str]]] = [
    ("outpost", []),
    ("galleon", ["--galleon"]),
]

BOOT_TIMEOUT_S = 240
SHUTDOWN_TIMEOUT_S = 60


def port_open(host: str, port: int) -> bool:
    try:
        s = socket.create_connection((host, port), timeout=1)
        s.close()
        return True
    except OSError:
        return False


def wait_rcon_up(timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if port_open(RCON_HOST, RCON_PORT):
            # Port open ≠ RCON ready (auth comes online a beat later). Touch RCON.
            try:
                RconClient(RCON_HOST, RCON_PORT, RCON_PASS, timeout=3.0).cmd("seed")
                return True
            except (OSError, RconError):
                pass
        time.sleep(2.0)
    return False


def wait_rcon_down(timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not port_open(RCON_HOST, RCON_PORT):
            return True
        time.sleep(1.0)
    return False


def boot_server() -> subprocess.Popen[bytes]:
    # gradlew on Windows resolves to gradlew.bat; the wrapper handles this.
    gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    print(f"[info] booting integration server via {gradlew.name}...", flush=True)
    return subprocess.Popen(
        [str(gradlew), "runIntegrationServer"],
        cwd=str(ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def stop_server_via_rcon() -> None:
    try:
        RconClient(RCON_HOST, RCON_PORT, RCON_PASS, timeout=5.0).cmd("stop")
    except (OSError, RconError) as e:
        print(f"[warn] /stop via RCON failed: {e!r}")


def run_scenarios() -> dict[str, int]:
    test_script = Path(__file__).parent / "test_unfurl.py"
    results: dict[str, int] = {}
    for label, extra in SCENARIOS:
        print(f"\n=== scenario: {label} ===", flush=True)
        rc = subprocess.call(
            [sys.executable, str(test_script), *extra],
            cwd=str(ROOT),
        )
        results[label] = rc
    return results


def main() -> int:
    server: subprocess.Popen[bytes] | None = None
    we_started_server = False

    if port_open(RCON_HOST, RCON_PORT):
        print("[info] RCON already responding; using existing server")
    else:
        server = boot_server()
        we_started_server = True
        if not wait_rcon_up(BOOT_TIMEOUT_S):
            print(f"[fail-env] server did not become RCON-ready within {BOOT_TIMEOUT_S}s")
            if server is not None:
                server.kill()
            return 2

    try:
        results = run_scenarios()
        print("\n=== summary ===")
        for label, rc in results.items():
            tag = "PASS" if rc == 0 else f"FAIL(exit={rc})"
            print(f"  {label}: {tag}")
        return 0 if all(rc == 0 for rc in results.values()) else 1
    finally:
        if we_started_server and server is not None:
            stop_server_via_rcon()
            if not wait_rcon_down(SHUTDOWN_TIMEOUT_S):
                print(f"[warn] server did not shut down within {SHUTDOWN_TIMEOUT_S}s; killing")
                server.kill()
            try:
                server.wait(timeout=30)
            except subprocess.TimeoutExpired:
                server.kill()


if __name__ == "__main__":
    sys.exit(main())
