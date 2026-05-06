// ============================================================
// auth.js — 요청 인증 미들웨어
//
// 검증 단계:
//  1. x-review-secret 헤더 — 공유 시크릿 키 일치 여부
//  2. x-github-repo 헤더   — GitHub 레포 정보 존재 여부
//  3. GitHub Owner 화이트리스트 — 허용된 조직/계정 여부
//  4. Content-Type          — application/json 여부
//
// 모든 실패 → 404 반환 (공격자에게 이유 노출 차단)
// ============================================================

export function verifyRequest(req, res, next) {
  const errors = [];
  const ip = getClientIp(req);

  // ── 1. 공유 시크릿 키 검증 ────────────────────────────────────────────
  const secret   = req.headers["x-review-secret"] || "";
  const expected = process.env.REVIEW_SECRET       || "";

  if (!secret || secret !== expected) {
    errors.push("invalid_secret");
  }

  // ── 2. GitHub 레포 헤더 검증 ──────────────────────────────────────────
  // GitHub Actions 워크플로우에서 x-github-repo: owner/repo 형태로 전송
  const ghRepo = req.headers["x-github-repo"] || "";
  if (!ghRepo || !ghRepo.includes("/")) {
    errors.push("missing_github_repo");
  }

  // ── 3. 허용된 GitHub Owner 검증 ───────────────────────────────────────
  const allowedOwners = process.env.ALLOWED_GITHUB_OWNERS || "";
  if (allowedOwners && ghRepo && ghRepo.includes("/")) {
    const owner   = ghRepo.split("/")[0].trim().toLowerCase();
    const allowed = allowedOwners
      .split(",")
      .map(s => s.trim().toLowerCase())
      .filter(Boolean);

    if (allowed.length > 0 && !allowed.includes(owner)) {
      errors.push(`unauthorized_owner:${owner}`);
    }
  }

  // ── 4. Content-Type 검증 ──────────────────────────────────────────────
  if (!req.is("application/json")) {
    errors.push("invalid_content_type");
  }

  // ── 결과 처리 ─────────────────────────────────────────────────────────
  if (errors.length > 0) {
    console.warn(`[Auth] BLOCKED — ip:${ip} repo:"${ghRepo}" reason:[${errors.join(",")}]`);
    // 항상 404 — 엔드포인트 존재 여부조차 노출 안 함
    return res.status(404).end();
  }

  // 통과 — 이후 핸들러에서 사용할 수 있도록 주입
  req.githubRepo = ghRepo;
  req.clientIp   = ip;
  console.log(`[Auth] PASS — ip:${ip} repo:${ghRepo}`);
  next();
}

function getClientIp(req) {
  return (
    (req.headers["x-forwarded-for"] || "").split(",")[0].trim() ||
    req.socket.remoteAddress ||
    "unknown"
  );
}
