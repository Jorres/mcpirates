#!/usr/bin/env bash
# Orchestrated dev server + client restart.
#
# Why this exists: previously each restart spawned 3 parallel waiters
# (boot-server-bg + wait-RCON-bg + boot-client-bg + wait-Dev-join-bg) that all
# log-grepped runs/server/logs/latest.log. Stale "Dev joined the game" lines
# from earlier sessions made waiters fire prematurely, leaving Claude with a
# wrong belief about server state. This script collapses the whole sequence
# into one bg task that polls RCON directly (deterministic up/down signal),
# emits a single "READY" line, and exits.
#
# Contract:
#   - Caller (Claude) sends /stop via MCP RCON if a server is up; this script
#     then waits up to 30s for RCON to close before relaunching.
#   - Single-instance enforced via flock on runs/server/.dev_restart.lock — a
#     parallel invocation exits with code 2 instead of racing.
#   - --wipe deletes runs/server/world (ops.json + server.properties stay).
#   - Stdout: one "[restart] ..." line per stage + final "READY" on success.
#     Stderr: gradle output (suppressed; redirect to /dev/null).
#   - Exits 0 on READY, 1 on timeout (180s), 2 on lock contention.
#
# Caller's job after this exits:
#   - Poll cmd("list") via the minecraft MCP until "Dev" appears. The client
#     window takes a few seconds to render + join after gradle starts it.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Single-instance lock. A second concurrent dev_restart.sh would race for the
# RCON port, kill the first instance's JVMs mid-boot, and leave the user with
# two MC client windows. mkdir is atomic on every POSIX-ish shell (including
# Git Bash for Windows, which has no flock).
mkdir -p runs/server
LOCK_DIR=runs/server/.dev_restart.lock
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "[restart] another dev_restart.sh is already running (lock held)" >&2
    echo "[restart] if you believe it's stuck, rmdir $LOCK_DIR" >&2
    exit 2
fi
trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT

WIPE=0
[ "${1:-}" = "--wipe" ] && WIPE=1

rcon_open() {
    python -c "import socket; s=socket.socket(); s.settimeout(0.5); s.connect(('localhost',25575)); s.close()" 2>/dev/null
}

wait_until() {
    local cond=$1 deadline=$(( $(date +%s) + ${2:-120} )) desc=${3:-condition}
    until eval "$cond"; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "[restart] TIMEOUT waiting for $desc" >&2
            exit 1
        fi
        sleep 1
    done
}

# 1. If RCON is currently reachable, the previous server is still in the
#    shutdown window — give it up to 30s to drop the listener. Saves the caller
#    from having to chain a "wait for stop" loop themselves (which historically
#    they got wrong by grepping persistent log lines).
if rcon_open; then
    echo "[restart] waiting for previous server to close RCON"
    deadline=$(( $(date +%s) + 30 ))
    while rcon_open; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "[restart] RCON still open after 30s — send /stop first" >&2
            exit 1
        fi
        sleep 1
    done
fi

# 2. Kill any lingering Minecraft client JVMs from prior runs.
#
# Why PowerShell-based, not pkill: the actual MC client JVM doesn't have
# "net.minecraft.client.main.Main" in its command line — neoforge launches it
# via an `@clientQuickRunVmArgs.txt` argfile, so the main-class name lives in
# a file MC reads at startup, not on argv. The reliable signature in argv is
# the argfile path itself. pkill -f on Git Bash for Windows can't see full
# Java command lines anyway, so we go straight to PowerShell + Win32_Process.
#
# We kill BOTH: the running client JVM (clientQuickRunVmArgs.txt in argv) AND
# any leftover gradlew wrapper JVMs from prior `runClientQuick` invocations,
# which otherwise pile up as zombie launchers each restart.
echo "[restart] killing prior MC client JVMs"
powershell.exe -NoProfile -Command "
    Get-CimInstance Win32_Process | Where-Object {
        \$_.CommandLine -and (
            \$_.CommandLine -like '*clientQuickRunVmArgs.txt*' -or
            \$_.CommandLine -like '*serverRunVmArgs.txt*' -or
            (\$_.CommandLine -like '*gradle-wrapper.jar*' -and (
                \$_.CommandLine -like '*runClientQuick*' -or
                \$_.CommandLine -like '*runServer*'
            ))
        )
    } | ForEach-Object {
        Write-Host ('[restart]   killing pid=' + \$_.ProcessId + ' (' + \$_.Name + ')')
        Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue
    }
" 2>/dev/null || true

# 3. Optional world wipe.
if [ "$WIPE" -eq 1 ]; then
    echo "[restart] wiping runs/server/world"
    rm -rf runs/server/world
fi

# 4. Boot server. We run gradle in the background; its stdout/stderr go
#    to /dev/null since we're not log-grepping anymore.
echo "[restart] launching server"
nohup ./gradlew runServer >/dev/null 2>&1 &
SERVER_PID=$!

# 5. Wait until RCON answers (server fully booted).
wait_until rcon_open 180 "RCON to open"
sleep 2   # tiny grace for RCON's first-command jitter
echo "[restart] server RCON open (pid=$SERVER_PID)"

# 6. Boot client.
echo "[restart] launching client"
nohup ./gradlew runClientQuick >/dev/null 2>&1 &
CLIENT_PID=$!

echo "[restart] READY (server pid=$SERVER_PID client pid=$CLIENT_PID)"
