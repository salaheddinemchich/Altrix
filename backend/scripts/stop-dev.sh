#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stop-dev.sh — gracefully stop everything (frontend + backends + Docker)
#
# Safe to run even when nothing is up. Idempotent.
#
# Usage:
#   ./scripts/stop-dev.sh                # full stop
#   ./scripts/stop-dev.sh --keep-docker  # stop apps only, leave Docker running
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$ROOT/logs"
cd "$ROOT"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✓ $*${NC}"; }
hdr()  { echo -e "\n${YELLOW}═══ $* ═══${NC}"; }

KEEP_DOCKER=false
for arg in "$@"; do
  case "$arg" in
    --keep-docker) KEEP_DOCKER=true ;;
  esac
done

# ── 1. Kill anything bound to our application ports ──────────────────────────
hdr "Stopping application processes"

stop_port() {
  local name="$1" port="$2"
  local pids
  pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "  $name (:$port) — pids: $pids"
    # shellcheck disable=SC2086
    kill -SIGTERM $pids 2>/dev/null || true
    sleep 2
    # If still alive, force-kill
    pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
      # shellcheck disable=SC2086
      kill -SIGKILL $pids 2>/dev/null || true
    fi
    ok "$name stopped"
  else
    ok "$name (:$port) — nothing running"
  fi
}

stop_port "platform-project"     8082
stop_port "platform-job"          8083
stop_port "platform-orchestrator" 8084
stop_port "frontend (ng serve)"   4200

# ── 2. Sweep any leftover bootRun / ng-serve / Gradle processes by name ──────
hdr "Sweeping stray Gradle / Node processes"
pkill -f "platform-project:bootRun"      2>/dev/null || true
pkill -f "platform-job:bootRun"          2>/dev/null || true
pkill -f "platform-orchestrator:bootRun" 2>/dev/null || true
pkill -f "ng serve"                      2>/dev/null || true
pkill -f "@angular/cli/bin/ng"           2>/dev/null || true
ok "named processes terminated"

# ── 3. Tell Gradle to release file locks ─────────────────────────────────────
"$ROOT/gradlew" --stop --project-dir "$ROOT" > /dev/null 2>&1 || true
ok "Gradle daemons stopped"

# ── 4. Remove stale PID files (logs are kept for debugging) ──────────────────
if [ -d "$LOG_DIR" ]; then
  rm -f "$LOG_DIR"/*.pid 2>/dev/null || true
  ok "stale PID files cleared (logs preserved in $LOG_DIR/)"
fi

# ── 5. Docker compose ────────────────────────────────────────────────────────
if [ "$KEEP_DOCKER" = true ]; then
  hdr "Skipping Docker (--keep-docker)"
  ok "Docker containers left running"
else
  hdr "Stopping Docker containers"
  docker compose down
  ok "all containers stopped"
fi

echo ""
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN} Everything shut down${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
