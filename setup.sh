#!/usr/bin/env bash
# ============================================================
# setup.sh — Claude Review Server 최초 설치 스크립트
#
# 실행: bash setup.sh
# ============================================================

set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }
ask()   { echo -e "${YELLOW}[INPUT]${NC} $*"; }

echo ""
echo "======================================================"
echo "  Claude Review Server — 설치 시작"
echo "======================================================"
echo ""

# ── 사전 요구사항 확인 ──────────────────────────────────────────────────
info "Docker 확인 중..."
command -v docker >/dev/null 2>&1 || error "Docker가 설치되어 있지 않습니다."
docker compose version >/dev/null 2>&1 || error "Docker Compose가 설치되어 있지 않습니다."
info "Docker OK"

# ── .env 파일 생성 ──────────────────────────────────────────────────────
if [ -f ".env" ]; then
  warn ".env 파일이 이미 존재합니다. 덮어쓰지 않습니다."
else
  info ".env 파일 생성 중..."
  cp .env.example .env

  ask "Anthropic API 키를 입력하세요 (sk-ant-...):"
  read -r API_KEY
  sed -i "s|sk-ant-여기에_API_키_입력|${API_KEY}|g" .env

  ask "도메인을 입력하세요 (예: review.yourdomain.com):"
  read -r DOMAIN
  sed -i "s|review.yourdomain.com|${DOMAIN}|g" .env

  ask "Let's Encrypt 이메일을 입력하세요:"
  read -r EMAIL
  sed -i "s|your@email.com|${EMAIL}|g" .env

  ask "허용할 GitHub Owner를 입력하세요 (콤마 구분, 예: myorg,myuser):"
  read -r OWNERS
  sed -i "s|your-org,your-username|${OWNERS}|g" .env

  # 랜덤 시크릿 자동 생성
  SECRET=$(openssl rand -hex 32)
  sed -i "s|여기에_랜덤_시크릿_키_입력|${SECRET}|g" .env
  info "시크릿 키 자동 생성 완료: ${SECRET}"
  warn "이 시크릿 키를 GitHub Secrets > REVIEW_SECRET 에 등록하세요!"

  info ".env 파일 생성 완료"
fi

# ── nginx.conf 도메인 치환 ──────────────────────────────────────────────
DOMAIN=$(grep '^DOMAIN=' .env | cut -d= -f2)
if [ -n "$DOMAIN" ]; then
  info "nginx.conf 도메인 설정 중: ${DOMAIN}"
  sed -i "s|review.yourdomain.com|${DOMAIN}|g" nginx/nginx.conf
fi

# ── ssl 디렉토리 생성 ───────────────────────────────────────────────────
mkdir -p nginx/ssl
chmod 600 nginx/ssl 2>/dev/null || true

# ── certbot chmod ───────────────────────────────────────────────────────
chmod +x certbot/renew.sh

echo ""
echo "======================================================"
echo "  다음 단계를 순서대로 진행하세요"
echo "======================================================"
echo ""
echo "1. 공유기 포트포워딩 설정"
echo "   80  → 이 PC의 로컬 IP"
echo "   443 → 이 PC의 로컬 IP"
echo ""
echo "2. AWS Route53에서 CNAME 또는 A 레코드 설정"
echo "   ${DOMAIN} → 공인 IP"
echo ""
echo "3. SSL 인증서 최초 발급 (포트포워딩 확인 후 실행)"
echo "   bash certbot/issue-cert.sh"
echo ""
echo "4. 서버 실행"
echo "   docker compose up -d"
echo ""
echo "5. 동작 확인"
echo "   curl https://${DOMAIN}/health"
echo ""
echo "6. GitHub Secrets 등록 (레포 Settings → Secrets)"
echo "   REVIEW_SERVER_URL = https://${DOMAIN}"
echo "   REVIEW_SECRET     = (위에서 생성된 시크릿 키)"
echo ""
