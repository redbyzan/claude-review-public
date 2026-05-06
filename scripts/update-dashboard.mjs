#!/usr/bin/env node
// claude-review README 대시보드 자동 업데이트 스크립트
// GitHub Actions에서 실행되어 프로덕션 서버 상태를 README에 반영합니다.

import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, "..");
const README_PATH = resolve(REPO_ROOT, "README.md");

const SERVER_URL = process.env.SERVER_URL || "https://review.example.com";
const GITHUB_REPO = process.env.GITHUB_REPOSITORY || "your-username/claude-review";
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || "";

const START_MARKER = "<!-- DASHBOARD:START -->";
const END_MARKER = "<!-- DASHBOARD:END -->";

// --- Data Fetching ---

async function fetchJSON(url, label) {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(10000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } catch (e) {
    console.error(`[WARN] Failed to fetch ${label}: ${e.message}`);
    return null;
  }
}

async function fetchHealth() {
  return fetchJSON(`${SERVER_URL}/health`, "health");
}

async function fetchStats() {
  return fetchJSON(`${SERVER_URL}/api/dashboard/summary`, "stats");
}

async function fetchLeaderboard() {
  return fetchJSON(`${SERVER_URL}/api/dashboard/leaderboard?limit=5`, "leaderboard");
}

async function fetchRecentPRs() {
  if (!GITHUB_TOKEN) return [];
  try {
    const res = await fetch(
      `https://api.github.com/repos/${GITHUB_REPO}/pulls?state=closed&sort=updated&direction=desc&per_page=30`,
      {
        headers: {
          Authorization: `Bearer ${GITHUB_TOKEN}`,
          Accept: "application/vnd.github+json",
        },
        signal: AbortSignal.timeout(10000),
      }
    );
    if (!res.ok) return [];
    const prs = await res.json();
    return prs.filter((pr) => pr.merged_at).slice(0, 5);
  } catch {
    return [];
  }
}

async function fetchCIStatus() {
  if (!GITHUB_TOKEN) return null;
  try {
    // CI 워크플로우는 pull_request에서 실행되므로 브랜치 필터링 없이 최근 실행을 조회
    const res = await fetch(
      `https://api.github.com/repos/${GITHUB_REPO}/actions/runs?per_page=20`,
      {
        headers: {
          Authorization: `Bearer ${GITHUB_TOKEN}`,
          Accept: "application/vnd.github+json",
        },
        signal: AbortSignal.timeout(10000),
      }
    );
    if (!res.ok) return null;
    const data = await res.json();
    const ciRun = (data.workflow_runs || []).find(
      r => r.path?.includes("ci.yml") || r.name === "CI"
    );
    return ciRun || null;
  } catch {
    return null;
  }
}

// --- Markdown Generation ---

function statusBadge(label, value, color) {
  return `![${label}](https://img.shields.io/badge/${encodeURIComponent(label)}-${encodeURIComponent(value)}-${color})`;
}

function generateBadges(health, stats, ci) {
  const badges = [];

  // CI status
  if (ci) {
    const ciColor = ci.conclusion === "success" ? "brightgreen" : ci.conclusion === "failure" ? "red" : "yellow";
    badges.push(statusBadge("CI", ci.conclusion || ci.status, ciColor));
  } else {
    badges.push(statusBadge("CI", "unknown", "lightgrey"));
  }

  // Service health
  if (health) {
    const hColor = health.status === "ok" ? "brightgreen" : "red";
    badges.push(statusBadge("서비스", health.status === "ok" ? "UP" : "DOWN", hColor));
  } else {
    badges.push(statusBadge("서비스", "UNREACHABLE", "red"));
  }

  // Review count
  if (stats) {
    badges.push(statusBadge("리뷰", `${stats.totalReviews}건`, "blue"));
    badges.push(statusBadge("팀", `${stats.totalTeams}팀`, "blueviolet"));
    badges.push(statusBadge("성공률", stats.successRate.toFixed(1) + "%", stats.successRate >= 95 ? "brightgreen" : "yellow"));
  }

  return badges.join(" ");
}

