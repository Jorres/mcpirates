#!/usr/bin/env bash
# Linux variant of dev_restart.sh. See that file for the full design rationale
# (single-instance lock, RCON-as-readiness-signal, single READY line on stdout).
#
# Only the JVM-killing stanza differs: Linux `pgrep -f` / `pkill -f` can see
# the full Java argv, so we match on argfile names directly instead of going
# through PowerShell + Win32_Process.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mkdir -p runs/server
LOCK_DIR=runs/server/.dev_restart.lock
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "[restart] another dev_restart_linux.sh is already running (lock held)" >&2
    echo "[restart] if you believe it's stuck, rmdir $LOCK_DIR" >&2
    exit 2
fi
trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT

WIPE=0
[ "${1:-}" = "--wipe" ] && WIPE=1

rcon_open() {
    python3 -c "import socket; s=socket.socket(); s.settimeout(0.5); s.connect(('localhost',25575)); s.close()" 2>/dev/null
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

# Kill prior MC client/server JVMs and lingering gradle wrappers. pgrep -f sees
# full argv on Linux, so we match the argfile names neoforge launches with,
# plus any gradle wrapper still running runClient*/runServer.
echo "[restart] killing prior MC client/server JVMs"
PATTERNS=(
    'clientRunVmArgs\.txt'
    'clientQuickRunVmArgs\.txt'
    'serverRunVmArgs\.txt'
)
for p in "${PATTERNS[@]}"; do
    for pid in $(pgrep -f "$p" 2>/dev/null || true); do
        echo "[restart]   killing pid=$pid (matched $p)"
        kill "$pid" 2>/dev/null || true
    done
done
for pid in $(pgrep -f 'gradle-wrapper\.jar.*\(runClient\(Quick\)\?\|runServer\)' 2>/dev/null || true); do
    echo "[restart]   killing gradle wrapper pid=$pid"
    kill "$pid" 2>/dev/null || true
done
sleep 1

if [ "$WIPE" -eq 1 ]; then
    echo "[restart] wiping runs/server/world"
    rm -rf runs/server/world
fi

echo "[restart] launching server"
nohup ./gradlew runServer >/dev/null 2>&1 &
SERVER_PID=$!

wait_until rcon_open 180 "RCON to open"
sleep 2
echo "[restart] server RCON open (pid=$SERVER_PID)"

echo "[restart] launching client"
nohup ./gradlew runClientQuick >/dev/null 2>&1 &
CLIENT_PID=$!

echo "[restart] READY (server pid=$SERVER_PID client pid=$CLIENT_PID)"
