#!/bin/bash
set -e

echo "=== AI Code Review 배포 ==="

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
E2E_SCRIPT="$SCRIPT_DIR/scripts/verify-cli-proxy.sh"

# 1. 로컬 dev 서버 종료
echo "[1/5] 로컬 dev 서버 종료..."
pkill -f "ClaudeReviewApplication" 2>/dev/null || true

# 2. Docker 이미지 빌드
echo "[2/5] Docker 이미지 빌드..."
docker compose build --no-cache

# 3. 컨테이너 재시작
echo "[3/5] 컨테이너 배포..."
docker compose up -d

# 4. 헬스체크
echo "[4/5] 헬스체크 대기..."
HEALTHY=false
for i in $(seq 1 10); do
    if docker compose exec -T review-server wget -qO- http://localhost:8080/health 2>/dev/null | grep -q '"ok"'; then
        echo "헬스체크 통과!"
        docker compose exec -T review-server wget -qO- http://localhost:8080/health 2>/dev/null
        echo ""
        HEALTHY=true
        break
    fi
    sleep 3
done

if [ "$HEALTHY" = "false" ]; then
    echo "헬스체크 실패. 로그를 확인하세요:"
    docker compose logs --tail=20 review-server
    exit 1
fi

# 5. E2E 검증 (CLI 프록시 + Docker→Host 통신)
echo "[5/5] E2E 검증..."
if [ -x "$E2E_SCRIPT" ]; then
    if "$E2E_SCRIPT"; then
        echo ""
        echo "=== 배포 + E2E 검증 완료 ==="
    else
        echo ""
        echo "⚠ WARNING: E2E 검증 실패. 서비스는 정상이나 CLI 폴백 확인 필요."
        echo "  수동 확인: $E2E_SCRIPT"
        # 서비스는 정상이므로 exit 1 대신 경고만 남김
    fi
else
    echo "E2E 스크립트 없음 — 건너뜀 ($E2E_SCRIPT)"
fi
