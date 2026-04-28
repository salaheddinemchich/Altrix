#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start-dev.sh — Run once after every reboot to bring up the full dev stack.
# Usage: ./scripts/start-dev.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

MIGRATOR_DIR="/home/blacklight/IdeaProjects/pubsub-kafka-migrator"
SAMPLE_APP_DIR="/home/blacklight/IdeaProjects/sample-pubsub-app"

cd "$MIGRATOR_DIR"

# ── Load environment variables ────────────────────────────────────────────────
echo "Loading .env..."
set -a && source "$MIGRATOR_DIR/.env" && set +a
echo "  AI key prefix: ${AI_PROVIDER_API_KEY:0:10}..."

# ── 1. SonarQube kernel requirement ──────────────────────────────────────────
echo ""
echo "Setting vm.max_map_count for SonarQube..."
sudo sysctl -w vm.max_map_count=524288 > /dev/null
echo "  vm.max_map_count = $(sysctl -n vm.max_map_count)"

# ── 2. Start Docker infrastructure ───────────────────────────────────────────
echo ""
echo "Starting Docker containers..."
docker compose up -d
echo "  Waiting 15s for containers to stabilize..."
sleep 15
docker compose ps --format "table {{.Name}}\t{{.Status}}"

# ── 3. Create MinIO bucket ───────────────────────────────────────────────────
echo ""
echo "Checking MinIO bucket..."
docker exec migrator-minio sh -c "
  mc alias set local http://localhost:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD --quiet 2>/dev/null
  mc ls local/migrator-projects --quiet 2>/dev/null && echo '  migrator-projects: EXISTS' || {
    mc mb local/migrator-projects --quiet
    echo '  migrator-projects: CREATED'
  }
"

# ── 4. Create Kafka topics ───────────────────────────────────────────────────
echo ""
echo "Checking Kafka topics..."
echo "  Waiting for Kafka to be ready..."
until docker exec migrator-kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list \
  > /dev/null 2>&1; do
  printf "."
  sleep 3
done
echo " Kafka ready."

for topic in \
  "project.registered" \
  "migration.job.created" \
  "migration.job.status.update" \
  "migration.job.completed"; do
  docker exec migrator-kafka \
    /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions 1 --replication-factor 1 \
    > /dev/null 2>&1
  echo "  topic: $topic OK"
done

# ── 5. Create PubSub topics + subscriptions ───────────────────────────────────
echo ""
echo "Checking PubSub emulator topics..."
echo "  Waiting for PubSub emulator..."
until curl -sf "http://localhost:8085" > /dev/null 2>&1; do
  printf "."
  sleep 2
done
echo " PubSub emulator ready."

for topic in orders.created orders.updated orders.cancelled; do
  result=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "http://localhost:8085/v1/projects/my-local-project/topics/$topic")
  echo "  topic $topic: HTTP $result"
done

# Subscriptions
curl -s -X PUT \
  "http://localhost:8085/v1/projects/my-local-project/subscriptions/orders.notifications.sub" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/my-local-project/topics/orders.created","ackDeadlineSeconds":10}' \
  > /dev/null
echo "  subscription orders.notifications.sub OK"

curl -s -X PUT \
  "http://localhost:8085/v1/projects/my-local-project/subscriptions/orders.inventory.sub" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/my-local-project/topics/orders.updated","ackDeadlineSeconds":10}' \
  > /dev/null
echo "  subscription orders.inventory.sub OK"

# ── 6. Create orders_db ───────────────────────────────────────────────────────
echo ""
echo "Checking orders_db..."
docker exec migrator-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT 1 FROM pg_database WHERE datname='orders_db'" | grep -q 1 && \
  echo "  orders_db: EXISTS" || {
    docker exec migrator-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      -c "CREATE DATABASE orders_db OWNER $POSTGRES_USER;" > /dev/null
    echo "  orders_db: CREATED"
  }

# ── 7. Test Groq API key ──────────────────────────────────────────────────────
echo ""
echo "Testing Groq API key..."
response=$(curl -s https://api.groq.com/openai/v1/chat/completions \
  -H "Authorization: Bearer $AI_PROVIDER_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"llama-3.3-70b-versatile","messages":[{"role":"user","content":"hi"}],"max_tokens":5}' \
  2>/dev/null)

if echo "$response" | grep -q '"content"'; then
  echo "  Groq API key: VALID ✓"
else
  error=$(echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('error',{}).get('code','unknown'))" 2>/dev/null)
  echo "  Groq API key: INVALID — error: $error"
  echo "  Fix: update AI_PROVIDER_API_KEY in .env then re-run this script"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Infrastructure ready. Now start services in 3 terminals:"
echo ""
echo "  Terminal 1:  cd $MIGRATOR_DIR && source .env && ./gradlew :platform-project:bootRun"
echo "  Terminal 2:  cd $MIGRATOR_DIR && source .env && ./gradlew :platform-job:bootRun"
echo "  Terminal 3:  cd $MIGRATOR_DIR && source .env && ./gradlew :platform-orchestrator:bootRun"
echo "  Terminal 4:  cd $SAMPLE_APP_DIR && PUBSUB_EMULATOR_HOST=localhost:8085 ./gradlew bootRun"
echo ""
echo "  pgAdmin:     http://localhost:5050  (admin@migrator.com / admin123)"
echo "  Kafdrop:     http://localhost:9002"
echo "  MinIO:       http://localhost:9001"
echo "  SonarQube:   http://localhost:9003  (takes ~3 min to start)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
