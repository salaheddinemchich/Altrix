#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# analyze.sh — Static analysis + security scan → SonarQube & DefectDojo
#
# Prerequisites: run start-dev.sh first (SonarQube & DefectDojo must be up)
#
# Usage:
#   ./scripts/analyze.sh                  # full analysis
#   ./scripts/analyze.sh --skip-tests     # reuse previous test/coverage results
#   ./scripts/analyze.sh --sonar-only     # only SonarQube, skip security scans
#   ./scripts/analyze.sh --security-only  # only security scans + DefectDojo
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

# ── Parse flags ──────────────────────────────────────────────────────────────
SKIP_TESTS=false
SONAR_ONLY=false
SECURITY_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --skip-tests)    SKIP_TESTS=true ;;
    --sonar-only)    SONAR_ONLY=true ;;
    --security-only) SECURITY_ONLY=true ;;
  esac
done

# ── Load .env ─────────────────────────────────────────────────────────────────
set -a && source "$ROOT/.env" && set +a

SONAR_HOST="${SONAR_HOST_URL:-http://localhost:9003}"
DD_HOST="http://localhost:8089"
SCAN_DIR="$ROOT/build/security-scans"
mkdir -p "$SCAN_DIR"

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✓ $*${NC}"; }
warn() { echo -e "${YELLOW}  ! $*${NC}"; }
fail() { echo -e "${RED}  ✗ $*${NC}"; }
hdr()  { echo -e "\n${YELLOW}═══ $* ═══${NC}"; }

# ─────────────────────────────────────────────────────────────────────────────
# 0. Pre-flight: verify required containers are up
# ─────────────────────────────────────────────────────────────────────────────
hdr "Pre-flight checks"

check_container() {
  local name=$1
  if docker ps --format '{{.Names}}' | grep -q "^${name}$"; then
    ok "$name is running"
  else
    fail "$name is NOT running — start it first with: ./scripts/start-dev.sh"
    exit 1
  fi
}

if [ "$SECURITY_ONLY" = false ]; then
  check_container "migrator-sonarqube"
fi
if [ "$SONAR_ONLY" = false ]; then
  check_container "migrator-defectdojo"
  check_container "migrator-defectdojo-nginx"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 1. Tests + JaCoCo coverage
# ─────────────────────────────────────────────────────────────────────────────
if [ "$SKIP_TESTS" = true ]; then
  hdr "Tests (skipped — using cached results)"
  warn "Using existing build/reports/jacoco output"
else
  hdr "Running tests + JaCoCo coverage"
  ./gradlew \
    :platform-common:test     :platform-common:jacocoTestReport \
    :platform-project:test    :platform-project:jacocoTestReport \
    :platform-job:test        :platform-job:jacocoTestReport \
    :platform-orchestrator:test :platform-orchestrator:jacocoTestReport \
    --parallel --no-daemon --continue 2>&1 \
    | grep -E "(Tests run|FAIL|ERROR|BUILD|jacocoTestReport)" || true
  ok "Tests and coverage reports generated"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 2. SonarQube analysis
