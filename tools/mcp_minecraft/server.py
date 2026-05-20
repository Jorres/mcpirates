"""MCP server exposing the running mcpirates dev Minecraft instance to Claude Code.

Tools are intentionally narrow — small, composable wrappers over `/data get`,
`/locate`, `/tp`, plus log-tailing. If you find yourself wanting more, extend
this file rather than letting Claude shell out for one-offs.

Run via the project-root `.mcp.json`. Required env:
  MC_RCON_HOST       (default 127.0.0.1)
  MC_RCON_PORT       (default 25575)
  MC_RCON_PASSWORD   (default "dev" — must match server.properties)
  MC_LOG_PATH        (default <repo>/runs/server/logs/latest.log)
"""

from __future__ import annotations

import os
import re
from pathlib import Path

from mcp.server.fastmcp import FastMCP

from .bridge import BridgeClient, BridgeError
from .rcon import RconClient, RconError


_HOST = os.environ.get("MC_RCON_HOST", "127.0.0.1")
_PORT = int(os.environ.get("MC_RCON_PORT", "25575"))
_PASS = os.environ.get("MC_RCON_PASSWORD", "dev")

_DEFAULT_LOG = (
    Path(__file__).resolve().parents[2] / "runs" / "server" / "logs" / "latest.log"
)
_LOG_PATH = Path(os.environ.get("MC_LOG_PATH", str(_DEFAULT_LOG)))

_rcon = RconClient(_HOST, _PORT, _PASS)
_bridge = BridgeClient(_HOST, int(os.environ.get("MC_BRIDGE_PORT", "25580")))
mcp = FastMCP("mcpirates")


def _effective_cmd(command: str) -> str:
    """Strip leading slash and any `execute ... run ` prefixes (possibly nested)
    to return the actual command being executed."""
    s = command.strip().lstrip("/")
    while True:
        low = s.lower()
        if not low.startswith("execute "):
            return s
        idx = low.find(" run ")
        if idx == -1:
            return s
        s = s[idx + len(" run "):].lstrip()


def _run(command: str) -> str:
    eff = _effective_cmd(command)
    head = eff.split(" ", 1)[0].lower() if eff else ""
    if head == "fill":
        return (
            "[refused: /fill is forbidden through the MCP — it is destructive "
            "and easy to misuse as a query. Use read_block / setblock instead.]"
        )
    try:
        return _rcon.cmd(command)
    except (OSError, RconError) as e:
        return f"[rcon error: {e}]"


def _bridge_call(method: str, params: dict | None = None) -> dict | str:
    try:
        return _bridge.call(method, params)
    except (OSError, BridgeError) as e:
        return f"[bridge error: {e}]"


@mcp.tool()
def cmd(command: str) -> str:
    """Run a raw server command via RCON. Leading "/" is optional.

    Returns the server's response text (often empty for successful mutations).
    """
    return _run(command.lstrip("/"))


@mcp.tool()
def tail_log(lines: int = 200) -> str:
    """Return the last N lines of the server log."""
    if not _LOG_PATH.exists():
        return f"[log not found at {_LOG_PATH}]"
    text = _LOG_PATH.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join(text[-lines:])


@mcp.tool()
def grep_log(pattern: str, max_matches: int = 100, context: int = 0) -> str:
    """Regex-grep the server log. `context` is lines before/after each match."""
    if not _LOG_PATH.exists():
        return f"[log not found at {_LOG_PATH}]"
    try:
        rx = re.compile(pattern)
    except re.error as e:
        return f"[bad regex: {e}]"
    lines = _LOG_PATH.read_text(encoding="utf-8", errors="replace").splitlines()
    hits: list[str] = []
    matched = 0
    for i, line in enumerate(lines):
        if rx.search(line):
            lo = max(0, i - context)
            hi = min(len(lines), i + context + 1)
            hits.append(f"--- L{i + 1} ---")
            hits.extend(lines[lo:hi])
            matched += 1
            if matched >= max_matches:
                break
    if not hits:
        return "[no matches]"
    return "\n".join(hits)


