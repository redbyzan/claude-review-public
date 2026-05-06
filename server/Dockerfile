FROM node:22-alpine

# 보안 업데이트
RUN apk update && apk upgrade && apk add --no-cache wget

WORKDIR /app

# 의존성 먼저 복사 (Docker 레이어 캐시 최적화)
COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

# 소스 복사
COPY . .

# 비루트 사용자로 실행 (보안)
RUN addgroup -S reviewer && adduser -S reviewer -G reviewer
USER reviewer

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1

CMD ["node", "server.js"]
