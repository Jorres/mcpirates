# Claude ↔ Minecraft bridge

A project-scoped MCP server (`tools/mcp_minecraft/`) gives Claude live access
to the running mcpirates dev server: RCON commands, log scraping, block/entity
inspection. Loaded automatically from `.mcp.json` when Claude is invoked from
this repo.

**Two `.mcp.json` locations** (keep in sync):
- `aero/.mcp.json` — `cwd="mcpirates/tools"`. Used when Claude is launched
  from the parent `aero/` dir (the common case).
- `mcpirates/.mcp.json` — `cwd="tools"`. Used when Claude is launched from
  inside `mcpirates/`.

Same `mcpServers.minecraft` block in both, just a different relative `cwd`.
Edit one → mirror the change to the other.

## The tools, and when to reach for each

| tool | use when |
| --- | --- |
| `cmd(command)` | the obvious thing is a one-off `/`-command |
| `tail_log(lines)` | "what just happened?" |
| `grep_log(pattern, max, context)` | hunting a specific log line by regex |
| `read_block(x,y,z)` | full SNBT for a block + its BE |
| `read_block_path(x,y,z, path)` | one field — e.g. analog lever `State` |
| `read_entities(selector, path)` | `/data get entity` — see selector caveats below |
| `locate_structure(name)` | `locate` then parse coords from the response |
| `tp(x,y,z, target=..., yaw=, pitch=)` | move someone — `target` is required, see below |
| `players()` | who's online |
| `setblock`, `fill`, `summon` | mutations |
| `time_set`, `gamemode` | environment knobs |

## RCON-specific gotchas

- **`@s` does not work** over RCON — there's no executor. Every selector-based
  call needs a real target (a player name, `@p`, `@a`, `@e[...]`). The `tp`
  tool defaults to `@s` for ergonomics — you must override `target=` when
  calling it from Claude over RCON.
- **`@p` is "closest player to RCON's notional origin"** which is world spawn
  unless wrapped in `execute at ...`. For "teleport the human to here", grab
  their name with `players()` first and pass it as `target`.
- **No tab completion / quoting help.** Pass commands exactly as they would
  appear in the chat box, minus the leading `/`.
- **Output is plain text.** `locate_structure` returns something like
  `The nearest minecraft:pillager_outpost is at [123, ~, -456] (789 blocks
  away)` — parse with a regex if you need numeric coords.
- **Mutations don't always echo.** A successful `setblock` returns
  `Changed the block at X, Y, Z` but lots of commands return empty on
  success. Don't treat empty as failure.

## Headless server == only one player at a time, and not always you

The user typically connects a separate `./gradlew runClient` to `localhost`.
If they haven't, `players()` is empty and `tp` against a player name will
fail. Use `players()` to confirm before assuming a target exists.

## Reading vs scanning

`read_block` / `read_entities` are point-lookups. There is **no scan tool**
yet. To find every analog lever in a chunk you have two choices:

1. Iterate coordinates in Python by issuing many `read_block` calls — slow
   but works.
2. Use `/execute as @e[type=...] at @s run data get entity @s` patterns via
   `cmd(...)` for entity sweeps.

If you find yourself doing #1 a lot, that's the signal to add a Tier 2 Java
RPC (see "Beyond Tier 1" below).

## When a command needs a position context

Many commands implicitly use the executor's position (`fill ~ ~ ~ ...`).
RCON has no position. Either pass absolute coords or wrap in
`execute positioned X Y Z run ...` / `execute at PLAYER run ...`.

## Beyond Tier 1

The deferred Tier 2 is a dev-only Java module (`com.mcpirates.dev.ClaudeBridge`)
that exposes things commands can't reach: scan-style queries, `AirshipBrain`
state snapshots, `SubLevel` introspection, event taps, screenshots. Build it
incrementally — only when you've hit a wall a command can't solve. Do NOT
preemptively design it. See `MEMORY.md` → mcpirates Claude/MC bridge for the
deferred-feature checklist.

## Files involved

- [.mcp.json](../.mcp.json) — server registration
- [tools/mcp_minecraft/server.py](../tools/mcp_minecraft/server.py) — tool definitions
- [tools/mcp_minecraft/rcon.py](../tools/mcp_minecraft/rcon.py) — RCON client
- [runs/server/server.properties](../runs/server/server.properties) — RCON port/password
- [runs/server/eula.txt](../runs/server/eula.txt) — accepted; flip to false to disable
