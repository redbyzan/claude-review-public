// ============================================================
// server.js — Claude 코드리뷰 서버
//
// 엔드포인트:
//   GET  /health  — 헬스체크 (인증 없음)
//   POST /review  — 코드리뷰 (시크릿 + GitHub 인증 필요)
// ============================================================

import Anthropic  from "@anthropic-ai/sdk";
import express    from "express";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join, dirname } from "node:path";
import { verifyRequest } from "./middleware/auth.js";
import { rateLimiter   } from "./middleware/rateLimiter.js";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ── 초기화 ───────────────────────────────────────────────────────────────
const app = express();

// z.ai Claude 호환 게이트웨이 설정
// 출처: web-content-curator/src/api/providers/ClaudeProvider.ts
const ZAI_ANTHROPIC_BASE_URL = "https://api.z.ai/api/anthropic";
const ZAI_GATEWAY_MODEL      = "glm-5";
const ANTHROPIC_KEY_PATTERN   = /^sk-ant-/i;

const rawApiKey  = process.env.ANTHROPIC_API_KEY || "";
const isZaiKey   = !ANTHROPIC_KEY_PATTERN.test(rawApiKey) && rawApiKey.length > 0;

const claude = isZaiKey
  ? new Anthropic({
      authToken: rawApiKey,
      baseURL:   ZAI_ANTHROPIC_BASE_URL,
    })
  : new Anthropic({ apiKey: rawApiKey });

const model = isZaiKey ? ZAI_GATEWAY_MODEL : "claude-sonnet-4-20250514";

// ── 글로벌 미들웨어 ──────────────────────────────────────────────────────
app.use(express.json({ limit: "512kb" }));
app.use(rateLimiter);               // 모든 경로에 rate limit 적용
app.set("trust proxy", 1);          // Nginx 프록시 뒤에서 실제 IP 추출

// ── 헬스체크 (인증 없음 — Nginx upcheck / Docker healthcheck 용) ─────────
app.get("/health", (_, res) => {
  res.json({
    status:    "ok",
    timestamp: new Date().toISOString(),
    uptime:    Math.floor(process.uptime()),
  });
});

// ── 코드리뷰 엔드포인트 ─────────────────────────────────────────────────
app.post("/review", verifyRequest, async (req, res) => {
  const {
    diff,
    pr_title   = "(제목 없음)",
    pr_author  = "unknown",
    repo       = "unknown",
    pr_number  = 0,
    base_branch = "main",
  } = req.body;

  // diff 유효성 검사
  if (!diff || typeof diff !== "string" || diff.trim().length < 50) {
    return res.status(400).json({ error: "diff가 없거나 너무 짧습니다." });
  }

  const label = `${repo}#${pr_number}`;
  const start = Date.now();

  console.log(`[Review] START — ${label} | author:${pr_author} | "${pr_title}"`);

  // z.ai rate limit 대비 최대 3회 재시도 (지수 백오프)
  const MAX_RETRIES = 3;
  let lastError;

  for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
    try {
      const message = await claude.messages.create({
        model:      model,
        max_tokens: 2000,
        system:     buildSystemPrompt(),
        messages: [{
          role:    "user",
          content: buildUserPrompt({ diff, pr_title, pr_author, repo, pr_number, base_branch }),
        }],
      });

      const review  = message.content[0].text;
      const usage   = message.usage;
      const elapsed = ((Date.now() - start) / 1000).toFixed(1);
      const cost    = estimateCost(usage);

      console.log(
        `[Review] DONE  — ${label} | ` +
        `in:${usage.input_tokens} out:${usage.output_tokens} | ` +
        `~$${cost} | ${elapsed}s`
      );

      return res.json({ review, usage, model: message.model, elapsed_ms: Date.now() - start });

    } catch (err) {
      lastError = err;
      if (err.status === 429 && attempt < MAX_RETRIES - 1) {
        const delay = 5000 * Math.pow(2, attempt); // 5s, 10s, 20s
        console.warn(`[Review] 429 rate limit — ${label} | ${delay / 1000}s 후 재시도 (${attempt + 1}/${MAX_RETRIES})`);
        await new Promise(r => setTimeout(r, delay));
        continue;
      }
      break;
    }
  }

  // 모든 재시도 실패
  const err = lastError;
  const elapsed = ((Date.now() - start) / 1000).toFixed(1);
  console.error(`[Review] ERROR — ${label} | ${elapsed}s | ${err.message}`);

  if (err.status === 429) {
    return res.status(429).json({ error: "API rate limit 초과. 잠시 후 재시도하세요." });
  }
  if (err.status === 401) {
    return res.status(500).json({ error: "API 키 오류. 서버 설정을 확인하세요." });
  }

  res.status(502).json({ error: "리뷰 생성 실패", detail: err.message });
});

