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
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-altrix}"
DD_HOST="http://localhost:8089"
DD_PRODUCT="Altrix"
SCAN_DIR="$ROOT/build/security-scans"
GIT_ROOT="$(git -C "$ROOT" rev-parse --show-toplevel 2>/dev/null || echo "$ROOT")"
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

      SONAR_OUTPUT=$(docker run --rm \
        --network=host \
        -e SONAR_HOST_URL="$SONAR_HOST" \
        -e SONAR_TOKEN="$SONAR_TOKEN" \
        -v "$ROOT:/usr/src" \
        sonarsource/sonar-scanner-cli:latest 2>&1) && SONAR_OK=true || SONAR_OK=false

      echo "$SONAR_OUTPUT" \
        | grep -E "(EXECUTION|ANALYSIS SUCCESSFUL|dashboard|Quality Gate|ERROR)" || true
      echo ""

      if [ "$SONAR_OK" = "true" ]; then
        ok "SonarQube dashboard: $SONAR_HOST/dashboard?id=$SONAR_PROJECT_KEY"
      else
        fail "SonarQube analysis FAILED — regenerate SONAR_TOKEN at: $SONAR_HOST/account/security"
      fi
    fi
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# 3. Security scans (Gitleaks · Semgrep · Trivy) — JSON for DefectDojo native parsers
# ─────────────────────────────────────────────────────────────────────────────
if [ "$SONAR_ONLY" = false ]; then
  hdr "Security scans"

  # 3a. Gitleaks — secret detection (JSON for DefectDojo "Gitleaks Scan" parser)
  echo "  [1/3] Gitleaks (secret scan)..."
  docker run --rm \
    -v "$GIT_ROOT:/repo" \
    zricethezav/gitleaks:latest detect \
      --source /repo \
      --report-format json \
      --report-path /repo/backend/build/security-scans/gitleaks.json \
      --no-banner \
      --redact \
      2>&1 | tail -3 || true
  ok "Gitleaks → build/security-scans/gitleaks.json"

  # 3b. Semgrep — SAST (JSON for DefectDojo "Semgrep JSON Report" parser)
  echo "  [2/3] Semgrep (SAST)..."
  SEMGREP_OUTPUT=$(docker run --rm \
    --network=host \
    -v "$ROOT:/src" \
    -e SEMGREP_SEND_METRICS=off \
    semgrep/semgrep:latest semgrep scan \
      --config p/java \
      --config p/owasp-top-ten \
      --config p/secrets \
      --json \
      --output /src/build/security-scans/semgrep.json \
      /src 2>&1) && SEMGREP_OK=true || SEMGREP_OK=false
  echo "$SEMGREP_OUTPUT" | grep -E "(ran|findings|Rules|error|Error|warning)" | head -10 || true
  if [ "$SEMGREP_OK" = "true" ]; then
    ok "Semgrep → build/security-scans/semgrep.json"
  else
    fail "Semgrep scan FAILED — check output above"
  fi

  # 3c. Trivy — dependency CVEs (JSON for DefectDojo "Trivy Scan" parser)
  echo "  [3/3] Trivy (dependency CVEs)..."
  docker run --rm \
    -v "$ROOT:/repo" \
    -v trivy-cache:/root/.cache/trivy \
    aquasec/trivy:latest filesystem \
      --format json \
      --output /repo/build/security-scans/trivy-fs.json \
      --severity CRITICAL,HIGH,MEDIUM \
      --ignore-unfixed \
      --vuln-type library \
      --quiet \
      /repo 2>/dev/null || true
  ok "Trivy → build/security-scans/trivy-fs.json"

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

    # Ensure product exists (idempotent — duplicate name returns 400, ignored)
    curl -s -X POST "$DD_HOST/api/v2/products/" \
      -H "Authorization: Token $DD_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"${DD_PRODUCT}\",\"description\":\"AI-powered migration platform — automated codebase analysis, planning, transformation, and validation across any stack.\",\"prod_type\":1}" \
      -o /dev/null 2>/dev/null || true

    DD_PRODUCT_ID=$(curl -s "$DD_HOST/api/v2/products/?name=${DD_PRODUCT}&limit=1" \
      -H "Authorization: Token $DD_TOKEN" \
      2>/dev/null | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['results'][0]['id'])" 2>/dev/null || true)

    if [ -z "${DD_PRODUCT_ID:-}" ]; then
      fail "Could not resolve DefectDojo product ID for '${DD_PRODUCT}'"
    else
      ok "Product '${DD_PRODUCT}' (id=$DD_PRODUCT_ID)"
    fi

    # Engagement: unique-per-run (timestamp ensures no duplicate-name collisions)
    COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
    BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "local")
    TODAY=$(date +%Y-%m-%d)
    TIMESTAMP=$(date +%H%M%S)
    ENG_NAME="local-${BRANCH}-${COMMIT}-${TIMESTAMP}"

    ENG_ID=$(curl -s -X POST "$DD_HOST/api/v2/engagements/" \
      -H "Authorization: Token $DD_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"${ENG_NAME}\",\"product\":${DD_PRODUCT_ID},\"target_start\":\"${TODAY}\",\"target_end\":\"${TODAY}\",\"status\":\"In Progress\",\"engagement_type\":\"CI/CD\",\"commit_hash\":\"${COMMIT}\",\"branch_tag\":\"${BRANCH}\"}" \
      2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || true)

    if [ -z "${ENG_ID:-}" ]; then
      fail "Could not create DefectDojo engagement"
    else
      ok "Engagement '${ENG_NAME}' (id=$ENG_ID)"

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
          -F "engagement=${ENG_ID}" \
          -F "test_title=${label}" \
          -F "active=true" \
          -F "verified=false" \
          -F "close_old_findings=false" \
          "$DD_HOST/api/v2/import-scan/" 2>/dev/null) || true
        if [ "${status:-0}" -ge 200 ] && [ "${status:-0}" -lt 300 ]; then
          local count
          count=$(python3 -c "import json; d=json.load(open('/tmp/dd-resp.json')); print(d.get('statistics',{}).get('after',{}).get('info',{}).get('total',0) + d.get('statistics',{}).get('after',{}).get('low',{}).get('total',0) + d.get('statistics',{}).get('after',{}).get('medium',{}).get('total',0) + d.get('statistics',{}).get('after',{}).get('high',{}).get('total',0) + d.get('statistics',{}).get('after',{}).get('critical',{}).get('total',0))" 2>/dev/null || echo "?")
          ok "$label uploaded ($count findings)"
        else
          warn "$label failed (HTTP ${status:-0}): $(cat /tmp/dd-resp.json 2>/dev/null | python3 -m json.tool 2>/dev/null | head -3 || true)"
        fi
      }

      upload_to_dojo "Gitleaks Scan"       "$SCAN_DIR/gitleaks.json"  "Gitleaks — Secret Detection"
      upload_to_dojo "Semgrep JSON Report" "$SCAN_DIR/semgrep.json"   "Semgrep — SAST"
      upload_to_dojo "Trivy Scan"          "$SCAN_DIR/trivy-fs.json"  "Trivy — Dependency CVEs"

      # Mark engagement Completed once all scans uploaded
      curl -s -X PATCH "$DD_HOST/api/v2/engagements/${ENG_ID}/" \
        -H "Authorization: Token $DD_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"status":"Completed"}' \
        -o /dev/null 2>/dev/null || true

      echo ""
      ok "Engagement marked Completed"
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
  echo "  SonarQube : $SONAR_HOST/dashboard?id=$SONAR_PROJECT_KEY"
fi
if [ "$SONAR_ONLY" = false ]; then
  echo "  DefectDojo: $DD_HOST/product/${DD_PRODUCT_ID:-list}"
fi
echo ""
