#!/bin/bash
# dns-common.sh — Cloudflare DNS 전환 공통 함수
# source하여 사용: source "$(dirname "$0")/dns-common.sh"

get_dns_record_id() {
    local result
    result=$(curl -sf -X GET \
        "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/dns_records?name=${DOMAIN}&type=CNAME" \
        -H "Authorization: Bearer ${CF_API_TOKEN}" \
        -H "Content-Type: application/json" \
    | python3 -c "
import sys, json
data = json.load(sys.stdin)
if not data.get('success'):
    print('ERROR: API 호출 실패', file=sys.stderr)
    sys.exit(1)
records = data.get('result', [])
if not records:
    print('ERROR: DNS 레코드 없음', file=sys.stderr)
    sys.exit(1)
print(records[0]['id'])
" 2>&1)

    if [ $? -ne 0 ]; then
        echo "ERROR: $result" >&2
        return 1
    fi
    echo "$result"
}

switch_dns() {
    local target_tunnel="$1"
    local record_id
    record_id=$(get_dns_record_id) || return 1

    local response
    response=$(curl -sf -X PUT \
        "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/dns_records/${record_id}" \
        -H "Authorization: Bearer ${CF_API_TOKEN}" \
        -H "Content-Type: application/json" \
        --data "{
            \"type\": \"CNAME\",
            \"name\": \"${DOMAIN}\",
            \"content\": \"${target_tunnel}.cfargotunnel.com\",
            \"ttl\": 1,
            \"proxied\": true
        }" 2>/dev/null)

    if echo "$response" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if not data.get('success'):
    errors = data.get('errors', [])
    print(f'ERROR: {errors}', file=sys.stderr)
    sys.exit(1)
" 2>&1; then
        return 0
    else
        echo "ERROR: DNS 전환 실패 — $response" >&2
        return 1
    fi
}