// ── 미등록 경로 — 정보 노출 없이 차단 ────────────────────────────────────
app.use((req, res) => {
  console.warn(`[Server] 404 — ${req.method} ${req.path}`);
  res.status(404).end();
});

// ── 미처리 예외 ───────────────────────────────────────────────────────────
process.on("uncaughtException",     (err) => console.error("[Fatal] uncaughtException:",     err));
process.on("unhandledRejection",    (err) => console.error("[Fatal] unhandledRejection:",    err));

// ── 서버 시작 ─────────────────────────────────────────────────────────────
const PORT = parseInt(process.env.SERVER_PORT || "3000", 10);
app.listen(PORT, "0.0.0.0", () => {
  console.log(`[Server] 코드리뷰 서버 시작 — port:${PORT}`);
  console.log(`[Server] 모드: ${isZaiKey ? "z.ai 게이트웨이 (glm-5)" : "Anthropic 직접 (claude-sonnet-4)"}`);
  console.log(`[Server] 허용 Owner: ${process.env.ALLOWED_GITHUB_OWNERS || "(전체 GitHub)"}`);
});

// ── 프롬프트 빌더 (라운드로빈) ────────────────────────────────────────────
const PROMPTS_DIR = join(__dirname, "prompts");
const PROMPT_FILES = readdirSync(PROMPTS_DIR)
  .filter(f => f.endsWith(".md"))
  .sort();
let promptIndex = 0;

function buildSystemPrompt() {
  const file = PROMPT_FILES[promptIndex % PROMPT_FILES.length];
  console.log(`[Prompt] ${promptIndex % PROMPT_FILES.length + 1}/${PROMPT_FILES.length} | ${file}`);
  promptIndex++;
  return readFileSync(join(PROMPTS_DIR, file), "utf-8");
}

function buildUserPrompt({ diff, pr_title, pr_author, repo, pr_number, base_branch }) {
  // 너무 긴 diff는 앞부분 10000자만 사용
  const truncated = diff.length > 10000;
  const diffSlice = diff.slice(0, 10000);

  return `PR 정보:
- 저장소: ${repo}
- PR #${pr_number}: ${pr_title}
- 작성자: ${pr_author}
- 베이스 브랜치: ${base_branch}
${truncated ? "⚠️ diff가 길어 앞부분 10,000자만 리뷰합니다.\n" : ""}
\`\`\`diff
${diffSlice}
\`\`\`

다음 형식으로 리뷰해주세요:

## ✅ 잘된 점

## 🔧 개선 제안

## 🚨 보안 / 성능 주의
(없으면 "특이사항 없음"으로 표기)

## 💡 학습 포인트
이 PR에서 더 공부하면 좋을 개념 1~2가지`;
}

// claude-sonnet-4 기준 비용 추정 (USD)
function estimateCost({ input_tokens, output_tokens }) {
  return ((input_tokens * 3 + output_tokens * 15) / 1_000_000).toFixed(5);
}
