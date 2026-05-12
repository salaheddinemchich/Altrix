#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start-all.sh — one-shot: infrastructure + application services
#
# Equivalent to running:
#   ./scripts/start-dev.sh   &&   ./scripts/start-services.sh
#
# Pass --no-frontend to skip Angular ng serve.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "▸ Stage 1/2 — Infrastructure"
"$SCRIPT_DIR/start-dev.sh"

echo ""
echo "▸ Stage 2/2 — Application services"
"$SCRIPT_DIR/start-services.sh" "$@"
