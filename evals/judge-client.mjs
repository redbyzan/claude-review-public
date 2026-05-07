/**
 * judge-client.mjs -- LLM-as-Judge client for eval-runner.
 *
 * Calls the Anthropic Messages API via fetch to evaluate review quality,
 * extracts scores, blends with keyword metrics, and computes
 * meta-evaluation agreement rates.
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const PROMPT_TEMPLATE_PATH = join(__dirname, "prompts", "judge-prompt.md");
const ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
const ANTHROPIC_API_VERSION = "2023-06-01";
const MAX_RETRIES = 3;

// ---------------------------------------------------------------------------
// Score extraction
// ---------------------------------------------------------------------------
/**
 * Parse the LLM response for the score pattern.
 * Looks for "Score: X/5" where X is a single digit.
 * @param {string} responseText
 * @returns {number|null} 1-5, or null if not found
 */
export function extractScore(responseText) {
  const match = responseText.match(/Score:\s*(\d)\/5/);
  if (match == null) return null;
  const score = parseInt(match[1], 10);
  return score >= 1 && score <= 5 ? score : null;
}

// ---------------------------------------------------------------------------
// Score blending
// ---------------------------------------------------------------------------
/**
 * Blend keyword hit rate with judge score using weighted average.
 * Formula: keywordRate * (1 - judgeWeight) + (judgeScore / 5) * judgeWeight
 * @param {number} keywordRate - 0.0 to 1.0
 * @param {number} judgeScore - 1 to 5
 * @param {number} [judgeWeight=0.2] - 0.0 to 1.0
 * @returns {number}
 */
export function blendScores(keywordRate, judgeScore, judgeWeight = 0.2) {
  if (judgeWeight < 0 || judgeWeight > 1) {
    throw new Error(
      `judgeWeight must be between 0.0 and 1.0 inclusive, got ${judgeWeight}`,
    );
  }
  return keywordRate * (1 - judgeWeight) + (judgeScore / 5) * judgeWeight;
}

// ---------------------------------------------------------------------------
// Meta-evaluation: agreement computation
// ---------------------------------------------------------------------------
/**
 * Compute agreement rate between judge and human labels.
 * Binarizes judgeScore >= 4 as "pass" (otherwise "fail").
 * @param {Record<string, {human: string, judge_score: number}>} labels
 * @returns {number} agreement rate 0.0-1.0
 */
export function computeAgreement(labels) {
  const entries = Object.entries(labels);
  if (entries.length === 0) return 0;

  let agreements = 0;
  for (const [, entry] of entries) {
    const judgeLabel = entry.judge_score >= 4 ? "pass" : "fail";
    if (judgeLabel === entry.human) agreements++;
  }
  return agreements / entries.length;
}

/**
 * Check if agreement rate meets the threshold.
 * @param {number} rate
 * @param {number} [threshold=0.7]
 * @returns {boolean}
 */
export function checkAgreementGate(rate, threshold = 0.7) {
  return rate >= threshold;
}

// ---------------------------------------------------------------------------
// Anthropic API call
// ---------------------------------------------------------------------------
/**
 * Call the Anthropic Messages API to evaluate a review.
 * @param {object} opts
 * @param {string} opts.reviewText
 * @param {string} opts.diff
 * @param {string} [opts.category="unknown"]
 * @param {string} [opts.apiKey=""]
 * @param {string} [opts.model="claude-sonnet-4-20250514"]
 * @param {string} [opts.severity="info"]
 * @returns {Promise<number>} judge score 1-5
 */
export async function callJudge({
  reviewText,
  diff,
  category = "unknown",
  apiKey = "",
  model = "claude-sonnet-4-20250514",
  severity = "info",
}) {
  // Resolve API key
  const resolvedKey = apiKey || process.env.ANTHROPIC_API_KEY || "";
  if (!resolvedKey) {
    console.error(
      "ERROR: ANTHROPIC_API_KEY is required for live judge mode. " +
        "Set the ANTHROPIC_API_KEY environment variable or pass --dry-run.",
    );
    process.exit(1);
  }

  // Read prompt template
  let promptText;
  try {
    promptText = readFileSync(PROMPT_TEMPLATE_PATH, "utf-8");
  } catch {
    console.error(
      `ERROR: Judge prompt template not found: ${PROMPT_TEMPLATE_PATH}`,
    );
    process.exit(1);
  }

  // Substitute placeholders
  promptText = promptText
    .replaceAll("{{diff}}", diff)
    .replaceAll("{{review}}", reviewText)
    .replaceAll("{{category}}", category)
    .replaceAll("{{severity}}", severity);

  // Build API request
  const body = {
    model,
    max_tokens: 1024,
    messages: [{ role: "user", content: promptText }],
  };

  // Call with exponential backoff on 429
  let responseText = "";
  for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
    try {
      const resp = await fetch(ANTHROPIC_API_URL, {
        method: "POST",
        headers: {
          "x-api-key": resolvedKey,
          "anthropic-version": ANTHROPIC_API_VERSION,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(60_000),
      });

      if (resp.status === 429) {
        const waitTime = 2 ** attempt;
        console.log(
          `  judge: rate limited (429), retry ${attempt + 1}/${MAX_RETRIES} in ${waitTime}s...`,
        );
        await new Promise((r) => setTimeout(r, waitTime * 1000));
        if (attempt === MAX_RETRIES - 1) {
          console.error(
            `ERROR: Judge API rate limit exceeded after ${MAX_RETRIES} retries`,
          );
          process.exit(1);
        }
        continue;
      }

      if (!resp.ok) {
        const bodyPreview = await resp.text();
        console.error(
          `ERROR: Judge API HTTP ${resp.status}: ${bodyPreview.slice(0, 300)}`,
        );
        process.exit(1);
      }

      const respData = await resp.json();
      const contentBlocks = respData.content || [];
      responseText = contentBlocks
        .filter((b) => b.type === "text")
        .map((b) => b.text)
        .join(" ");
      break;
    } catch (err) {
      if (err.name === "AbortError" || err.name === "TimeoutError") {
        console.error(`ERROR: Judge API call timed out: ${err}`);
        process.exit(1);
      }
      console.error(
        `ERROR: Judge API call failed: ${err.constructor.name}: ${err.message}`,
      );
      process.exit(1);
    }
  }

  // Extract score
  const score = extractScore(responseText);
  if (score == null) {
    console.error(
      `WARNING: Could not extract score from judge response. ` +
        `Response preview: ${responseText.slice(0, 200)}`,
    );
    return 3; // default to middle score
  }
  return score;
}
