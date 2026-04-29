#!/usr/bin/env bash
set -euo pipefail

MIGRATOR_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$MIGRATOR_DIR"

echo "=== Loading .env ==="
set -a && source "$MIGRATOR_DIR/.env" && set +a

SONAR_HOST="${SONAR_HOST_URL:-http://localhost:9003}"

if [ -z "${SONAR_TOKEN:-}" ]; then
  echo "ERROR: SONAR_TOKEN is not set in .env"
  exit 1
fi

echo ""
echo "=== Waiting for SonarQube at $SONAR_HOST ==="
until curl -sf "$SONAR_HOST/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; do
  printf "."; sleep 5
done
echo "  SonarQube ready"

echo ""
echo "=== Generating coverage reports ==="
./gradlew test jacocoTestReport --no-daemon -q
echo "  Done"

echo ""
echo "=== Sending analysis to SonarQube ==="
docker run --rm \
  --network=host \
  -e SONAR_HOST_URL="$SONAR_HOST" \
  -e SONAR_TOKEN="$SONAR_TOKEN" \
  -v "$MIGRATOR_DIR:/usr/src" \
  sonarsource/sonar-scanner-cli:latest 2>&1 \
  | grep -E "(EXECUTION|ANALYSIS SUCCESSFUL|dashboard|ERROR|WARN.*[Cc]overage)" || true

echo ""
echo "Dashboard: $SONAR_HOST/dashboard?id=pubsub-kafka-migrator"
