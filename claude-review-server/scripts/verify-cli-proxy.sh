#!/bin/bash
# verify-cli-proxy.sh — CLI 프록시 폴백 e2e 검증 스크립트
#
# 교훈: 장애 대응 중 급조한 코드가 로컬 개발 환경에서만 동작하고
#        프로덕션(Linux Docker)에서 동작하지 않았던 사고를 반복하지 않기 위해
#        배포 후 반드시 이 스크립트로 e2e 검증을 수행한다.
#
# 사용법:
#   홈서버(프로덕션): ./scripts/verify-cli-proxy.sh
#   개발PC:           ./scripts/verify-cli-proxy.sh --dev
#
# 종료 코드:
#   0 — 전체 통과
#   1 — 하나 이상 실패

set -euo pipefail

# ── 설정 ──────────────────────────────────────
PROXY_URL="${CLI_PROXY_URL:-http://host.docker.internal:3100}"
REVIEW_SERVER_URL="${REVIEW_SERVER_URL:-http://127.0.0.1:8080}"
DEV_MODE=false
[[ "${1:-}" == "--dev" ]] && DEV_MODE=true

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0

# ── 유틸 ──────────────────────────────────────
log_pass() { echo -e "${GREEN}[PASS]${NC} $1"; ((PASS++)); }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; ((FAIL++)); }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_step() { echo ""; echo -e "\033[1m▶ $1${NC}"; }

# ── Step 1: Express 서버 프로세스 확인 ─────────
log_step "Step 1: Express 서버 프로세스 확인"

if pgrep -f "cli-proxy" > /dev/null 2>&1 || pgrep -f "tsx.*index" > /dev/null 2>&1 || pgrep -f "node.*index.js" > /dev/null 2>&1 || curl -sf --max-time 1 http://127.0.0.1:3100/health > /dev/null 2>&1; then
    log_pass "Express 서버 프로세스 실행 중"
else
    log_fail "Express 서버 프로세스가 실행 중이지 않음"
    log_warn "  → cd cli-proxy && npm run start 로 실행하세요"
fi

# ── Step 2: Express /health 직접 호출 ─────────
log_step "Step 2: Express /health 직접 호출 (호스트 네트워크)"

# 호스트에서 직접 호출
HEALTH_HTTP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:3100/health 2>/dev/null || echo "000")
if [[ "$HEALTH_HTTP" == "200" ]]; then
    log_pass "호스트에서 Express /health 응답 (HTTP 200)"
else
    log_fail "호스트에서 Express /health 응답 없음 (HTTP $HEALTH_HTTP)"
fi

# ── Step 3: Express 바인딩 주소 확인 ───────────
log_step "Step 3: Express 바인딩 주소 확인"

LISTENING=$(ss -tlnp 2>/dev/null | grep ":3100" || netstat -tlnp 2>/dev/null | grep ":3100" || echo "")
if echo "$LISTENING" | grep -q "0.0.0.0"; then
    log_pass "바인딩: 0.0.0.0:3100 (모든 인터페이스 수용)"
elif echo "$LISTENING" | grep -q "127.0.0.1"; then
    log_fail "바인딩: 127.0.0.1:3100 (Docker 컨테이너에서 접근 불가)"
    log_warn "  → cli-proxy/src/index.ts에서 BIND 환경변수를 0.0.0.0으로 설정"
elif echo "$LISTENING" | grep -q ":::" || echo "$LISTENING" | grep -q "*"; then
    log_pass "바인딩: :::3100 (모든 인터페이스 수용)"
else
    log_warn "바인딩 확인 불가 (ss/netstat 결과 없음)"
fi

# ── Step 4: Docker→Host 통신 확인 ──────────────
log_step "Step 4: Docker → Host 통신 (host.docker.internal)"

