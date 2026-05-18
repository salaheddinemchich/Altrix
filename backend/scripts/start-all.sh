#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start-all.sh — one-shot: infrastructure + application services
#
# Equivalent to running:
#   ./scripts/start-dev.sh [infra-flags]  &&  ./scripts/start-services.sh [svc-flags]
#
# Infrastructure flags (forwarded to start-dev.sh):
#   --minimal    Start only core infra (Postgres, Redis, Kafka, MinIO + UIs).
#                Recommended on Windows / low-RAM machines.
#   --no-sonar   Skip SonarQube + DefectDojo, still starts PubSub emulator.
#
# Service flags (forwarded to start-services.sh):
#   --no-frontend        Skip Angular ng serve.
#   --only <service>     Start only one service: project|job|orchestrator|frontend
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Partition flags into infra vs service buckets ────────────────────────────
DEV_ARGS=()
SVC_ARGS=()
EXPECT_ONLY_VALUE=false

for arg in "$@"; do
  if [ "$EXPECT_ONLY_VALUE" = true ]; then
    SVC_ARGS+=("$arg")
    EXPECT_ONLY_VALUE=false
    continue
  fi
  case "$arg" in
    --minimal|--no-sonar) DEV_ARGS+=("$arg") ;;
    --no-frontend)        SVC_ARGS+=("$arg") ;;
    --only)               SVC_ARGS+=("$arg"); EXPECT_ONLY_VALUE=true ;;
  esac
done

echo "▸ Stage 1/2 — Infrastructure"
"$SCRIPT_DIR/start-dev.sh" "${DEV_ARGS[@]+"${DEV_ARGS[@]}"}"

echo ""
echo "▸ Stage 2/2 — Application services"
"$SCRIPT_DIR/start-services.sh" "${SVC_ARGS[@]+"${SVC_ARGS[@]}"}"
