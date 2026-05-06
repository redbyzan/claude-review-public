#!/usr/bin/env bash
# ============================================================
# renew.sh — Let's Encrypt 인증서 자동 갱신
#
# crontab 등록 방법:
#   crontab -e
#   0 3 1,15 * * /path/to/claude-review/certbot/renew.sh >> /var/log/cert-renew.log 2>&1
#   (매월 1일, 15일 새벽 3시 실행)
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] SSL 인증서 갱신 시작"

cd "$PROJECT_DIR"

# Certbot으로 갱신 시도 (만료 30일 이내일 때만 실제 갱신)
docker compose run --rm \
  -v ./nginx/ssl:/etc/letsencrypt \
  -v certbot-webroot:/var/www/certbot \
  certbot renew --quiet

# 갱신 후 Nginx 재시작 (새 인증서 적용)
docker compose exec nginx nginx -s reload

echo "[$(date '+%Y-%m-%d %H:%M:%S')] SSL 인증서 갱신 완료"
