#!/usr/bin/env bash
set -euo pipefail

MIGRATOR_DIR="/home/blacklight/IdeaProjects/pubsub-kafka-migrator/backend"
SAMPLE_APP_DIR="/home/blacklight/IdeaProjects/sample-pubsub-app"
cd "$MIGRATOR_DIR"

echo "=== Stopping Gradle services ==="

# Kill bootRun processes by port
for port in 8082 8083 8084 8090; do
  pid=$(lsof -ti :$port 2>/dev/null || true)
  if [ -n "$pid" ]; then
    echo "  Stopping process on port $port (PID $pid)"
    kill -SIGTERM "$pid" 2>/dev/null || true
  else
    echo "  Port $port: nothing running"
  fi
done

# Kill any remaining bootRun processes by name
pkill -f "platform-project:bootRun"      2>/dev/null || true
pkill -f "platform-job:bootRun"          2>/dev/null || true
pkill -f "platform-orchestrator:bootRun" 2>/dev/null || true
pkill -f "sample-pubsub-app"             2>/dev/null || true

# Stop Gradle daemons so they don't hold file locks or ports
echo "  Stopping Gradle daemons..."
"$MIGRATOR_DIR/gradlew" --stop --project-dir "$MIGRATOR_DIR" 2>/dev/null || true
[ -f "$SAMPLE_APP_DIR/gradlew" ] && \
  "$SAMPLE_APP_DIR/gradlew" --stop --project-dir "$SAMPLE_APP_DIR" 2>/dev/null || true

echo ""
echo "=== Stopping Docker containers ==="
docker compose down
echo "  All containers stopped"

echo ""
echo "=== Done. Everything is shut down. ==="