@mcp.tool()
def read_block(x: int, y: int, z: int) -> str:
    """Get the block state + block-entity NBT at the given world coords.

    Tries `/data get block X Y Z` first (returns SNBT for block entities), and
    falls back to `/mcpirates debug getblock X Y Z` for plain blocks — so this
    works for every block, not just BEs.
    """
    result = _run(f"data get block {x} {y} {z}")
    if "not a block entity" in result:
        return _run(f"mcpirates debug getblock {x} {y} {z}")
    return result


@mcp.tool()
def read_block_path(x: int, y: int, z: int, path: str) -> str:
    """Get a specific NBT path from the block entity at X Y Z.

    Example: read_block_path(7, 75, 5, "State") on the analog lever.
    """
    return _run(f"data get block {x} {y} {z} {path}")


@mcp.tool()
def read_entities(selector: str = "@e", path: str = "") -> str:
    """`/data get entity SELECTOR [PATH]`. Beware huge selectors.

    Common selectors: `@p`, `@e[type=minecraft:pillager,limit=5]`,
    `@e[type=simulated:honey_glue]`.
    """
    suffix = f" {path}" if path else ""
    return _run(f"data get entity {selector}{suffix}")


@mcp.tool()
def locate_structure(structure: str) -> str:
    """`/locate structure NAME`. Returns server text — parse coords from it.

    Use the long form: e.g. `minecraft:pillager_outpost`, `mcpirates:airships`.
    """
    return _run(f"locate structure {structure}")


@mcp.tool()
def tp(
    x: float,
    y: float,
    z: float,
    target: str = "@s",
    yaw: float | None = None,
    pitch: float | None = None,
) -> str:
    """Teleport TARGET to (x, y, z), optionally setting yaw/pitch.

    `@s` only works when there's an executor; for headless RCON use a player
    name or `@p` (closest player to RCON's notional origin = world spawn).
    """
    rot = ""
    if yaw is not None and pitch is not None:
        rot = f" {yaw} {pitch}"
    return _run(f"tp {target} {x} {y} {z}{rot}")


@mcp.tool()
def players() -> str:
    """List online players (`/list`)."""
    return _run("list")


@mcp.tool()
def time_set(value: str = "day") -> str:
    """`/time set VALUE` (day, night, noon, midnight, or a number)."""
    return _run(f"time set {value}")


@mcp.tool()
def gamemode(mode: str = "creative", target: str = "@s") -> str:
    """`/gamemode MODE TARGET`."""
    return _run(f"gamemode {mode} {target}")


@mcp.tool()
def setblock(x: int, y: int, z: int, block: str, mode: str = "replace") -> str:
    """`/setblock X Y Z BLOCK [MODE]`. BLOCK accepts blockstates and NBT."""
    return _run(f"setblock {x} {y} {z} {block} {mode}")


@mcp.tool()
def summon(entity: str, x: float, y: float, z: float, nbt: str = "") -> str:
    """`/summon ENTITY X Y Z [NBT]`. NBT is SNBT, e.g. `{NoAi:1b}`."""
    suffix = f" {nbt}" if nbt else ""
    return _run(f"summon {entity} {x} {y} {z}{suffix}")


@mcp.tool()
def bridge_ping() -> dict | str:
    """Round-trip sanity check against the Java JSON-RPC bridge (:25580)."""
    return _bridge_call("ping")


@mcp.tool()
def airship_brain_state(lever_x: int | None = None, lever_y: int | None = None,
                        lever_z: int | None = None) -> dict | str:
    """Snapshot of all registered airships (or one if lever pos given).

    Returns per ship: state, ticksInState, kind, pos, linearVelocity, angularVelocity,
    orientation (quat), mass, balloonCapacity, plateauRows, lastGoal, lastHeadingErrDeg,
    steadyTicks, liftoffStartY, throttle/burner/clutch counts, cannoneers, etc.
    """
    params: dict = {}
    if lever_x is not None and lever_y is not None and lever_z is not None:
        params["lever"] = [lever_x, lever_y, lever_z]
    return _bridge_call("airship_brain_state", params)


@mcp.tool()
def sublevel_inspect(uuid: str) -> dict | str:
    """Inspect a Sable SubLevel by UUID across all dims.

    Returns pos, orientation, linearVelocity, angularVelocity, mass, entityCount.
    """
    return _bridge_call("sublevel_inspect", {"uuid": uuid})


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
