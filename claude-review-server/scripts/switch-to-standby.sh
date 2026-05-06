#!/bin/bash
# switch-to-standby.sh — 수동으로 Standby(개발PC)로 전환
# 사용: CF_ZONE_ID=xxx CF_API_TOKEN=xxx TUNNEL_B_ID=xxx bash scripts/switch-to-standby.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/dns-common.sh"

TUNNEL_B_ID="${TUNNEL_B_ID:?TUNNEL_B_ID 필요}"
CF_ZONE_ID="${CF_ZONE_ID:?CF_ZONE_ID 필요}"
CF_API_TOKEN="${CF_API_TOKEN:?CF_API_TOKEN 필요}"
DOMAIN="${DOMAIN:-review.example.com}"

echo "Standby(Tunnel B)로 전환 중..."
if switch_dns "$TUNNEL_B_ID"; then
    echo "성공! 트래픽이 Standby로 전환됨"
else
    echo "실패!" >&2
    exit 1
fi
