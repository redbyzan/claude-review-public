#!/usr/bin/env node

/**
 * eval-runner.mjs -- AI Code Review Bot evaluation runner.
 *
 * Reads golden dataset YAML files, calls POST /review for each case,
 * evaluates must_contain keywords in the response, and prints a
 * category-level summary as plain text.
 *
 * Exit codes:
 *     0  pass_rate >= threshold (default 80%)
 *     1  pass_rate < 50%  OR  fatal setup error
 *     2  pass_rate 50% .. threshold-1
 *     3  regression detected (baseline comparison failed)
 */

import { program } from "commander";
import { execSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, join, basename } from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";
import {
  blendScores,
  callJudge,
  checkAgreementGate,
  computeAgreement,
} from "./judge-client.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const DEFAULT_CONFIG_PATH = join(__dirname, "eval-config.yaml");
const DEFAULT_DATASET_DIR = join(__dirname, "golden-dataset");
const REQUEST_TIMEOUT_MS = 90_000;

// Severity ordering: higher index = more severe
const SEVERITY_ORDER = { info: 0, warning: 1, critical: 2 };

// ---------------------------------------------------------------------------
// Severity detection
// ---------------------------------------------------------------------------
/**
 * Parse review text for emoji severity markers.
 * @param {string} reviewText
 * @returns {"critical"|"warning"|"info"}
 */
export function detectSeverity(reviewText) {
  const hasCritical = reviewText.includes("\u{1F534}"); // red circle
  const hasWarning = reviewText.includes("\u{1F7E1}"); // yellow circle
  const hasInfo = reviewText.includes("\u{1F7E2}"); // green circle

  if (hasCritical) return "critical";
  if (hasWarning) return "warning";
  if (hasInfo) return "info";

  // Text fallback: check for severity words in headings
  let textSeverity = "info";
  for (const line of reviewText.split("\n")) {
    const stripped = line.trim();
    if (stripped.startsWith("#")) {
      const lower = stripped.toLowerCase();
      if (lower.includes("critical")) {
        textSeverity = "critical";
      } else if (lower.includes("warning")) {
        if ((SEVERITY_ORDER[textSeverity] || 0) < SEVERITY_ORDER.warning) {
          textSeverity = "warning";
        }
      }
    }
  }
  return textSeverity;
}

/**
 * @param {string} detected
 * @param {string} expected
 * @returns {boolean}
 */
function compareSeverity(detected, expected) {
  return detected === expected;
}

// ---------------------------------------------------------------------------
// Config loading
// ---------------------------------------------------------------------------
/**
 * @param {string} configPath
 * @returns {object}
 */
