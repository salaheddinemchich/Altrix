#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start-services.sh — boot all application services (3 backend + frontend)
#
# Reads .env from backend/.env (auto-loaded into each bootRun via Gradle).
# Backend services run via Gradle bootRun in the background; logs land under
# backend/logs/*.log and PIDs under backend/logs/*.pid. The frontend (ng serve)
# is optional — pass --no-frontend to skip it.
#
# Prerequisite: docker compose stack must already be up. Run start-dev.sh
# first, or use start-all.sh which chains infra + services.
#
# Usage:
#   ./scripts/start-services.sh                  # backend + frontend
#   ./scripts/start-services.sh --no-frontend    # backend only
#   ./scripts/start-services.sh --only project   # one service (project|job|orchestrator|frontend)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/.." && pwd)"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"
cd "$ROOT"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✓ $*${NC}"; }
warn() { echo -e "${YELLOW}  ! $*${NC}"; }
fail() { echo -e "${RED}  ✗ $*${NC}"; }
hdr()  { echo -e "\n${YELLOW}═══ $* ═══${NC}"; }

# ── Args ──────────────────────────────────────────────────────────────────────
NO_FRONTEND=false
ONLY=""
for arg in "$@"; do
  case "$arg" in
    --no-frontend) NO_FRONTEND=true ;;
    --only)        ;;
    project|job|orchestrator|frontend) ONLY="$arg" ;;
  esac
done

# ── Pre-flight ────────────────────────────────────────────────────────────────
hdr "Pre-flight"
if ! docker ps --format '{{.Names}}' | grep -q '^altrix-postgres$'; then
  fail "Infrastructure not running. Run ./scripts/start-dev.sh first."
  exit 1
fi
ok "altrix-postgres up"

if ! docker ps --format '{{.Names}}' | grep -q '^altrix-kafka$'; then
  fail "Kafka not running. Run ./scripts/start-dev.sh first."
  exit 1
fi
ok "altrix-kafka up"

# ── Load .env so PATH / NODE_OPTIONS etc. propagate to the launched processes ─
if [ -f "$ROOT/.env" ]; then
  set -a && source "$ROOT/.env" && set +a
  ok ".env loaded"
fi

# ── Service launcher ──────────────────────────────────────────────────────────
# bootRun already auto-loads .env via each module's build.gradle.kts.
start_backend() {
  local name="$1"      # e.g. platform-orchestrator
  local port="$2"
  local log="$LOG_DIR/${name}.log"
  local pidf="$LOG_DIR/${name}.pid"

  # If already running on the port, skip
  if lsof -ti tcp:"$port" > /dev/null 2>&1; then
    warn "$name already running on :$port — skipping"
    return 0
  fi

  echo "  Starting $name on :$port (log: $log)"
  nohup "$ROOT/gradlew" --no-daemon ":${name}:bootRun" \
    > "$log" 2>&1 &
  echo $! > "$pidf"
  ok "$name pid=$(cat "$pidf")"
}

wait_for_http() {
  local name="$1" url="$2" max="${3:-90}"
  for i in $(seq 1 "$max"); do
    if curl -sf -o /dev/null "$url"; then
      ok "$name is UP ($url)"
      return 0
    fi
    printf "  waiting for %s (%d/%d)...\r" "$name" "$i" "$max"
    sleep 5
  done
  echo ""
  fail "$name did not respond at $url within $((max * 5))s — check log"
  return 1
}

start_frontend() {
  local fe="$REPO_ROOT/frontend"
  if [ ! -d "$fe" ]; then
    warn "Frontend folder not found at $fe — skipping"
    return 0
  fi
  if lsof -ti tcp:4200 > /dev/null 2>&1; then
    warn "Something is already on :4200 — skipping frontend"
    return 0
  fi
  local log="$LOG_DIR/frontend.log"
  local pidf="$LOG_DIR/frontend.pid"
  echo "  Starting Angular dev server on :4200 (log: $log)"
  (cd "$fe" && nohup npx ng serve --host 0.0.0.0 --port 4200 > "$log" 2>&1 &
   echo $! > "$pidf")
  ok "frontend pid=$(cat "$pidf")"
}

# ── Launch ────────────────────────────────────────────────────────────────────
hdr "Launching application services"

run_all=true
[ -n "$ONLY" ] && run_all=false

if [ "$run_all" = true ] || [ "$ONLY" = "orchestrator" ]; then
  start_backend "platform-orchestrator" 8084
fi
if [ "$run_all" = true ] || [ "$ONLY" = "project" ]; then
  start_backend "platform-project" 8082
fi
if [ "$run_all" = true ] || [ "$ONLY" = "job" ]; then
  start_backend "platform-job" 8083
fi
if { [ "$run_all" = true ] && [ "$NO_FRONTEND" = false ]; } || [ "$ONLY" = "frontend" ]; then
  start_frontend
fi

# ── Wait for health ───────────────────────────────────────────────────────────
hdr "Waiting for services to be ready"
if [ "$run_all" = true ] || [ "$ONLY" = "orchestrator" ]; then
  wait_for_http "platform-orchestrator" "http://localhost:8084/actuator/health" || true
fi
if [ "$run_all" = true ] || [ "$ONLY" = "project" ]; then
  wait_for_http "platform-project"      "http://localhost:8082/actuator/health" || true
fi
if [ "$run_all" = true ] || [ "$ONLY" = "job" ]; then
  wait_for_http "platform-job"          "http://localhost:8083/actuator/health" || true
fi
if { [ "$run_all" = true ] && [ "$NO_FRONTEND" = false ]; } || [ "$ONLY" = "frontend" ]; then
  wait_for_http "frontend"              "http://localhost:4200"                 || true
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN} Application services ready${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${BLUE}  Frontend             ${NC} http://localhost:4200"
echo -e "${BLUE}  platform-orchestrator${NC} http://localhost:8084   (API + WebSocket)"
echo -e "${BLUE}  platform-project     ${NC} http://localhost:8082"
echo -e "${BLUE}  platform-job         ${NC} http://localhost:8083"
echo ""
echo "  Tail logs:  tail -f $LOG_DIR/*.log"
echo "  Stop all:   ./scripts/stop-dev.sh"
echo ""