function generateServiceTable(health) {
  if (!health) {
    return "| 항목 | 상태 |\n|------|------|\n| 서비스 | UNREACHABLE |";
  }
  const uptime = health.uptime || 0;
  const days = Math.floor(uptime / 86400);
  const hours = Math.floor((uptime % 86400) / 3600);
  const uptimeStr = days > 0 ? `${days}d ${hours}h` : `${hours}h`;

  return (
    "| 항목 | 상태 |\n|------|------|\n" +
    `| 서비스 상태 | ${health.status === "ok" ? "UP" : "DOWN"} |\n` +
    `| 업타임 | ${uptimeStr} |\n` +
    `| 역할 | ${health.role || "active"} |`
  );
}

function generateMetricsTable(stats) {
  if (!stats) return "| 항목 | 값 |\n|------|----|\n| 데이터 | 없음 |";

  const tokensStr =
    stats.totalTokens >= 1_000_000
      ? (stats.totalTokens / 1_000_000).toFixed(1) + "M"
      : stats.totalTokens >= 1_000
        ? (stats.totalTokens / 1_000).toFixed(1) + "K"
        : String(stats.totalTokens);

  return (
    "| 항목 | 값 |\n|------|----|\n" +
    `| 총 리뷰 | ${stats.totalReviews} |\n` +
    `| 참여 팀 | ${stats.totalTeams} |\n` +
    `| 성공률 | ${stats.successRate.toFixed(1)}% |\n` +
    `| 총 토큰 | ${tokensStr} |\n` +
    `| 평균 토큰/리뷰 | ${stats.avgTokensPerReview.toLocaleString()} |\n` +
    `| 멘토 리뷰 | ${stats.mentorCount} |\n` +
    `| 시니컬 리뷰 | ${stats.cynicCount} |`
  );
}

function generateFeatureTable(stats) {
  if (!stats) return "";
  return (
    "| 기능 | 건수 |\n|------|------|\n" +
    `| CTA 삽입 | ${stats.ctaInsertions} |\n` +
    `| 반박(Rebuttal) | ${stats.rebuttalRequests} |\n` +
    `| 대화(Conversation) | ${stats.conversationRequests} |\n` +
    `| Gemini 폴백 성공 | ${stats.fallbackSuccess} |`
  );
}

function generateModelTable(stats) {
  if (!stats || !stats.modelCounts || Object.keys(stats.modelCounts).length === 0) return "";

  const sorted = Object.entries(stats.modelCounts).sort((a, b) => b[1] - a[1]);
  const total = sorted.reduce((s, [, v]) => s + v, 0);

  let table = "| 모델 | 리뷰 | 비율 |\n|------|------|------|\n";
  for (const [model, count] of sorted) {
    const pct = total > 0 ? ((count / total) * 100).toFixed(1) : "0.0";
    const shortName = model.replace("claude-", "").replace("-2025", "");
    table += `| ${shortName} | ${count} | ${pct}% |\n`;
  }
  return table.trimEnd();
}

function generateLeaderboardTable(teams) {
  if (!teams || teams.length === 0) return "";

  let table = "| 순위 | 팀 | 리뷰 | 토큰 | 최근 리뷰 |\n|------|-----|------|------|----------|\n";
  teams.forEach((t, i) => {
    const tokens = t.totalTokens >= 1000 ? (t.totalTokens / 1000).toFixed(1) + "K" : String(t.totalTokens);
    const prInfo = t.lastPrNumber > 0 ? `[#${t.lastPrNumber}](https://github.com/${t.repo || GITHUB_REPO}/pull/${t.lastPrNumber})` : "-";
    table += `| ${i + 1} | ${t.teamName} | ${t.reviewCount} | ${tokens} | ${prInfo} |\n`;
  });
  return table.trimEnd();
}

function generateRecentActivity(prs) {
  if (!prs || prs.length === 0) return "";

  let table = "| 날짜 | PR | 상태 |\n|------|-----|------|\n";
  for (const pr of prs) {
    const date = pr.merged_at ? pr.merged_at.slice(0, 10) : pr.updated_at.slice(0, 10);
    const title = (pr.title.length > 40 ? pr.title.slice(0, 37) + "..." : pr.title).replace(/\|/g, "\\|");
    table += `| ${date} | [#${pr.number} ${title}](${pr.html_url}) | merged |\n`;
  }
  return table.trimEnd();
}