function loadConfig(configPath) {
  try {
    const raw = readFileSync(configPath, "utf-8");
    return yaml.load(raw);
  } catch {
    console.error(`ERROR: Config file not found: ${configPath}`);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------
// Recursive glob (stdlib-only)
// ---------------------------------------------------------------------------
function globYaml(dir) {
  const results = [];
  function walk(d) {
    for (const entry of readdirSync(d, { withFileTypes: true })) {
      const full = join(d, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile() && full.endsWith(".yaml")) {
        results.push(full);
      }
    }
  }
  walk(dir);
  return results.sort();
}

// ---------------------------------------------------------------------------
// Golden dataset loading
// ---------------------------------------------------------------------------
/**
 * @param {string} datasetDir
 * @param {object} [opts]
 * @param {number} [opts.limit]
 * @param {string} [opts.category]
 * @returns {object[]}
 */
export function loadTestCases(datasetDir, { limit, category } = {}) {
  const yamlFiles = globYaml(datasetDir);
  if (yamlFiles.length === 0) {
    console.error(`ERROR: No golden YAML files found in ${datasetDir}`);
    process.exit(1);
  }

  const cases = [];
  for (const fp of yamlFiles) {
    const data = yaml.load(readFileSync(fp, "utf-8"));
    const inp = data.input || {};
    const expected = data.expected || {};
    const source = data.source || {};
    const cat = data.category || "unknown";

    if (category != null && cat !== category) continue;

    cases.push({
      id: data.id || basename(fp, ".yaml"),
      category: cat,
      diff: inp.diff || "",
      pr_title: inp.pr_title || "(no title)",
      pr_author: inp.pr_author || "unknown",
      repo: inp.repo || "unknown",
      persona: inp.persona || "",
      must_contain: expected.must_contain || [],
      severity: expected.severity || "info",
      source_type: source.type || "unknown",
      file_path: fp,
    });
  }

  return limit != null ? cases.slice(0, limit) : cases;
}

// ---------------------------------------------------------------------------
// API call
// ---------------------------------------------------------------------------
/**
 * POST /review with the test case input.
 * @param {object} testCase
 * @param {string} serverUrl
 * @param {string} secret
 * @returns {Promise<object>}
 */
async function callReviewApi(testCase, serverUrl, secret) {
  const url = `${serverUrl.replace(/\/+$/, "")}/review`;
  const body = {
    diff: testCase.diff,
    pr_title: testCase.pr_title,
    pr_author: testCase.pr_author,
    repo: testCase.repo,
  };

  const resp = await fetch(url, {
    method: "POST",
    headers: {
      "x-review-secret": secret,
      "x-github-repo": testCase.repo,
      "Content-Type": "application/json",
      "User-Agent": "claude-review-eval-runner/1.0",
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });

  if (!resp.ok) {
    const errBody = await resp.text();
    const err = new Error(`HTTP ${resp.status}: ${errBody.slice(0, 300)}`);
    err.status = resp.status;
    throw err;
  }
  return resp.json();
}

// ---------------------------------------------------------------------------
// Keyword evaluation
// ---------------------------------------------------------------------------
/**
 * @param {string} reviewText
 * @param {string[]} mustContain
 * @returns {{ hits: number, missing: string[] }}
 */
export function evaluateKeywords(reviewText, mustContain) {
  const lower = reviewText.toLowerCase();
  let hits = 0;
  const missing = [];
  for (const kw of mustContain) {
    if (lower.includes(kw.toLowerCase())) {
      hits++;
    } else {
      missing.push(kw);
    }
  }
  return { hits, missing };
}

// ---------------------------------------------------------------------------
// Baseline save/load
// ---------------------------------------------------------------------------
function saveBaseline(path, data) {
  mkdirSync(dirname(path), { recursive: true });
  if (!data.metadata) data.metadata = {};
  const meta = data.metadata;
  if (!meta.date) meta.date = new Date().toISOString();
  if (!meta.commit) {
    try {
      meta.commit = execSync("git rev-parse --short HEAD", {
        stderr: "ignore",
        encoding: "utf-8",
      }).trim();
    } catch {
      meta.commit = "unknown";
    }
  }
  if (!meta.model) meta.model = "eval-runner";
  writeFileSync(path, JSON.stringify(data, null, 2), "utf-8");
  console.log(`Baseline saved to ${path}`);
}

function loadBaseline(path) {
  return JSON.parse(readFileSync(path, "utf-8"));
}

// ---------------------------------------------------------------------------
// Regression gate
// ---------------------------------------------------------------------------
/**
 * @param {number} currentRate
 * @param {object} baselineData
 * @param {number} [dropThreshold=0.05]
 * @returns {{ isRegression: boolean, detail: string }}
 */
function checkRegression(currentRate, baselineData, dropThreshold = 0.05) {
  const baselineRate = baselineData.overall_pass_rate || 0;
  const drop = baselineRate - currentRate;

  if (drop >= dropThreshold) {
    return {
      isRegression: true,
      detail: (
        `REGRESSION DETECTED: pass_rate dropped from ` +
        `${fmtPct(baselineRate)} to ${fmtPct(currentRate)} ` +
        `(-${fmtPct(drop)}, threshold: -${fmtPct(dropThreshold)})`
      ),
    };
  }
  return {
    isRegression: false,
    detail: (
      `baseline comparison: ${fmtPct(baselineRate)} -> ${fmtPct(currentRate)} ` +
      `(delta: ${drop >= 0 ? "" : "+"}${fmtPct(drop)}, threshold: -${fmtPct(dropThreshold)})`
    ),
  };
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------
function fmtPct(n) {
  return `${Math.round(n * 100)}%`;
}

// ---------------------------------------------------------------------------
// Plain-text table output
// ---------------------------------------------------------------------------
/**
 * @param {object[]} results
 * @param {number} threshold
 * @returns {number} overall pass_rate
 */
function printResultsTable(results, threshold) {
  const categories = {};
  for (const r of results) {
    (categories[r.category] ??= []).push(r);
  }

  let totalHits = 0;
  let totalKeywords = 0;

  console.log();
  console.log(
    "Category".padEnd(15) +
    "Cases".padStart(6) +
    "Hits".padStart(11) +
    "hit_rate".padStart(11) +
    "Status".padStart(9),
  );
  console.log("-".repeat(52));

  for (const cat of Object.keys(categories).sort()) {
    const catResults = categories[cat];
    const catHits = catResults.reduce((s, r) => s + r.hits, 0);
    const catTotal = catResults.reduce((s, r) => s + r.total_keywords, 0);
    const hitRate = catTotal > 0 ? catHits / catTotal : 0;

    totalHits += catHits;
    totalKeywords += catTotal;

    const status = hitRate >= threshold ? "PASS" : "FAIL";
    console.log(
      cat.padEnd(15) +
      String(catResults.length).padStart(5) +
      ` ${catHits}/${String(catTotal).padStart(4)}` +
      fmtPct(hitRate).padStart(10) +
      status.padStart(8),
    );
  }

  const overallRate = totalKeywords > 0 ? totalHits / totalKeywords : 0;
  const overallStatus = overallRate >= threshold ? "PASS" : "FAIL";

  console.log("-".repeat(52));
  console.log(
    "OVERALL".padEnd(15) +
    String(results.length).padStart(5) +
    ` ${totalHits}/${String(totalKeywords).padStart(4)}` +
    fmtPct(overallRate).padStart(10) +
    overallStatus.padStart(8),
  );
  console.log();

  // Severity accuracy summary
  const hasSeverity = results.some(
    (r) => r.detected_severity !== "info" || r.expected_severity !== "info",
  );
  if (results.length > 0 && hasSeverity) {
    const sevCorrect = results.filter((r) => r.severity_match).length;
    const sevAcc = sevCorrect / results.length;
    console.log(`severity_accuracy: ${sevCorrect}/${results.length} = ${fmtPct(sevAcc)}`);
    console.log();
  }

  // Per-case failure details
  const failures = results.filter((r) => !r.passed);
  if (failures.length > 0) {
    console.log("Failed Cases:");
    for (const f of failures) {
      const missingStr = f.missing_keywords.join(", ");
      const errorStr = f.error ? ` -- ${f.error}` : "";
      console.log(
        `  ${f.test_id}: ${f.hits}/${f.total_keywords} hits ` +
        `(missing: ${missingStr})${errorStr}`,
      );
    }
    console.log();
  }

  return overallRate;
}

// ---------------------------------------------------------------------------
// Write JSON output
// ---------------------------------------------------------------------------
function writeJsonOutput(outputPath, results, overallRate, threshold, cases) {
  let outputData;
  if (cases != null && results.length === 0) {
    outputData = {
      overall_pass_rate: 0,
      threshold,
      total_cases: cases.length,
      total_passed: 0,
      results: cases.map((c) => ({
        test_id: c.id,
        category: c.category,
        must_contain_total: c.must_contain.length,
        must_contain_keywords: c.must_contain,
        expected_severity: c.severity,
      })),
    };
  } else {
    outputData = {
      overall_pass_rate: overallRate,
      threshold,
      total_cases: results.length,
      total_passed: results.filter((r) => r.passed).length,
      results: results.map((r) => {
        const entry = {
          test_id: r.test_id,
          category: r.category,
          passed: r.passed,
          must_contain_hits: r.hits,
          must_contain_total: r.total_keywords,
          missing_keywords: r.missing_keywords,
          elapsed_ms: r.elapsed_ms,
          error: r.error,
          detected_severity: r.detected_severity,
          expected_severity: r.expected_severity,
          severity_match: r.severity_match,
        };
        if (r.judge_score != null) entry.judge_score = r.judge_score;
        if (r.blended_score != null) entry.blended_score = r.blended_score;
        return entry;
      }),
    };
  }
  writeFileSync(outputPath, JSON.stringify(outputData, null, 2), "utf-8");
  console.log(`Results written to ${outputPath}`);
}

// ---------------------------------------------------------------------------
// Single eval run
// ---------------------------------------------------------------------------
/**
 * Run a single evaluation pass over the given test cases.
 * @returns {Promise<{ results: object[], overallRate: number, severityAccuracy: number }>}
 */
async function runSingleEval(
  cases,
  serverUrl,
  threshold,
  dryRun,
  outputPath,
  severityThreshold,
  { withJudge = false, judgeModel = "claude-sonnet-4-20250514", judgeWeight = 0.2 } = {},
) {
  const results = [];

  if (dryRun) {
    for (const testCase of cases) {
      const detected = detectSeverity("");
      const expected = testCase.severity;
      const sevMatch = compareSeverity(detected, expected);

      const judgeScore = withJudge ? 3 : null;
      let blended = null;
      if (withJudge && judgeScore != null) {
        blended = blendScores(0, judgeScore, judgeWeight);
      }

      results.push({
        test_id: testCase.id,
        category: testCase.category,
        passed: false,
        hits: 0,
        total_keywords: testCase.must_contain.length,
        missing_keywords: [...testCase.must_contain],
        detected_severity: detected,
        expected_severity: expected,
        severity_match: sevMatch,
        judge_score: judgeScore,
        blended_score: blended,
      });
    }
    if (outputPath) writeJsonOutput(outputPath, results, 0, threshold);
    const sevCorrect = results.filter((r) => r.severity_match).length;
    return {
      results,
      overallRate: 0,
      severityAccuracy: results.length > 0 ? sevCorrect / results.length : 0,
    };
  }

  // Live mode: get auth secret from environment
  const secret = process.env.REVIEW_SECRET || "";
  if (!secret) {
    console.error("ERROR: REVIEW_SECRET env var not set -- required for API auth");
    process.exit(1);
  }

  // Run each case
  for (let i = 0; i < cases.length; i++) {
    const testCase = cases[i];
    process.stdout.write(`[${i + 1}/${cases.length}] ${testCase.id} (${testCase.category}) ... `);

    try {
      const t0 = performance.now();
      const resp = await callReviewApi(testCase, serverUrl, secret);
      const elapsedMs = Math.round(performance.now() - t0);

      const reviewText = resp.review || "";
      const respModel = resp.model || "";
      const respElapsed = resp.elapsedMs || 0;

      console.log(
        `model=${respModel} server_ms=${respElapsed} ` +
        `client_ms=${elapsedMs} review_len=${reviewText.length}`,
      );

      // Keyword evaluation
      const { hits, missing } = evaluateKeywords(reviewText, testCase.must_contain);
      const passed = hits === testCase.must_contain.length;

      // Severity detection
      const detected = detectSeverity(reviewText);
      const expected = testCase.severity;
      const sevMatch = compareSeverity(detected, expected);

      // Judge evaluation (optional)
      let judgeScore = null;
      let blended = null;
      if (withJudge) {
        process.stdout.write(`  judging ${testCase.id} ... `);
        judgeScore = await callJudge({
          reviewText,
          diff: testCase.diff,
          category: testCase.category,
          model: judgeModel,
          severity: testCase.severity,
        });
        const keywordRate = testCase.must_contain.length > 0
          ? hits / testCase.must_contain.length
          : 0;
        blended = blendScores(keywordRate, judgeScore, judgeWeight);
        console.log(`  judge_score=${judgeScore} blended=${blended.toFixed(3)}`);
      }

      results.push({
        test_id: testCase.id,
        category: testCase.category,
        passed,
        hits,
        total_keywords: testCase.must_contain.length,
        missing_keywords: missing,
        elapsed_ms: elapsedMs,
        detected_severity: detected,
        expected_severity: expected,
        severity_match: sevMatch,
        judge_score: judgeScore,
        blended_score: blended,
      });
    } catch (err) {
      const msg = err.status
        ? `HTTP ${err.status}: ${err.message.slice(0, 300)}`
        : `${err.constructor.name}: ${err.message}`;
      console.log(`ERROR: ${msg}`);

      const detected = "info";
      const expected = testCase.severity;
      results.push({
        test_id: testCase.id,
        category: testCase.category,
        passed: false,
        hits: 0,
        total_keywords: testCase.must_contain.length,
        missing_keywords: [...testCase.must_contain],
        error: msg,
        detected_severity: detected,
        expected_severity: expected,
        severity_match: compareSeverity(detected, expected),
      });
    }
  }

  // Compute overall rate
  const totalHits = results.reduce((s, r) => s + r.hits, 0);
  const totalKw = results.reduce((s, r) => s + r.total_keywords, 0);
  const overallRate = totalKw > 0 ? totalHits / totalKw : 0;

  // Compute severity accuracy
  const sevCorrect = results.filter((r) => r.severity_match).length;
  const severityAccuracy = results.length > 0 ? sevCorrect / results.length : 0;

  if (outputPath) writeJsonOutput(outputPath, results, overallRate, threshold);

  return { results, overallRate, severityAccuracy };
}

// ---------------------------------------------------------------------------
// Main runner
// ---------------------------------------------------------------------------
async function runEval(opts) {
  const {
    configPath,
    datasetDir,
    limit,
    category,
    outputPath,
    dryRun,
    saveBaselinePath,
    baselinePath,
    repeat,
    withJudge,
    metaEval,
  } = opts;

  // 1. Load config
  const config = loadConfig(configPath);
  const serverUrl = config.server_url || "https://review.example.com";
  const thresholds = config.thresholds || {};
  const threshold = thresholds.must_contain_hit_rate || 0.8;
  const severityThreshold = thresholds.severity_accuracy || 0.8;
  const judgeModel = config.judge_model || "claude-sonnet-4-20250514";
  const judgeWeight = config.judge_weight ?? 0.2;

  // 2. Load golden dataset
  const cases = loadTestCases(datasetDir, { limit, category });
  console.log(`Loaded ${cases.length} test cases`);

  if (cases.length === 0) {
    console.error("ERROR: No matching test cases found");
    process.exit(1);
  }

  // 3. Dry-run path
  if (dryRun) {
    const effectiveRepeat = repeat != null && repeat >= 1 ? repeat : 1;

    if (effectiveRepeat > 1) {
      const passRates = [];
      for (let runIdx = 0; runIdx < effectiveRepeat; runIdx++) {
        console.log(`\n=== Repeat ${runIdx + 1}/${effectiveRepeat} ===`);
        for (const c of cases) {
          console.log(
            `  ${c.id} [${c.category}] persona=${c.persona} ` +
            `keywords=${c.must_contain.length} source=${c.source_type}`,
          );
        }
        console.log(`${cases.length} cases loaded (dry-run, no API calls)`);
        passRates.push(0);
      }

      const meanRate = passRates.reduce((a, b) => a + b, 0) / passRates.length;
      console.log(`\nRepeat Statistics (${effectiveRepeat} dry-runs):`);
      console.log(`  mean pass_rate: ${(meanRate * 100).toFixed(0)}%`);
      console.log(`  stddev:         0.0000`);
    } else {
      for (const c of cases) {
        console.log(
          `  ${c.id} [${c.category}] persona=${c.persona} ` +
          `keywords=${c.must_contain.length} source=${c.source_type}`,
        );
      }
      console.log(`\n${cases.length} cases loaded (dry-run, no API calls)`);
    }

    if (outputPath) {
      if (withJudge) {
        await runSingleEval(cases, serverUrl, threshold, true, outputPath, severityThreshold, {
          withJudge: true, judgeModel, judgeWeight,
        });
      } else {
        writeJsonOutput(outputPath, [], 0, threshold, cases);
      }
    }

    if (saveBaselinePath) {
      const dryBaseline = {
        metadata: { model: config.model || "eval-runner" },
        overall_pass_rate: 0,
        severity_accuracy: 0,
        threshold,
        total_cases: cases.length,
        total_passed: 0,
        results: cases.map((c) => ({
          test_id: c.id,
          category: c.category,
          expected_severity: c.severity,
        })),
      };
      saveBaseline(saveBaselinePath, dryBaseline);
    }

    if (baselinePath) {
      const blData = loadBaseline(baselinePath);
      const { detail } = checkRegression(0, blData);
      console.log(`baseline: ${detail}`);
    }

    process.exit(0);
  }

  // 4. Handle --repeat (live mode only)
  const effectiveRepeat = repeat != null && repeat >= 1 ? repeat : 1;

  let overallRate, results, severityAccuracy;

  if (effectiveRepeat > 1) {
    console.log(`Running ${effectiveRepeat} repeat(s)...`);
    const passRates = [];
    let allResults = [];

    for (let runIdx = 0; runIdx < effectiveRepeat; runIdx++) {
      console.log(`\n=== Repeat ${runIdx + 1}/${effectiveRepeat} ===`);
      const runOutput = outputPath && runIdx === effectiveRepeat - 1 ? outputPath : null;

      const run = await runSingleEval(
        cases, serverUrl, threshold, false, runOutput, severityThreshold,
        { withJudge, judgeModel, judgeWeight },
      );
      passRates.push(run.overallRate);
      allResults = run.results;
    }

    // Compute statistics
    const meanRate = passRates.reduce((a, b) => a + b, 0) / passRates.length;
    let stddev = 0;
    if (passRates.length > 1) {
      const variance = passRates.reduce((s, r) => s + (r - meanRate) ** 2, 0) / passRates.length;
      stddev = Math.sqrt(variance);
    }

    console.log(`\nRepeat Statistics (${effectiveRepeat} runs):`);
    console.log(`  mean pass_rate: ${(meanRate * 100).toFixed(0)}%`);
    console.log(`  stddev:         ${stddev.toFixed(4)}`);
    passRates.forEach((rate, i) => {
      console.log(`  run ${i + 1}: ${(rate * 100).toFixed(0)}%`);
    });
    console.log();

    overallRate = meanRate;
    results = allResults;
  } else {
    // Single run (live mode)
    ({ results, overallRate, severityAccuracy } = await runSingleEval(
      cases, serverUrl, threshold, false, outputPath, severityThreshold,
      { withJudge, judgeModel, judgeWeight },
    ));
  }

  // 5. Print results table
  if (results.length > 0) {
    overallRate = printResultsTable(results, threshold);
  }

  // 6. Compute severity accuracy for display
  if (results.length > 0) {
    const sevCorrect = results.filter((r) => r.severity_match).length;
    severityAccuracy = results.length > 0 ? sevCorrect / results.length : 0;
    console.log(
      `severity_accuracy: ${fmtPct(severityAccuracy)} (threshold: ${fmtPct(severityThreshold)})`,
    );
    if (severityAccuracy < severityThreshold) {
      console.log(
        `WARNING: severity_accuracy below threshold (${fmtPct(severityAccuracy)} < ${fmtPct(severityThreshold)})`,
      );
    }
  }

  // 6b. Meta-evaluation (optional, requires --with-judge)
  if (metaEval && results.length > 0) {
    const labels = {};
    for (const r of results) {
      if (r.judge_score != null) {
        const keywordRate = r.total_keywords > 0 ? r.hits / r.total_keywords : 0;
        const humanLabel = keywordRate >= threshold ? "pass" : "fail";
        labels[r.test_id] = { human: humanLabel, judge_score: r.judge_score };
      }
    }
    if (Object.keys(labels).length > 0) {
      const agreementRate = computeAgreement(labels);
      const total = Object.keys(labels).length;
      const agreements = Math.round(agreementRate * total);
      const disagreements = total - agreements;
      const gatePassed = checkAgreementGate(agreementRate, 0.7);

      console.log();
      console.log("Meta-Evaluation Report:");
      console.log(`  agreement_rate: ${(agreementRate * 100).toFixed(0)}% (${agreements}/${total})`);
      console.log(`  disagreements: ${disagreements}`);
      console.log(`  gate (>= 0.7): ${gatePassed ? "PASS" : "FAIL"}`);

      if (agreementRate < 0.7) {
        console.log(
          "WARNING: Judge-keyword agreement below 0.7 threshold. " +
          "Review quality may need calibration.",
        );
      }
    } else {
      console.error("WARNING: --meta-eval requested but no judge scores available.");
    }
  }

  // 7. Save baseline if requested
  if (saveBaselinePath) {
    const sevCorrect = results.filter((r) => r.severity_match).length;
    const liveBaseline = {
      metadata: { model: config.model || "eval-runner" },
      overall_pass_rate: overallRate,
      severity_accuracy: results.length > 0 ? sevCorrect / results.length : 0,
      threshold,
      total_cases: results.length,
      total_passed: results.filter((r) => r.passed).length,
      results: results.map((r) => ({
        test_id: r.test_id,
        category: r.category,
        passed: r.passed,
        must_contain_hits: r.hits,
        must_contain_total: r.total_keywords,
        detected_severity: r.detected_severity,
        expected_severity: r.expected_severity,
        severity_match: r.severity_match,
      })),
    };
    saveBaseline(saveBaselinePath, liveBaseline);
  }

  // 8. Baseline comparison if requested
  let isRegression = false;
  if (baselinePath) {
    const baselineData = loadBaseline(baselinePath);
    const reg = checkRegression(overallRate, baselineData);
    isRegression = reg.isRegression;
    console.log(`baseline: ${reg.detail}`);
    if (isRegression) {
      console.log("REGRESSION DETECTED -- exiting with code 3");
    }
  }

  // 9. Exit code logic
  if (isRegression) {
    process.exit(3);
  } else if (overallRate >= threshold) {
    console.log(`PASS: ${fmtPct(overallRate)} >= ${fmtPct(threshold)} threshold`);
    process.exit(0);
  } else if (overallRate >= 0.5) {
    console.log(`PARTIAL: ${fmtPct(overallRate)} (50%-${fmtPct(threshold)})`);
    process.exit(2);
  } else {
    console.log(`FAIL: ${fmtPct(overallRate)} < 50%`);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------
// CLI (commander)
// ---------------------------------------------------------------------------
program
  .name("eval-runner")
  .description(
    "AI Code Review Bot -- eval runner. " +
    "Reads golden dataset YAML files, calls POST /review for each test case, " +
    "evaluates must_contain keywords, and prints category-level results.",
  )
  .option("--config <path>", "Path to eval-config.yaml", DEFAULT_CONFIG_PATH)
  .option("--dataset-dir <path>", "Path to golden-dataset directory", DEFAULT_DATASET_DIR)
  .option("--limit <n>", "Limit number of test cases", parseInt)
  .option("--category <cat>", "Run only cases from a specific category")
  .option("--output <path>", "Write JSON results to this file path")
  .option("--dry-run", "Load cases and print summary without calling API", false)
  .option("--save-baseline <path>", "Save results as baseline JSON to this path")
  .option("--baseline <path>", "Load baseline JSON and compare with current run")
  .option("--repeat <n>", "Run evaluation N times and report variance", parseInt)
  .option("--with-judge", "Enable LLM-as-Judge evaluation via Anthropic API", false)
  .option("--meta-eval", "Compute judge-keyword agreement rate (requires --with-judge)", false)
  .action(async (opts) => {
    if (opts.repeat != null && opts.repeat < 1) {
      console.error("ERROR: --repeat must be >= 1");
      process.exit(1);
    }
    await runEval({
      configPath: opts.config,
      datasetDir: opts.datasetDir,
      limit: opts.limit,
      category: opts.category,
      outputPath: opts.output,
      dryRun: opts.dryRun,
      saveBaselinePath: opts.saveBaseline,
      baselinePath: opts.baseline,
      repeat: opts.repeat,
      withJudge: opts.withJudge,
      metaEval: opts.metaEval,
    });
  });

program.parse();
