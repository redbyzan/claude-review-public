#!/bin/bash
# switch-to-primary.sh — 수동으로 Active(홈서버)로 전환
# 사용: CF_ZONE_ID=xxx CF_API_TOKEN=xxx TUNNEL_A_ID=xxx bash scripts/switch-to-primary.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/dns-common.sh"

TUNNEL_A_ID="${TUNNEL_A_ID:?TUNNEL_A_ID 필요}"
CF_ZONE_ID="${CF_ZONE_ID:?CF_ZONE_ID 필요}"
CF_API_TOKEN="${CF_API_TOKEN:?CF_API_TOKEN 필요}"
DOMAIN="${DOMAIN:-review.example.com}"

echo "Primary(Tunnel A)로 전환 중..."
if switch_dns "$TUNNEL_A_ID"; then
    echo "성공! 트래픽이 Primary로 전환됨"
else
    echo "실패!" >&2
    exit 1
fi
