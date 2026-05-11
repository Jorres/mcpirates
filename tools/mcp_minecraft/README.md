# mcp_minecraft

MCP server that lets Claude Code drive the running mcpirates dev server via RCON
+ log scraping. Project-scoped — only loads when Claude is invoked from this
repo.

## One-time setup

1. Install the MCP Python SDK:
   ```
   py -m pip install -r tools/mcp_minecraft/requirements.txt
   ```

2. Accept the EULA on first server boot. Run `./gradlew runServer` once; it
   will exit with `You need to agree to the EULA` and write `runs/server/eula.txt`.
   Set `eula=true` in that file and re-run.

3. RCON is already enabled in `runs/server/server.properties` (port 25575,
   password `dev`). Change them there if you need to.

## Daily use

1. Start the dev server: `./gradlew runServer`. Wait for `RCON running on
   0.0.0.0:25575` in the log.
2. (Optional) Start a separate `./gradlew runClient` and connect to
   `localhost`. Game keeps ticking even when its window isn't focused.
3. Open Claude Code from this repo. The `minecraft` MCP server loads from
   `.mcp.json` automatically — approve it the first time.

## Tools

All wrap one of: RCON command, `runs/server/logs/latest.log` read.

| tool | purpose |
| --- | --- |
| `cmd(command)` | raw server command, returns response text |
| `tail_log(lines=200)` | last N lines of latest.log |
| `grep_log(pattern, max_matches=100, context=0)` | regex over latest.log |
| `read_block(x, y, z)` | full SNBT for block + BE at coord |
| `read_block_path(x, y, z, path)` | one NBT path on the BE |
| `read_entities(selector, path="")` | `/data get entity` |
| `locate_structure(name)` | `/locate structure` |
| `tp(x, y, z, target="@s", yaw?, pitch?)` | teleport |
| `players()` | `/list` |
| `time_set(value)`, `gamemode(mode, target)` | conveniences |
| `setblock`, `fill`, `summon` | mutations |

## When to extend

Add a tool here whenever you would otherwise have to copy/paste several
commands or parse free text. Examples that should become tools when the need
arises: `find_airship_levers(radius)`, `assert_block_at(x,y,z, expected)`,
`reset_test_world()`. Keep them narrow — one purpose each.
