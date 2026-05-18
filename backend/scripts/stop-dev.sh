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
warn() { echo -e "${YELLOW}  ! $*${NC}"; }
hdr()  { echo -e "\n${YELLOW}═══ $* ═══${NC}"; }

# ── Cross-platform helpers ─────────────────────────────────────────────────────

# True when running inside Git Bash / MSYS2 on Windows
is_windows() {
  [[ "$OSTYPE" == "msys"* ]] || [[ "$OSTYPE" == "cygwin"* ]] || [[ "${OS:-}" == "Windows_NT" ]]
}

# Return PIDs listening on a TCP port.
pids_on_port() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$port" 2>/dev/null || true
  else
    netstat -ano 2>/dev/null \
      | awk -v p=":${port}" '$2 ~ (p"$") && $4 == "LISTENING" { print $NF }' \
      | sort -u || true
  fi
}

# Kill processes matching a command-line pattern.
# Uses pkill on Linux/macOS. On Windows Git Bash pkill is absent — silently
# skips (port-based kills above already handle the main processes).
kill_by_pattern() {
  local pattern="$1"
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "$pattern" 2>/dev/null || true
  else
    warn "pkill unavailable — skipping pattern kill: $pattern"
  fi
}

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
  pids=$(pids_on_port "$port")
  if [ -n "$pids" ]; then
    echo "  $name (:$port) — pids: $pids"
    if is_windows; then
      # Windows: taskkill sends WM_CLOSE first (graceful), then /F forces
      for pid in $pids; do
        taskkill //PID "$pid" 2>/dev/null || true
      done
      sleep 2
      pids=$(pids_on_port "$port")
      for pid in $pids; do
        taskkill //F //PID "$pid" 2>/dev/null || true
      done
    else
      # shellcheck disable=SC2086
      kill -SIGTERM $pids 2>/dev/null || true
      sleep 2
      pids=$(pids_on_port "$port")
      if [ -n "$pids" ]; then
        # shellcheck disable=SC2086
        kill -SIGKILL $pids 2>/dev/null || true
      fi
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
kill_by_pattern "platform-project:bootRun"
kill_by_pattern "platform-job:bootRun"
kill_by_pattern "platform-orchestrator:bootRun"
kill_by_pattern "ng serve"
kill_by_pattern "@angular/cli/bin/ng"
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
