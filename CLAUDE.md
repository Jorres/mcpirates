# mcpirates — Claude orientation

NeoForge addon for Create Aeronautics: pirate airships at pillager outposts,
sheriff villager bounty board in villages. See `docs/decisions.md` (authoritative
architecture log) and `docs/design.md` (high-level intent).

## Iterating with the running game

A `minecraft` MCP server is wired up via `.mcp.json` for this repo. When the
user has `./gradlew runServer` running, you can call:

- `cmd(...)` — any server command via RCON
- `tail_log(N)` / `grep_log(regex)` — read latest.log
- `read_block(x,y,z)` / `read_block_path(...)` / `read_entities(selector,path)`
- `locate_structure(name)`, `tp(x,y,z, target=...)`, `players()`
- `setblock`, `fill`, `summon`, `time_set`, `gamemode`

**Read [docs/claude-bridge.md](docs/claude-bridge.md) before using these** —
RCON has no executor (`@s` doesn't work), output is plain text needing parsing,
and there's no live block-scan tool yet. The doc covers selector gotchas,
common patterns, and when to escalate to a Java-side RPC instead.

If RCON tools return `[rcon error: ...]`, the dev server isn't running. Ask the
user to start it.

## Hard constraints

- **Working dir:** `mcpirates/`. The parent `aero/` is the user's live
  `.minecraft`. Never write outside `mcpirates/`.
- **Pinned versions:** see `gradle.properties`. NeoForge **21.1.228**,
  Lithostitched **1.7.0** (NOT 1.7.3 — crashes Create's Registrate).
- **NBT regen:** the structure-import scripts read from the saved-world
  `generated/minecraft/structures/<name>.nbt` and write to the resources tree.
  - `tools/build_outpost_pieces.py` (airship/outpost base plate) — running
    against the *committed* `airship_small.nbt` re-wraps it (not idempotent).
    Pass `--airship` pointing at a fresh structure-block save instead.
  - `tools/import_sheriff_station.py` (sheriff station building) — idempotent
    (strips existing jigsaws before stamping). Override entrance via
    `--jigsaw-pos X Y Z --jigsaw-orientation <face>_up` when the layout changes.
