# Behavior

You MUST use concise responses without "Ah, you are correct", "Good observation!" and similar.

You MUST NOT be lazy and make shortcuts, especially ones that introduce entropy. E.g. once you tried to programmatically clean chunks of Neoforge GameTest because you couldn't configure it to spawn superflat.
Such behavior is PROHIBITED.

Do not be overly verbose writing code commentaries or docs.

At all times remember - you have access to downloaded source code of your dependencies, and you always can search 
the internet for docs on NeoForge. 

Backwards compatibility does not exist. We are developing the mod and have no clients. Better ways to do stuff means 
earlier stuff gets brutally deleted.

# mcpirates — Claude orientation

NeoForge addon for Create Aeronautics: pirate airships at pillager outposts,
sheriff villager bounty board in villages. See `docs/decisions.md` (authoritative
architecture log) and `docs/design.md` (high-level intent).

## Iterating with the running game

You may launch / restart both the dev server and client yourself via helper script tools/dev_restart.sh.

If necessary you may launch processes directly:
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

Standard restart sequence after a Java/datapack change — **use the script,
don't open-code it**:

1. `cmd("stop")` over RCON (or kill the background `runServer`).
2. `Bash("./tools/dev_restart.sh", run_in_background=true)` — add `--wipe` if
   the change is worldgen-affecting and the user hasn't been building in the
   dev world. The script kills stale client / gradle JVMs, polls the RCON
   port directly (not log lines, which suffer from stale `Dev joined the
   game` hits from earlier sessions), launches server, waits for RCON open,
   launches client, and prints `[restart] READY` when done.
3. Wait for Dev to join. The client window takes ~10-30 s to render + join
   after gradle starts it; the script does NOT wait for the player to
   actually connect, only for RCON to come up.
   - **Call `mcp__minecraft__players` first, in the SAME turn as the post-
     READY check.** If "Dev" is listed → proceed. Cost: one tool call.
   - **Don't reach for `ScheduleWakeup` as a first move.** It has a 60-second
     minimum delay — using it before even checking `players()` once burns at
     least a minute of wall-clock when the join probably already happened.
     Schedule a wake-up only if `players()` returned empty AND you have
     nothing else productive to do this turn.
   - **`mcp__minecraft__grep_log` for `Dev joined the game`** is the
     authoritative second-precision signal. Use this if `players()` is empty
     but the timing looks suspicious (maybe Dev joined a moment ago and the
     RCON `list` cache is stale).
   - **Don't fake the wait with shell tricks.** `curl http://localhost:25575`,
     `nc -w 1 localhost 25575`, etc. cannot speak Source RCON (binary protocol
     with handshake/auth) and silently return nothing useful.
4. Then test.

A `minecraft` MCP server is there for this repo. When the
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

## Validating changes during iteration

For changes that touch any of:

- `airship/` — assembly, liftoff trigger, brain, kind dispatch, ground combat
- `pirates/` — defender spawning, captain death, ground combat module
- `LiftMath`, `HotAirBurners`, `ThrottleLevers`, `ClutchLevers`
- The mixin layer (`mixin/`)

run the gametest suite as the regression check:

```
./gradlew runGameTestServer
```

It takes ~30 s cold, ~5 s warm cache, and runs 6 tests covering buoyancy
(`airshipSmallRisesUnderBuoyancy`), assembly + actuation across kinds and
rotations, ground combat spawn, and the on-foot retreat → DORMANT → air
arrival lifecycle. All tests are sequential (each in its own batch), isolated
(arenas ~200 blocks apart via `StructureGridSpawnerMixin`), and the gametest
world is wiped before every run via `build.gradle`'s `doFirst` hook — no
state leaks between runs.

Failing tests print to console with a `LogTestReporter` line; the per-tick
state of each ship is in `runs/gametest/logs/latest.log`.

The full dev-server loop (`runServer` + `runClientQuick`) remains the right
choice for behaviour the gametest suite doesn't cover (worldgen, the
proximity-trigger event path with a real `ServerPlayer`, anything visual). Use
both — gametest for fast regression, dev-server for end-to-end. **A change
that compiles is not validated until at least one of the two has been run.**

## Hard constraints

- **Working dir:** `mcpirates/`. The parent `aero/` is the user's live
  `.minecraft`. Never write outside `mcpirates/`.
- **Pinned versions:** see `gradle.properties`. NeoForge **21.1.228**,
  Lithostitched **1.7.0**