# ─────────────────────────────────────────────────────────────────────────────
if [ "$SECURITY_ONLY" = false ]; then
  hdr "SonarQube analysis"

  if [ -z "${SONAR_TOKEN:-}" ]; then
    fail "SONAR_TOKEN is not set in .env — skipping SonarQube"
  else
    echo "  Waiting for SonarQube at $SONAR_HOST ..."
    for i in $(seq 1 24); do
      STATUS=$(curl -sf "$SONAR_HOST/api/system/status" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || true)
      [ "$STATUS" = "UP" ] && break
      printf "."; sleep 5
    done
    echo ""

    if [ "$STATUS" != "UP" ]; then
      fail "SonarQube not ready after 2 min — skipping (try again or check container logs)"
    else
      ok "SonarQube is UP"
      echo "  Sending analysis..."

      docker run --rm \
        --network=host \
        -e SONAR_HOST_URL="$SONAR_HOST" \
        -e SONAR_TOKEN="$SONAR_TOKEN" \
        -v "$ROOT:/usr/src" \
        sonarsource/sonar-scanner-cli:latest 2>&1 \
        | grep -E "(EXECUTION|ANALYSIS SUCCESSFUL|dashboard|Quality Gate|ERROR)" || true

      echo ""
      ok "SonarQube dashboard: $SONAR_HOST/dashboard?id=pubsub-kafka-migrator"
    fi
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# 3. Security scans (Gitleaks · Semgrep · Trivy)
# ─────────────────────────────────────────────────────────────────────────────
if [ "$SONAR_ONLY" = false ]; then
  hdr "Security scans"

  # 3a. Gitleaks — secret detection
  echo "  [1/3] Gitleaks (secret scan)..."
  docker run --rm \
    -v "$ROOT:/repo" \
    zricethezav/gitleaks:latest detect \
      --source /repo \
      --report-format sarif \
      --report-path /repo/build/security-scans/gitleaks.sarif \
      --no-banner \
      --redact \
      2>&1 | tail -3 || true
  ok "Gitleaks → build/security-scans/gitleaks.sarif"

  # 3b. Semgrep — SAST
  echo "  [2/3] Semgrep (SAST)..."
  docker run --rm \
    -v "$ROOT:/src" \
    -e SEMGREP_SEND_METRICS=off \
    semgrep/semgrep:latest semgrep scan \
      --config p/java \
      --config p/spring \
      --config p/owasp-top-ten \
      --config p/secrets \
      --sarif \
      --output /src/build/security-scans/semgrep.sarif \
      /src 2>&1 | grep -E "(ran|findings|error|Error)" || true
  ok "Semgrep → build/security-scans/semgrep.sarif"

  # 3c. Trivy — dependency CVEs
  echo "  [3/3] Trivy (dependency CVEs)..."
  docker run --rm \
    -v "$ROOT:/repo" \
    -v trivy-cache:/root/.cache/trivy \
    aquasec/trivy:latest filesystem \
      --format sarif \
      --output /repo/build/security-scans/trivy-fs.sarif \
      --severity CRITICAL,HIGH,MEDIUM \
      --ignore-unfixed \
      --vuln-type library \
      --quiet \
      /repo 2>/dev/null || true
  ok "Trivy → build/security-scans/trivy-fs.sarif"

  # ─────────────────────────────────────────────────────────────────────────
  # 4. Upload to DefectDojo
  # ─────────────────────────────────────────────────────────────────────────
  hdr "Uploading findings to DefectDojo"

  echo "  Getting API token..."
  DD_TOKEN=$(curl -s -X POST "$DD_HOST/api/v2/api-token-auth/" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"admin\",\"password\":\"${DD_ADMIN_PASSWORD}\"}" \
    2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null || true)

  if [ -z "${DD_TOKEN:-}" ]; then
    fail "Could not get DefectDojo token — check DD_ADMIN_PASSWORD in .env and that DefectDojo is running"
  else
    ok "DefectDojo authenticated"

    # Ensure product exists (create once, ignore duplicate errors)
    curl -s -X POST "$DD_HOST/api/v2/products/" \
      -H "Authorization: Token $DD_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"name":"pubsub-kafka-migrator","description":"PubSub to Kafka migration platform","prod_type":1}' \
      -o /dev/null 2>/dev/null || true

    # Create a new engagement named after the current date+commit
    COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
    TODAY=$(date +%Y-%m-%d)
    ENG_NAME="local-scan-${TODAY}-${COMMIT}"

    ENG_ID=$(curl -s -X POST "$DD_HOST/api/v2/engagements/" \
      -H "Authorization: Token $DD_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"${ENG_NAME}\",\"product\":1,\"target_start\":\"${TODAY}\",\"target_end\":\"${TODAY}\",\"status\":\"In Progress\",\"engagement_type\":\"CI/CD\"}" \
      2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || true)

    if [ -z "${ENG_ID:-}" ]; then
      fail "Could not create DefectDojo engagement"
    else
      ok "Engagement created: $ENG_NAME (id=$ENG_ID)"

      upload_to_dojo() {
        local scan_type="$1"
        local file="$2"
        local label="$3"
        if [ ! -f "$file" ]; then
          warn "$label — file not found, skipping"
          return 0
        fi
        local status
        status=$(curl -s -o /tmp/dd-resp.json -w "%{http_code}" \
          -H "Authorization: Token $DD_TOKEN" \
          -F "scan_type=${scan_type}" \
          -F "file=@${file}" \
          -F "product_name=pubsub-kafka-migrator" \
          -F "engagement_name=${ENG_NAME}" \
          -F "auto_create_context=true" \
          -F "close_old_findings=false" \
          "$DD_HOST/api/v2/import-scan/" 2>/dev/null) || true
        if [ "${status:-0}" -ge 200 ] && [ "${status:-0}" -lt 300 ]; then
          ok "$label uploaded (HTTP $status)"
        else
          warn "$label failed (HTTP ${status:-0}): $(cat /tmp/dd-resp.json 2>/dev/null | python3 -m json.tool 2>/dev/null | head -3 || true)"
        fi
      }

      upload_to_dojo "SARIF" "$SCAN_DIR/gitleaks.sarif"  "Gitleaks secret scan"
      upload_to_dojo "SARIF" "$SCAN_DIR/semgrep.sarif"   "Semgrep SAST"
      upload_to_dojo "SARIF" "$SCAN_DIR/trivy-fs.sarif"  "Trivy SCA (filesystem)"

      echo ""
      ok "DefectDojo dashboard: $DD_HOST/engagement/$ENG_ID"
    fi
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN} Analysis complete${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════════${NC}"
if [ "$SECURITY_ONLY" = false ] && [ -n "${SONAR_TOKEN:-}" ]; then
  echo "  SonarQube : $SONAR_HOST/dashboard?id=pubsub-kafka-migrator"
fi
if [ "$SONAR_ONLY" = false ]; then
  echo "  DefectDojo: $DD_HOST/product/list"
fi
echo ""
