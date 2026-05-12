# mcpirates — Claude orientation

NeoForge addon for Create Aeronautics: pirate airships at pillager outposts,
sheriff villager bounty board in villages. See `docs/decisions.md` (authoritative
architecture log) and `docs/design.md` (high-level intent).

## Iterating with the running game

You may launch / restart both the dev server and client yourself via Gradle:

- `./gradlew runServer` — boots the dev server.
- `./gradlew runClientQuick` — boots the dev client AND auto-joins
  `localhost:25565` via `--quickPlayMultiplayer` (see
  `build.gradle:93` — the `clientQuick` run config). Always use this, not
  `runClient` — the test loop skips the title + multiplayer menu, and the
  Claude-bridge MCP needs a player in the world to drive selectors.
- Background both via the Bash `run_in_background` flag; they block until killed.
- `cmd("stop")` over RCON cleanly shuts the server. Wait a few seconds before
  relaunching so the world lock file is released.

**Two hard requirements before testing — DO NOT skip:**

1. **Wipe the world between sessions.** `rm -rf runs/server/world` before each
   test that involves worldgen (outpost / galleon placement, biome filters,
   structure tags). Worldgen-affecting changes (Java structure types, biome
   tags, structure JSONs) only take effect on chunks generated AFTER the
   change — existing chunks were generated under the OLD config. Without a
   wipe you'll get phantom "the change didn't apply" results from stale
   chunks. PRESERVE `runs/server/ops.json` (Dev must stay opped at level 4 —
   see the `feedback_dev_server_ops.md` memory).
   **Exception:** if the user has been *building something* in the dev world
   recently (a ship in shipyard, a town, hand-placed test scenery they want
   to keep iterating on), do NOT wipe — ask first. The wipe is for
   worldgen-correctness testing, not a default cleanup.
2. **Run the client.** RCON-only "tests" prove the datapack + Java compile,
   not gameplay. Trigger-fired behavior (lift-off, brain ticking, jigsaw
   placement) requires a player in chunks. Missing-client failures will
   mislead you. Always launch `runClientQuick` and wait until `players()`
   lists "Dev" before commanding tests.

Standard restart sequence after a Java/datapack change:
1. `cmd("stop")` over RCON (or kill the background `runServer`).
2. `rm -rf runs/server/world` (preserve `ops.json`).
3. `Bash("./gradlew runServer", run_in_background=true)`.
4. Wait for `RCON running on 0.0.0.0:25575` in `runs/server/logs/latest.log`.
5. `Bash("./gradlew runClientQuick", run_in_background=true)`.
6. Poll `players()` until it lists "Dev".
7. Then test.

A `minecraft` MCP server is wired up via `.mcp.json` for this repo. When the
server is running, you can call:

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
- **NBT regen:** the structure-import scripts read raw ships from `tools/sources/`
  and write everything to the resources tree.
  - `tools/build_ships.py` (all airship resources, plus base_plate_with_airship)
    — single pipeline driven by the `SHIPS` config dict at the top of the file.
    Idempotent: strips any pre-existing keel jigsaws before injecting fresh ones.
    Auto-backs up `src/.../structure/*.nbt` to `tools/backups/<timestamp>/`. Run
    with no args to rebuild everything, `--ship NAME` to rebuild one.
    To update a ship: re-save it in dev MC via structure block, copy the result
    from `saves/<world>/generated/minecraft/structures/<name>.nbt` to
    `tools/sources/<name>.nbt`, then re-run.
  - `tools/import_sheriff_station.py` (sheriff station building) — idempotent
    (strips existing jigsaws before stamping). Override entrance via
    `--jigsaw-pos X Y Z --jigsaw-orientation <face>_up` when the layout changes.
