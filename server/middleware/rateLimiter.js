// ============================================================
// rateLimiter.js — 인메모리 Rate Limiter
//
// 기본값: IP당 분당 최대 10회
// GitHub Actions runner는 IP가 매번 달라지므로
// 너무 낮게 잡으면 정상 요청이 막힐 수 있음
// ============================================================

const WINDOW_MS = 60 * 1000;   // 1분 윈도우
const MAX_REQ   = 10;          // IP당 분당 최대 요청 수

// { ip -> { count, windowStart } }
const store = new Map();

// 만료된 항목 5분마다 정리 (메모리 누수 방지)
setInterval(() => {
  const now     = Date.now();
  let   deleted = 0;
  for (const [ip, data] of store) {
    if (now - data.windowStart > WINDOW_MS * 2) {
      store.delete(ip);
      deleted++;
    }
  }
  if (deleted > 0) {
    console.log(`[RateLimit] cleanup: ${deleted}개 항목 제거`);
  }
}, 5 * 60 * 1000);

export function rateLimiter(req, res, next) {
  const ip  = getClientIp(req);
  const now = Date.now();

  let data = store.get(ip);

  // 새 IP이거나 윈도우 만료 → 초기화
  if (!data || now - data.windowStart > WINDOW_MS) {
    data = { count: 1, windowStart: now };
    store.set(ip, data);
    return next();
  }

  data.count++;

  if (data.count > MAX_REQ) {
    const resetIn = Math.ceil((data.windowStart + WINDOW_MS - now) / 1000);
    console.warn(`[RateLimit] BLOCKED — ip:${ip} count:${data.count} resetIn:${resetIn}s`);
    return res
      .status(429)
      .set("Retry-After", String(resetIn))
      .json({ error: "Too many requests", retryAfter: resetIn });
  }

  next();
}

function getClientIp(req) {
  return (
    (req.headers["x-forwarded-for"] || "").split(",")[0].trim() ||
    req.socket.remoteAddress ||
    "unknown"
  );
}