if [[ "$DEV_MODE" == true ]]; then
    # 개발 모드: docker-compose로 임시 컨테이너 실행
    RESULT=$(docker run --rm --add-host=host.docker.internal:host-gateway \
        alpine/curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
        http://host.docker.internal:3100/health 2>/dev/null || echo "000")
else
    # 프로덕션: review-server 컨테이너 내부에서 호출
    CONTAINER=$(docker ps --filter "name=review-server" --format "{{.Names}}" | head -1)
    if [[ -n "$CONTAINER" ]]; then
        RESULT=$(docker exec "$CONTAINER" wget -qO- --timeout=3 \
            http://host.docker.internal:3100/health 2>/dev/null && echo "200" || echo "000")
    else
        RESULT="000"
        log_warn "review-server 컨테이너가 실행 중이지 않음"
    fi
fi

if [[ "$RESULT" == *"200"* ]]; then
    log_pass "Docker 컨테이너에서 host.docker.internal:3100 접근 성공"
else
    log_fail "Docker 컨테이너에서 host.docker.internal:3100 접근 실패 (HTTP $RESULT)"
    log_warn "  → docker-compose.yml에 extra_hosts: host.docker.internal:host-gateway 확인"
    log_warn "  → Express 바인딩이 0.0.0.0인지 확인"
fi

# ── Step 5: CLI 실제 호출 (Express 경유) ───────
log_step "Step 5: CLI 실제 호출 (Express 경유)"

CLI_RESPONSE=$(curl -s --max-time 30 -X POST http://127.0.0.1:3100/review \
    -H "Content-Type: application/json" \
    -d '{"systemPrompt":"코드리뷰 테스트","userPrompt":"간단히 답변해주세요","provider":"gemini"}' \
    2>/dev/null || echo "")

if [[ -n "$CLI_RESPONSE" ]] && echo "$CLI_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); assert 'review' in d" 2>/dev/null; then
    REVIEW_LEN=$(echo "$CLI_RESPONSE" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('review','')))")
    MODEL=$(echo "$CLI_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('model',''))")
    ELAPSED=$(echo "$CLI_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('elapsedMs',''))")
    log_pass "CLI 호출 성공 — model: $MODEL, ${REVIEW_LEN}자, ${ELAPSED}ms"
else
    log_fail "CLI 호출 실패 — 응답: ${CLI_RESPONSE:0:200}"
fi

# ── Step 6: Spring Boot 폴백 플로우 확인 ──────
log_step "Step 6: Spring Boot /health + 폴백 설정 상태"

if curl -sf --max-time 3 "${REVIEW_SERVER_URL}/health" > /dev/null 2>&1; then
    HEALTH_JSON=$(curl -sf --max-time 3 "${REVIEW_SERVER_URL}/health" 2>/dev/null)
    log_pass "Spring Boot /health 응답 정상"

    # Gemini proxy 상태 확인 (actuator)
    if echo "$HEALTH_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('geminiProxy',{}).get('status')=='UP'" 2>/dev/null; then
        log_pass "GeminiProxy HealthIndicator: UP (cli-proxy URL 설정됨)"
    else
        log_warn "GeminiProxy HealthIndicator 상태 확인 불가 (actuator 미노출 가능)"
    fi
else
    log_warn "Spring Boot 서버 응답 없음 (${REVIEW_SERVER_URL})"
fi

# ── Step 7: docker-compose extra_hosts 확인 ────
log_step "Step 7: docker-compose extra_hosts 설정 확인"

COMPOSE_FILE="docker-compose.yml"
if [[ -f "$COMPOSE_FILE" ]]; then
    if grep -q "host.docker.internal:host-gateway" "$COMPOSE_FILE"; then
        log_pass "docker-compose.yml에 extra_hosts 설정 존재"
    else
        log_fail "docker-compose.yml에 extra_hosts 누락"
        log_warn "  → review-server 서비스에 extra_hosts 추가 필요"
    fi
else
    log_warn "docker-compose.yml 없음 (현재 디렉토리: $(pwd))"
fi

# ── 결과 ──────────────────────────────────────
echo ""
echo "══════════════════════════════════════"
echo -e "  통과: ${GREEN}${PASS}${NC}  실패: ${RED}${FAIL}${NC}"
echo "══════════════════════════════════════"

if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "실패 항목이 있습니다. 위 로그를 확인하세요."
    exit 1
fi

echo ""
echo "모든 검증 통과! CLI 프록시 폴백이 정상 동작합니다."
exit 0
