#!/usr/bin/env bash
# ============================================================
# issue-cert.sh — Let's Encrypt SSL 인증서 최초 발급
#
# 실행 전 확인사항:
#   1. 공유기 80/443 포트포워딩 설정 완료
#   2. Route53 DNS 전파 완료 (dig 명령으로 확인)
#   3. docker compose up -d nginx 실행 중
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# .env에서 설정 로드
source "${PROJECT_DIR}/.env"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] SSL 인증서 발급 시작 — ${DOMAIN}"

# DNS 전파 확인
echo "DNS 전파 확인 중..."
RESOLVED_IP=$(dig +short "${DOMAIN}" | tail -n1)
echo "  ${DOMAIN} → ${RESOLVED_IP}"

if [ -z "$RESOLVED_IP" ]; then
  echo "[ERROR] DNS가 아직 전파되지 않았습니다. 잠시 후 다시 시도하세요."
  exit 1
fi

cd "$PROJECT_DIR"

# Nginx 실행 확인 (ACME 챌린지 처리용)
if ! docker compose ps nginx | grep -q "running"; then
  echo "Nginx 시작 중..."
  docker compose up -d nginx
  sleep 3
fi

# Certbot으로 인증서 발급
docker compose run --rm certbot \
  certonly --webroot \
  --webroot-path=/var/www/certbot \
  --email "${CERT_EMAIL}" \
  --agree-tos \
  --no-eff-email \
  -d "${DOMAIN}"

# Nginx 재시작 (인증서 적용)
docker compose exec nginx nginx -s reload

echo ""
echo "[$(date '+%Y-%m-%d %H:%M:%S')] SSL 인증서 발급 완료!"
echo ""
echo "동작 확인:"
echo "  curl https://${DOMAIN}/health"
echo ""
echo "자동 갱신 crontab 등록 (선택):"
echo "  crontab -e"
echo "  0 3 1,15 * * $(pwd)/certbot/renew.sh >> /var/log/cert-renew.log 2>&1"
