#!/usr/bin/env bash
set -euo pipefail

MIGRATOR_DIR="/home/blacklight/IdeaProjects/pubsub-kafka-migrator/backend"
SAMPLE_APP_DIR="/home/blacklight/IdeaProjects/sample-pubsub-app"
cd "$MIGRATOR_DIR"

echo "=== Loading .env ==="
set -a && source "$MIGRATOR_DIR/.env" && set +a
echo "  AI key: ${AI_PROVIDER_API_KEY:0:15}..."

echo ""
echo "=== SonarQube kernel fix ==="
sudo sysctl -w vm.max_map_count=524288 > /dev/null && echo "  vm.max_map_count OK"

echo ""
echo "=== Starting Docker ==="
docker compose up -d
echo "  Waiting 20s for containers to stabilize..."
sleep 20
docker compose ps --format "table {{.Name}}\t{{.Status}}"

echo ""
echo "=== MinIO bucket ==="
docker exec migrator-minio sh -c "
  mc alias set local http://localhost:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD --quiet 2>/dev/null || true
  mc ls local/migrator-projects --quiet 2>/dev/null && echo '  migrator-projects: EXISTS' || (mc mb local/migrator-projects --quiet && echo '  migrator-projects: CREATED')
"

echo ""
echo "=== Kafka topics ==="
until docker exec migrator-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do
  printf "."; sleep 3
done
echo "  Kafka ready"

for topic in project.registered migration.job.created migration.job.status.update migration.job.completed; do
  docker exec migrator-kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 > /dev/null 2>&1
  echo "  $topic OK"
done

echo ""
echo "=== PubSub emulator ==="
until curl -sf "http://localhost:8085" > /dev/null 2>&1; do printf "."; sleep 2; done
echo "  PubSub emulator ready"

for topic in orders.created orders.updated orders.cancelled; do
  curl -s -X PUT "http://localhost:8085/v1/projects/my-local-project/topics/$topic" > /dev/null
  echo "  topic/$topic OK"
done

curl -s -X PUT \
  "http://localhost:8085/v1/projects/my-local-project/subscriptions/orders.notifications.sub" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/my-local-project/topics/orders.created","ackDeadlineSeconds":10}' > /dev/null
echo "  subscription orders.notifications.sub OK"

curl -s -X PUT \
  "http://localhost:8085/v1/projects/my-local-project/subscriptions/orders.inventory.sub" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/my-local-project/topics/orders.updated","ackDeadlineSeconds":10}' > /dev/null
echo "  subscription orders.inventory.sub OK"

echo ""
echo "=== orders_db ==="
docker exec migrator-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT 1 FROM pg_database WHERE datname='orders_db'" | grep -q 1 \
  && echo "  orders_db EXISTS" \
  || (docker exec migrator-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      -c "CREATE DATABASE orders_db OWNER $POSTGRES_USER;" > /dev/null && echo "  orders_db CREATED")

echo ""
echo "=== Groq API key test ==="
response=$(curl -s https://api.groq.com/openai/v1/chat/completions \
  -H "Authorization: Bearer $AI_PROVIDER_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"llama-3.1-8b-instant","messages":[{"role":"user","content":"hi"}],"max_tokens":5}')
if echo "$response" | grep -q '"content"'; then
  echo "  Groq API key: VALID"
else
  echo "  Groq API key: INVALID — check AI_PROVIDER_API_KEY in .env"
fi

echo ""
echo "================================================================"
echo " Infrastructure ready. Start services in separate terminals:"
echo ""
echo " T1: cd $MIGRATOR_DIR && source .env && ./gradlew :platform-project:bootRun"
echo " T2: cd $MIGRATOR_DIR && source .env && ./gradlew :platform-job:bootRun"
echo " T3: cd $MIGRATOR_DIR && source .env && ./gradlew :platform-orchestrator:bootRun"
echo " T4: cd $SAMPLE_APP_DIR && PUBSUB_EMULATOR_HOST=localhost:8085 ./gradlew bootRun"
echo ""
echo " pgAdmin:   http://localhost:5050  (admin@migrator.com / admin123)"
echo " Kafdrop:   http://localhost:9002"
echo " MinIO:     http://localhost:9001"
echo " SonarQube: http://localhost:9003  (takes ~3 min to start)"
echo " DefectDojo:http://localhost:8089  (admin / check .env DD_ADMIN_PASSWORD)"
echo ""
echo " Run analysis:   ./scripts/analyze.sh"
echo "================================================================"
