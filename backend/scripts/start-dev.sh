#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# start-dev.sh — boot all infrastructure, provision resources, verify health
#
# After this script exits successfully, choose ONE of:
#   ./scripts/start-services.sh   → backend (3 services) + frontend
#   ./scripts/analyze.sh          → SonarQube + security scans + DefectDojo
#
# Or run everything in one shot:
#   ./scripts/start-all.sh        → infrastructure + services
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✓ $*${NC}"; }
warn() { echo -e "${YELLOW}  ! $*${NC}"; }
fail() { echo -e "${RED}  ✗ $*${NC}"; exit 1; }
hdr()  { echo -e "\n${YELLOW}═══ $* ═══${NC}"; }

# ── 0. Load environment ───────────────────────────────────────────────────────
hdr "Loading .env"
if [ ! -f "$ROOT/.env" ]; then
  fail "$ROOT/.env not found — create it with your GitHub/AI credentials."
fi
set -a && source "$ROOT/.env" && set +a
ok ".env loaded"
[ -n "${GROQ_API_KEY:-${AI_PROVIDER_API_KEY:-}}" ] && ok "Groq key:       [SET]"     || warn "Groq key:       [empty]"
[ -n "${NVIDIA_API_KEY:-}"      ] && ok "NVIDIA key:     [SET]"     || warn "NVIDIA key:     [empty]"
[ -n "${OPENROUTER_API_KEY:-}"  ] && ok "OpenRouter key: [SET]"     || warn "OpenRouter key: [empty]"
[ -n "${GITHUB_CLIENT_ID:-}"    ] && ok "GitHub OAuth:   [SET]"     || warn "GitHub OAuth:   [empty]"

# ── 1. SonarQube kernel requirement ──────────────────────────────────────────
hdr "Kernel: vm.max_map_count"
sudo sysctl -w vm.max_map_count=524288 > /dev/null
ok "vm.max_map_count = 524288"

# ── 2. Start all containers ───────────────────────────────────────────────────
hdr "Starting Docker Compose"
docker compose up -d
ok "Containers started — waiting for health checks..."

# ── 3. Wait for critical services (health-check polling, no blind sleep) ──────
hdr "Waiting for core services to be healthy"

wait_healthy() {
  local name="$1"
  local max=30  # 30 × 5s = 2.5 min max
  for i in $(seq 1 $max); do
    local health
    health=$(docker inspect --format '{{.State.Health.Status}}' "$name" 2>/dev/null || echo "none")
    if [ "$health" = "healthy" ]; then
      ok "$name is healthy"
      return 0
    fi
    printf "  waiting for %s (%d/%d)...\r" "$name" "$i" "$max"
    sleep 5
  done
  fail "$name did not become healthy in time — check: docker logs $name"
}

wait_healthy "altrix-postgres"
wait_healthy "altrix-redis"
wait_healthy "altrix-kafka"
wait_healthy "altrix-minio"

# ── 4. MinIO bucket ──────────────────────────────────────────────────────────
hdr "MinIO bucket"
docker exec altrix-minio sh -c "
  mc alias set local http://localhost:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD --quiet 2>/dev/null || true
  mc ls local/altrix-projects --quiet 2>/dev/null \
    && echo '  altrix-projects: EXISTS' \
    || (mc mb local/altrix-projects --quiet && echo '  altrix-projects: CREATED')
"
ok "altrix-projects bucket ready"

# ── 5. Kafka topics ───────────────────────────────────────────────────────────
hdr "Kafka topics"
until docker exec altrix-kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do
  printf "."; sleep 3
done
echo ""
for topic in project.registered migration.job.created migration.job.status.update migration.job.completed; do
  docker exec altrix-kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 > /dev/null 2>&1
  ok "$topic"
done

# ── 6. PubSub emulator topics ─────────────────────────────────────────────────
hdr "PubSub emulator"
until curl -sf "http://localhost:8085" > /dev/null 2>&1; do printf "."; sleep 2; done
echo ""
for topic in orders.created orders.updated orders.cancelled; do
  curl -s -X PUT "http://localhost:8085/v1/projects/my-local-project/topics/$topic" > /dev/null
  ok "topic/$topic"
done
curl -s -X PUT "http://localhost:8085/v1/projects/my-local-project/subscriptions/orders.notifications.sub" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/my-local-project/topics/orders.created","ackDeadlineSeconds":10}' > /dev/null
ok "subscription: orders.notifications.sub"
curl -s -X PUT "http://localhost:8085/v1/projects/my-local-project/subscriptions/orders.inventory.sub" \
  -H "Content-Type: application/json" \
  -d '{"topic":"projects/my-local-project/topics/orders.updated","ackDeadlineSeconds":10}' > /dev/null
ok "subscription: orders.inventory.sub"

# ── 7. Postgres: orders_db ───────────────────────────────────────────────────
hdr "Postgres: orders_db"
docker exec altrix-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT 1 FROM pg_database WHERE datname='orders_db'" | grep -q 1 \
  && ok "orders_db EXISTS" \
  || (docker exec altrix-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      -c "CREATE DATABASE orders_db OWNER $POSTGRES_USER;" > /dev/null && ok "orders_db CREATED")

# ── 8. AI provider key test (Groq only — fast smoke check) ──────────────────
hdr "AI provider key (Groq smoke test)"
GROQ_KEY="${GROQ_API_KEY:-${AI_PROVIDER_API_KEY:-}}"
if [ -z "$GROQ_KEY" ]; then
  warn "GROQ_API_KEY / AI_PROVIDER_API_KEY not set — skipping smoke test"
else
  response=$(curl -s --max-time 10 https://api.groq.com/openai/v1/chat/completions \
    -H "Authorization: Bearer $GROQ_KEY" \
    -H "Content-Type: application/json" \
    -d '{"model":"llama-3.1-8b-instant","messages":[{"role":"user","content":"hi"}],"max_tokens":5}' 2>/dev/null || true)
  if echo "$response" | grep -q '"content"'; then
    ok "Groq key: VALID"
  else
    warn "Groq key: could not verify — check GROQ_API_KEY in .env"
  fi
fi

# ── 9. Final summary ──────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN} Infrastructure ready${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
echo ""
echo "  pgAdmin:    http://localhost:5050  (admin@altrix.com / admin123)"
echo "  Kafdrop:    http://localhost:9002"
echo "  MinIO:      http://localhost:9001  ($MINIO_ROOT_USER)"
echo "  RedisUI:    http://localhost:8001"
echo "  SonarQube:  http://localhost:9003  (still starting — takes ~3 min)"
echo "  DefectDojo: http://localhost:8089  (admin / $DD_ADMIN_PASSWORD — takes ~5 min)"
echo ""
echo -e "${YELLOW}  Next steps:${NC}"
echo "    ./scripts/start-services.sh   → backend + frontend"
echo "    ./scripts/analyze.sh          → SonarQube + security scans"
echo "    ./scripts/stop-dev.sh         → stop everything"
echo ""