// --- Mermaid Diagram (static) ---

function generateArchitectureDiagram() {
  return `\`\`\`mermaid
graph LR
    A[GitHub PR] -->|webhook| B[GitHub Actions]
    B -->|POST /review| C[Cloudflare Tunnel]
    C --> D[Nginx Proxy]
    D --> E[Spring Boot :8080]
    E -->|Claude API| F[Anthropic Claude]
    E -->|fallback| G[Gemini CLI Proxy]
    E --> H[Review Logs]
    H --> I[Leaderboard / Stats]
    E --> J[Prometheus]
    J --> K[Grafana Dashboard]
    E -->|Slack webhook| L[Alert Notifications]
\`\`\``;
}

// --- Main ---

async function main() {
  console.log("[INFO] Fetching data...");

  const [health, stats, leaderboard, prs, ci] = await Promise.all([
    fetchHealth(),
    fetchStats(),
    fetchLeaderboard(),
    fetchRecentPRs(),
    fetchCIStatus(),
  ]);

  if (!health && !stats) {
    console.warn("[WARN] All data sources unreachable. Generating UNREACHABLE dashboard.");
  }

  // Build dashboard content
  const sections = [];

  sections.push(`> 마지막 업데이트: ${new Date().toISOString().slice(0, 19).replace("T", " ")} UTC`);

  sections.push("");
  sections.push("### 서비스 상태");
  sections.push(generateServiceTable(health));

  if (stats) {
    sections.push("");
    sections.push("### 리뷰 통계");
    sections.push(generateMetricsTable(stats));

    sections.push("");
    sections.push("### 기능 사용량");
    sections.push(generateFeatureTable(stats));

    if (Object.keys(stats.modelCounts || {}).length > 0) {
      sections.push("");
      sections.push("### 모델별 사용량");
      sections.push(generateModelTable(stats));
    }
  }

  if (leaderboard && leaderboard.length > 0) {
    sections.push("");
    sections.push("### 팀 리더보드 (Top 5)");
    sections.push(generateLeaderboardTable(leaderboard));
  }

  if (prs && prs.length > 0) {
    sections.push("");
    sections.push("### 최근 활동");
    sections.push(generateRecentActivity(prs));
  }

  sections.push("");
  sections.push("### 아키텍처");
  sections.push(generateArchitectureDiagram());

  const dashboardContent = sections.join("\n");

  // Update badges
  const badges = generateBadges(health, stats, ci);

  // Read existing README
  let readme;
  try {
    readme = readFileSync(README_PATH, "utf-8");
  } catch {
    console.error(`[ERROR] README not found at ${README_PATH}`);
    process.exit(1);
  }

  // Replace dashboard section
  const startIdx = readme.indexOf(START_MARKER);
  const endIdx = readme.indexOf(END_MARKER);

  if (startIdx === -1 || endIdx === -1) {
    console.error("[ERROR] Dashboard markers not found in README");
    process.exit(1);
  }

  // Replace badges
  const badgeStartMarker = "<!-- BADGES:START -->";
  const badgeEndMarker = "<!-- BADGES:END -->";
  let updated = readme;

  const badgeStartIdx = updated.indexOf(badgeStartMarker);
  const badgeEndIdx = updated.indexOf(badgeEndMarker);
  if (badgeStartIdx !== -1 && badgeEndIdx !== -1) {
    updated =
      updated.slice(0, badgeStartIdx + badgeStartMarker.length) +
      "\n" + badges + "\n" +
      updated.slice(badgeEndIdx);
  }

  // Replace dashboard content
  const newStartIdx = updated.indexOf(START_MARKER);
  const newEndIdx = updated.indexOf(END_MARKER);
  updated =
    updated.slice(0, newStartIdx + START_MARKER.length) +
    "\n" +
    dashboardContent +
    "\n" +
    updated.slice(newEndIdx);

  // Check if content changed
  if (updated === readme) {
    console.log("[INFO] No changes detected. Skipping commit.");
    process.exit(0);
  }

  writeFileSync(README_PATH, updated, "utf-8");
  console.log("[INFO] README updated successfully.");
}

main().catch((e) => {
  console.error(`[FATAL] ${e.message}`);
  process.exit(1);
});
