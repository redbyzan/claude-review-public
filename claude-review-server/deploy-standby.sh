#!/bin/bash
set -e

echo "=== AI Code Review Standby 배포 ==="

COMPOSE_FILE="docker-compose.standby.yml"

# 1. Docker 이미지 빌드
echo "[1/3] Docker 이미지 빌드..."
docker compose -f "$COMPOSE_FILE" build --no-cache

# 2. 컨테이너 재시작
echo "[2/3] Standby 컨테이너 배포..."
docker compose -f "$COMPOSE_FILE" up -d

# 3. 헬스체크 (18080 포트)
echo "[3/3] 헬스체크 대기..."
for i in $(seq 1 10); do
    if curl -sf http://localhost:18080/health 2>/dev/null | grep -q '"ok"'; then
        echo "Standby 배포 완료!"
        curl -sf http://localhost:18080/health 2>/dev/null
        echo ""
        exit 0
    fi
    sleep 3
done

echo "헬스체크 실패. 로그를 확인하세요:"
docker compose -f "$COMPOSE_FILE" logs --tail=20 review-server
exit 1
