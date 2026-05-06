#!/usr/bin/env python3
"""
eval-runner.py -- AI Code Review Bot evaluation runner.

Reads golden dataset YAML files, calls POST /review for each case,
evaluates must_contain keywords in the response, and prints a
category-level summary as plain text.

Exit codes:
    0  pass_rate >= threshold (default 80%)
    1  pass_rate < 50%  OR  fatal setup error
    2  pass_rate 50% .. threshold-1
    3  regression detected (baseline comparison failed)
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

# Local judge client module -- add evals dir to path for sibling import
sys.path.insert(0, str(Path(__file__).resolve().parent))
from judge_client import (
    blend_scores,
    call_judge,
    check_agreement_gate,
    compute_agreement,
    extract_score,
)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent / "eval-config.yaml"
DEFAULT_DATASET_DIR = Path(__file__).resolve().parent / "golden-dataset"
REQUEST_TIMEOUT_SEC = 90

# Severity ordering: higher index = more severe
SEVERITY_ORDER = {"info": 0, "warning": 1, "critical": 2}


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------
@dataclass
class TestCase:
    """One golden-dataset YAML file."""

    id: str
    category: str
    diff: str
    pr_title: str
    pr_author: str
    repo: str
    # persona is read for logging only -- NOT sent to API
    persona: str
    must_contain: list[str]
    severity: str
    source_type: str
    file_path: str


@dataclass
class CaseResult:
    """Result of evaluating a single test case."""

    test_id: str
    category: str
    passed: bool
    hits: int
    total_keywords: int
    missing_keywords: list[str] = field(default_factory=list)
    elapsed_ms: int = 0
    error: str | None = None
    detected_severity: str = "info"
    expected_severity: str = "info"
    severity_match: bool = True
    judge_score: int | None = None
    blended_score: float | None = None


# ---------------------------------------------------------------------------
# Severity detection
# ---------------------------------------------------------------------------
def detect_severity(review_text: str) -> str:
    """
    Parse review text for emoji severity markers.

    Returns the highest severity found:
        - critical if text contains red circle
        - warning if text contains yellow circle (and no red)
        - info if text contains green circle (and no red/yellow)
        - "info" as default if no emoji found

    Case-insensitive text fallback also checks headings for
    "critical"/"warning"/"info".
    """
    has_critical = "\U0001f534" in review_text  # red circle
    has_warning = "\U0001f7e1" in review_text  # yellow circle
    has_info = "\U0001f7e2" in review_text  # green circle

    # Highest emoji wins
    if has_critical:
        return "critical"
    if has_warning:
        return "warning"
    if has_info:
        return "info"

    # Text fallback: check for severity words in headings (lines starting with #)
    text_severity = "info"
    for line in review_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            lower = stripped.lower()
            if "critical" in lower:
                text_severity = "critical"
            elif "warning" in lower:
                if SEVERITY_ORDER.get(text_severity, 0) < SEVERITY_ORDER["warning"]:
                    text_severity = "warning"

    return text_severity


def compare_severity(detected: str, expected: str) -> bool:
    """Return True if detected severity exactly matches expected."""
    return detected == expected


def severity_distance(detected: str, expected: str) -> int:
    """Return ordinal distance between detected and expected severity."""
    return abs(SEVERITY_ORDER.get(detected, 0) - SEVERITY_ORDER.get(expected, 0))


# ---------------------------------------------------------------------------
# Config loading
# ---------------------------------------------------------------------------
def load_config(config_path: Path) -> dict[str, Any]:
    """Load eval-config.yaml and return as dict."""
    if not config_path.exists():
        print(f"ERROR: Config file not found: {config_path}", file=sys.stderr)
        sys.exit(1)
    with open(config_path, "r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


# ---------------------------------------------------------------------------
# Golden dataset loading
# ---------------------------------------------------------------------------
def load_test_cases(
    dataset_dir: Path,
    limit: int | None = None,
    category: str | None = None,
) -> list[TestCase]:
    """Glob golden-dataset/**/*.yaml and parse each file."""
    import glob as glob_mod

    pattern = str(dataset_dir / "**" / "*.yaml")
    yaml_files = sorted(glob_mod.glob(pattern, recursive=True))

    if not yaml_files:
        print(f"ERROR: No golden YAML files found in {dataset_dir}", file=sys.stderr)
        sys.exit(1)

    cases: list[TestCase] = []
    for fp in yaml_files:
        with open(fp, "r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh)

        inp = data.get("input", {})
        expected = data.get("expected", {})
        source = data.get("source", {})
        cat = data.get("category", "unknown")

        # Filter by category early
        if category is not None and cat != category:
            continue

        cases.append(
            TestCase(
                id=data.get("id", Path(fp).stem),
                category=cat,
                diff=inp.get("diff", ""),
                pr_title=inp.get("pr_title", "(no title)"),
                pr_author=inp.get("pr_author", "unknown"),
                repo=inp.get("repo", "unknown"),
                # persona is evaluation-only -- NOT sent to API
                persona=inp.get("persona", ""),
                must_contain=expected.get("must_contain", []),
                severity=expected.get("severity", "info"),
                source_type=source.get("type", "unknown"),
                file_path=fp,
            )
        )

    # Apply limit
    if limit is not None:
        cases = cases[:limit]

    return cases


# ---------------------------------------------------------------------------
# API call (stdlib urllib)
# ---------------------------------------------------------------------------
def call_review_api(
    case: TestCase,
    server_url: str,
    secret: str,
) -> dict[str, Any]:
    """
    POST /review with the test case input.

    Sends {diff, pr_title, pr_author, repo} -- no persona field.
    persona is evaluation-only and NOT sent to the API.
    """
    url = f"{server_url.rstrip('/')}/review"
    headers = {
        "x-review-secret": secret,
        "x-github-repo": case.repo,
        "Content-Type": "application/json",
        "User-Agent": "claude-review-eval-runner/1.0",
    }
    # Build request body -- no persona field sent
    body = {
        "diff": case.diff,
        "pr_title": case.pr_title,
        "pr_author": case.pr_author,
        "repo": case.repo,
    }

    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_SEC) as resp:
        response_bytes = resp.read()
        return json.loads(response_bytes.decode("utf-8"))


# ---------------------------------------------------------------------------
# Keyword evaluation
# ---------------------------------------------------------------------------
def evaluate_keywords(
    review_text: str,
    must_contain: list[str],
) -> tuple[int, list[str]]:
    """
    Check each must_contain keyword against the review text (case-insensitive).
    Returns (hit_count, missing_keywords).
    """
    review_lower = review_text.lower()
    hits = 0
    missing: list[str] = []
    for kw in must_contain:
        if kw.lower() in review_lower:
            hits += 1
        else:
            missing.append(kw)
    return hits, missing


# ---------------------------------------------------------------------------
# Baseline save/load
# ---------------------------------------------------------------------------
def save_baseline(path: str, data: dict[str, Any]) -> None:
    """Save baseline JSON with metadata to the specified path."""
    import os
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # Ensure metadata has required fields
    if "metadata" not in data:
        data["metadata"] = {}

    metadata = data["metadata"]
    if "date" not in metadata:
        metadata["date"] = datetime.now(timezone.utc).isoformat()
    if "commit" not in metadata:
        try:
            commit = subprocess.check_output(
                ["git", "rev-parse", "--short", "HEAD"],
                stderr=subprocess.DEVNULL,
                text=True,
            ).strip()
        except Exception:
            commit = "unknown"
        metadata["commit"] = commit
    if "model" not in metadata:
        metadata["model"] = "eval-runner"

    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
    print(f"Baseline saved to {path}")


def load_baseline(path: str) -> dict[str, Any]:
    """Load baseline JSON from the specified path."""
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


# ---------------------------------------------------------------------------
# Regression gate
# ---------------------------------------------------------------------------
def check_regression(
    current_rate: float,
    baseline_data: dict[str, Any],
    drop_threshold: float = 0.05,
) -> tuple[bool, str]:
    """
    Compare current overall pass_rate against baseline.

    Returns (is_regression, detail_message).
    Regression is detected if baseline_rate - current_rate >= drop_threshold.
    """
    baseline_rate = baseline_data.get("overall_pass_rate", 0.0)
    drop = baseline_rate - current_rate

    if drop >= drop_threshold:
        detail = (
            f"REGRESSION DETECTED: pass_rate dropped from "
            f"{baseline_rate:.0%} to {current_rate:.0%} "
            f"(-{drop:.0%}, threshold: -{drop_threshold:.0%})"
        )
        return True, detail

    detail = (
        f"baseline comparison: {baseline_rate:.0%} -> {current_rate:.0%} "
        f"(delta: {drop:+.0%}, threshold: -{drop_threshold:.0%})"
    )
    return False, detail


# ---------------------------------------------------------------------------
# Plain-text table output
# ---------------------------------------------------------------------------
def print_results_table(
    results: list[CaseResult],
    threshold: float,
) -> float:
    """Print category-level summary table and return overall pass_rate."""
    # Group by category
    categories: dict[str, list[CaseResult]] = {}
    for r in results:
        categories.setdefault(r.category, []).append(r)

    total_hits = 0
    total_keywords = 0

    # Header
    print()
    print(f"{'Category':<15} {'Cases':>5} {'Hits':>10} {'hit_rate':>10} {'Status':>8}")
    print("-" * 52)

    for cat in sorted(categories.keys()):
        cat_results = categories[cat]
        cat_hits = sum(r.hits for r in cat_results)
        cat_total = sum(r.total_keywords for r in cat_results)
        hit_rate = cat_hits / cat_total if cat_total > 0 else 0.0

        total_hits += cat_hits
        total_keywords += cat_total

        status = "PASS" if hit_rate >= threshold else "FAIL"

        print(
            f"{cat:<15} {len(cat_results):>5} "
            f"{cat_hits}/{cat_total:>4} "
            f"{hit_rate:>9.0%} {status:>8}"
        )

    overall_rate = total_hits / total_keywords if total_keywords > 0 else 0.0
    overall_status = "PASS" if overall_rate >= threshold else "FAIL"

    print("-" * 52)
    print(
        f"{'OVERALL':<15} {len(results):>5} "
        f"{total_hits}/{total_keywords:>4} "
        f"{overall_rate:>9.0%} {overall_status:>8}"
    )
    print()

    # Severity accuracy summary
    if results and any(r.detected_severity != "info" or r.expected_severity != "info" for r in results):
        sev_correct = sum(1 for r in results if r.severity_match)
        sev_total = len(results)
        sev_acc = sev_correct / sev_total if sev_total > 0 else 0.0
        print(f"severity_accuracy: {sev_correct}/{sev_total} = {sev_acc:.0%}")
        print()

    # Print per-case details for failures
    failures = [r for r in results if not r.passed]
    if failures:
        print("Failed Cases:")
        for f in failures:
            missing_str = ", ".join(f.missing_keywords)
            error_str = f" -- {f.error}" if f.error else ""
            print(
                f"  {f.test_id}: {f.hits}/{f.total_keywords} hits "
                f"(missing: {missing_str}){error_str}"
            )
        print()

    return overall_rate


# ---------------------------------------------------------------------------
# Write JSON output
# ---------------------------------------------------------------------------
def write_json_output(
    output_path: str,
    results: list[CaseResult],
    overall_rate: float,
    threshold: float,
    cases: list[TestCase] | None = None,
) -> None:
    """Write JSON results file."""
    if cases is not None and not results:
        # Dry-run mode: write loaded cases with overall_pass_rate=0
        output_data: dict[str, Any] = {
            "overall_pass_rate": 0,
            "threshold": threshold,
            "total_cases": len(cases),
            "total_passed": 0,
            "results": [
                {
                    "test_id": c.id,
                    "category": c.category,
                    "must_contain_total": len(c.must_contain),
                    "must_contain_keywords": c.must_contain,
                    "expected_severity": c.severity,
                }
                for c in cases
            ],
        }
    else:
        output_data = {
            "overall_pass_rate": overall_rate,
            "threshold": threshold,
            "total_cases": len(results),
            "total_passed": sum(1 for r in results if r.passed),
            "results": [
                {
                    "test_id": r.test_id,
                    "category": r.category,
                    "passed": r.passed,
                    "must_contain_hits": r.hits,
                    "must_contain_total": r.total_keywords,
                    "missing_keywords": r.missing_keywords,
                    "elapsed_ms": r.elapsed_ms,
                    "error": r.error,
                    "detected_severity": r.detected_severity,
                    "expected_severity": r.expected_severity,
                    "severity_match": r.severity_match,
                    **({"judge_score": r.judge_score} if r.judge_score is not None else {}),
                    **({"blended_score": r.blended_score} if r.blended_score is not None else {}),
                }
                for r in results
            ],
        }
    with open(output_path, "w", encoding="utf-8") as fh:
        json.dump(output_data, fh, ensure_ascii=False, indent=2)
    print(f"Results written to {output_path}")


# ---------------------------------------------------------------------------
# Single eval run (refactored from run_eval for repeat support)
# ---------------------------------------------------------------------------
def run_single_eval(
    cases: list[TestCase],
    server_url: str,
    threshold: float,
    dry_run: bool,
    output_path: str | None,
    severity_threshold: float,
    with_judge: bool = False,
    judge_model: str = "claude-sonnet-4-20250514",
    judge_weight: float = 0.2,
) -> tuple[list[CaseResult], float, float]:
    """
    Run a single evaluation pass over the given test cases.

    Returns (results, overall_rate, severity_accuracy).
    Does NOT call sys.exit -- caller handles exit codes.
    """
    results: list[CaseResult] = []

    if dry_run:
        # Dry-run: no API calls, but still produce CaseResult entries
        for case in cases:
            # In dry-run mode, simulate with empty review text
            detected = detect_severity("")
            expected = case.severity
            sev_match = compare_severity(detected, expected)

            # Judge placeholder: use score 3 when --with-judge in dry-run
            judge_score = 3 if with_judge else None
            blended = None
            if with_judge and judge_score is not None:
                keyword_rate = 0.0  # no keywords hit in dry-run
                blended = blend_scores(keyword_rate, judge_score, judge_weight)

            results.append(
                CaseResult(
                    test_id=case.id,
                    category=case.category,
                    passed=False,
                    hits=0,
                    total_keywords=len(case.must_contain),
                    missing_keywords=list(case.must_contain),
                    detected_severity=detected,
                    expected_severity=expected,
                    severity_match=sev_match,
                    judge_score=judge_score,
                    blended_score=blended,
                )
            )
        if output_path:
            write_json_output(output_path, results, 0.0, threshold)
        sev_correct = sum(1 for r in results if r.severity_match)
        severity_accuracy = sev_correct / len(results) if results else 0.0
        return results, 0.0, severity_accuracy

    # Live mode: get auth secret from environment
    secret = os.environ.get("REVIEW_SECRET", "")
    if not secret:
        print(
            "ERROR: REVIEW_SECRET env var not set -- required for API auth",
            file=sys.stderr,
        )
        sys.exit(1)

    # Run each case
    for i, case in enumerate(cases, 1):
        print(f"[{i}/{len(cases)}] {case.id} ({case.category}) ... ", end="", flush=True)

        try:
            t0 = time.monotonic()
            resp = call_review_api(case, server_url, secret)
            elapsed_ms = int((time.monotonic() - t0) * 1000)

            review_text = resp.get("review", "")
            resp_model = resp.get("model", "")
            resp_elapsed = resp.get("elapsedMs", 0)

            print(
                f"model={resp_model} server_ms={resp_elapsed} "
                f"client_ms={elapsed_ms} review_len={len(review_text)}"
            )

            # Keyword evaluation
            hits, missing = evaluate_keywords(review_text, case.must_contain)
            passed = hits == len(case.must_contain)

            # Severity detection
            detected = detect_severity(review_text)
            expected = case.severity
            sev_match = compare_severity(detected, expected)

            # Judge evaluation (optional)
            judge_score = None
            blended = None
            if with_judge:
                print(f"  judging {case.id} ... ", end="", flush=True)
                judge_score = call_judge(
                    review_text=review_text,
                    diff=case.diff,
                    category=case.category,
                    model=judge_model,
                    severity=case.severity,
                )
                keyword_rate = hits / len(case.must_contain) if case.must_contain else 0.0
                blended = blend_scores(keyword_rate, judge_score, judge_weight)
                print(f"  judge_score={judge_score} blended={blended:.3f}")

            results.append(
                CaseResult(
                    test_id=case.id,
                    category=case.category,
                    passed=passed,
                    hits=hits,
                    total_keywords=len(case.must_contain),
                    missing_keywords=missing,
                    elapsed_ms=elapsed_ms,
                    detected_severity=detected,
                    expected_severity=expected,
                    severity_match=sev_match,
                    judge_score=judge_score,
                    blended_score=blended,
                )
            )

        except urllib.error.HTTPError as exc:
            body_preview = ""
            try:
                body_preview = exc.read().decode("utf-8", errors="replace")[:300]
            except Exception:
                pass
            msg = f"HTTP {exc.code}: {body_preview}"
            print(f"ERROR: {msg}")
            detected = "info"
            expected = case.severity
            results.append(
                CaseResult(
                    test_id=case.id,
                    category=case.category,
                    passed=False,
                    hits=0,
                    total_keywords=len(case.must_contain),
                    missing_keywords=list(case.must_contain),
                    error=msg,
                    detected_severity=detected,
                    expected_severity=expected,
                    severity_match=compare_severity(detected, expected),
                )
            )

        except urllib.error.URLError as exc:
            reason = str(exc.reason) if exc.reason else str(exc)
            print(f"ERROR: Connection/timeout error: {reason}")
            detected = "info"
            expected = case.severity
            results.append(
                CaseResult(
                    test_id=case.id,
                    category=case.category,
                    passed=False,
                    hits=0,
                    total_keywords=len(case.must_contain),
                    missing_keywords=list(case.must_contain),
                    error=f"URLError: {reason}",
                    detected_severity=detected,
                    expected_severity=expected,
                    severity_match=compare_severity(detected, expected),
                )
            )

        except TimeoutError as exc:
            print(f"ERROR: Request timed out: {exc}")
            detected = "info"
            expected = case.severity
            results.append(
                CaseResult(
                    test_id=case.id,
                    category=case.category,
                    passed=False,
                    hits=0,
                    total_keywords=len(case.must_contain),
                    missing_keywords=list(case.must_contain),
                    error=f"TimeoutError: {exc}",
                    detected_severity=detected,
                    expected_severity=expected,
                    severity_match=compare_severity(detected, expected),
                )
            )

        except json.JSONDecodeError as exc:
            print(f"ERROR: Malformed response: {exc}")
            detected = "info"
            expected = case.severity
            results.append(
                CaseResult(
                    test_id=case.id,
                    category=case.category,
                    passed=False,
                    hits=0,
                    total_keywords=len(case.must_contain),
                    missing_keywords=list(case.must_contain),
                    error=f"JSONDecodeError: {exc}",
                    detected_severity=detected,
                    expected_severity=expected,
                    severity_match=compare_severity(detected, expected),
                )
            )

        except Exception as exc:
            print(f"ERROR: Unexpected: {type(exc).__name__}: {exc}")
            detected = "info"
            expected = case.severity
            results.append(
                CaseResult(
                    test_id=case.id,
                    category=case.category,
                    passed=False,
                    hits=0,
                    total_keywords=len(case.must_contain),
                    missing_keywords=list(case.must_contain),
                    error=f"{type(exc).__name__}: {exc}",
                    detected_severity=detected,
                    expected_severity=expected,
                    severity_match=compare_severity(detected, expected),
                )
            )

    # Compute overall rate
    total_hits = sum(r.hits for r in results)
    total_kw = sum(r.total_keywords for r in results)
    overall_rate = total_hits / total_kw if total_kw > 0 else 0.0

    # Compute severity accuracy
    sev_correct = sum(1 for r in results if r.severity_match)
    severity_accuracy = sev_correct / len(results) if results else 0.0

    if output_path:
        write_json_output(output_path, results, overall_rate, threshold)

    return results, overall_rate, severity_accuracy


# ---------------------------------------------------------------------------
# Main runner
# ---------------------------------------------------------------------------
def run_eval(
    config_path: Path,
    dataset_dir: Path,
    limit: int | None,
    category: str | None,
    output_path: str | None,
    dry_run: bool,
    save_baseline_path: str | None = None,
    baseline_path: str | None = None,
    repeat: int | None = None,
    with_judge: bool = False,
    meta_eval: bool = False,
) -> None:
    """Main evaluation logic."""
    # 1. Load config
    config = load_config(config_path)
    server_url = config.get("server_url", "https://review.example.com")
    thresholds = config.get("thresholds", {})
    threshold = thresholds.get("must_contain_hit_rate", 0.8)
    severity_threshold = thresholds.get("severity_accuracy", 0.8)
    # model and temperature are read but NOT sent -- reserved for #69 LLM-as-Judge
    _model = config.get("model", "")
    _temperature = config.get("temperature", 0.7)
    judge_model = config.get("judge_model", "claude-sonnet-4-20250514")
    judge_weight = config.get("judge_weight", 0.2)

    # 2. Load golden dataset
    cases = load_test_cases(dataset_dir, limit=limit, category=category)
    print(f"Loaded {len(cases)} test cases")

    if not cases:
        print("ERROR: No matching test cases found", file=sys.stderr)
        sys.exit(1)

    # 3. Dry-run path: list cases, handle repeat/baseline, exit 0
    if dry_run:
        effective_repeat = repeat if repeat and repeat >= 1 else 1

        if effective_repeat > 1:
            # Repeat dry-run N times to verify deterministic loading
            pass_rates: list[float] = []
            for run_idx in range(effective_repeat):
                print(f"\n=== Repeat {run_idx + 1}/{effective_repeat} ===")
                for c in cases:
                    print(
                        f"  {c.id} [{c.category}] persona={c.persona} "
                        f"keywords={len(c.must_contain)} source={c.source_type}"
                    )
                print(f"{len(cases)} cases loaded (dry-run, no API calls)")
                pass_rates.append(0.0)

            mean_rate = sum(pass_rates) / len(pass_rates)
            stddev = 0.0  # dry-run is always 0.0 so stddev is always 0
            print(f"\nRepeat Statistics ({effective_repeat} dry-runs):")
            print(f"  mean pass_rate: {mean_rate:.2%}")
            print(f"  stddev:         {stddev:.4f}")
        else:
            for c in cases:
                print(
                    f"  {c.id} [{c.category}] persona={c.persona} "
                    f"keywords={len(c.must_contain)} source={c.source_type}"
                )
            print(f"\n{len(cases)} cases loaded (dry-run, no API calls)")

        if output_path:
            if with_judge:
                # Use run_single_eval to get judge placeholder scores
                judge_results, _, _ = run_single_eval(
                    cases, server_url, threshold, True, output_path, severity_threshold,
                    with_judge=True, judge_model=judge_model, judge_weight=judge_weight,
                )
            else:
                write_json_output(output_path, [], 0.0, threshold, cases=cases)

        # Save baseline if requested (works with --dry-run)
        if save_baseline_path:
            dry_baseline: dict[str, Any] = {
                "metadata": {
                    "model": _model or "eval-runner",
                },
                "overall_pass_rate": 0.0,
                "severity_accuracy": 0.0,
                "threshold": threshold,
                "total_cases": len(cases),
                "total_passed": 0,
                "results": [
                    {
                        "test_id": c.id,
                        "category": c.category,
                        "expected_severity": c.severity,
                    }
                    for c in cases
                ],
            }
            save_baseline(save_baseline_path, dry_baseline)

        # Baseline comparison if requested (works with --dry-run)
        if baseline_path:
            bl_data = load_baseline(baseline_path)
            _is_reg, bl_detail = check_regression(0.0, bl_data)
            print(f"baseline: {bl_detail}")

        sys.exit(0)

    # 4. Handle --repeat (live mode only)
    effective_repeat = repeat if repeat and repeat >= 1 else 1

    if effective_repeat > 1:
        print(f"Running {effective_repeat} repeat(s)...")
        pass_rates: list[float] = []
        all_results: list[CaseResult] = []

        for run_idx in range(effective_repeat):
            print(f"\n=== Repeat {run_idx + 1}/{effective_repeat} ===")
            run_output = None
            # Only write output on the last run
            if output_path and run_idx == effective_repeat - 1:
                run_output = output_path

            results, overall_rate, sev_acc = run_single_eval(
                cases, server_url, threshold, False, run_output, severity_threshold,
                with_judge=with_judge, judge_model=judge_model, judge_weight=judge_weight,
            )
            pass_rates.append(overall_rate)
            all_results = results

        # Compute statistics
        mean_rate = sum(pass_rates) / len(pass_rates)
        if len(pass_rates) > 1:
            variance = sum((r - mean_rate) ** 2 for r in pass_rates) / len(pass_rates)
            stddev = math.sqrt(variance)
        else:
            stddev = 0.0

        print(f"\nRepeat Statistics ({effective_repeat} runs):")
        print(f"  mean pass_rate: {mean_rate:.2%}")
        print(f"  stddev:         {stddev:.4f}")
        for i, rate in enumerate(pass_rates):
            print(f"  run {i + 1}: {rate:.2%}")
        print()

        overall_rate = mean_rate
        results = all_results

    else:
        # Single run (live mode)
        results, overall_rate, severity_accuracy = run_single_eval(
            cases, server_url, threshold, False, output_path, severity_threshold,
            with_judge=with_judge, judge_model=judge_model, judge_weight=judge_weight,
        )

    # 5. Print results table
    if results:
        overall_rate = print_results_table(results, threshold)

    # 6. Compute severity accuracy for display
    if results:
        sev_correct = sum(1 for r in results if r.severity_match)
        severity_accuracy = sev_correct / len(results) if results else 0.0
        print(f"severity_accuracy: {severity_accuracy:.0%} (threshold: {severity_threshold:.0%})")
        if severity_accuracy < severity_threshold:
            print(f"WARNING: severity_accuracy below threshold ({severity_accuracy:.0%} < {severity_threshold:.0%})")

    # 6b. Meta-evaluation (optional, requires --with-judge)
    if meta_eval and results:
        # Build labels dict from results: use keyword pass/fail as "human" label
        # and judge_score for judge label
        labels: dict[str, dict[str, Any]] = {}
        for r in results:
            if r.judge_score is not None:
                keyword_rate = r.hits / r.total_keywords if r.total_keywords > 0 else 0.0
                human_label = "pass" if keyword_rate >= threshold else "fail"
                labels[r.test_id] = {
                    "human": human_label,
                    "judge_score": r.judge_score,
                }
        if labels:
            agreement_rate = compute_agreement(labels)
            total = len(labels)
            agreements = int(agreement_rate * total)
            disagreements = total - agreements
            gate_passed = check_agreement_gate(agreement_rate, 0.7)

            print()
            print("Meta-Evaluation Report:")
            print(f"  agreement_rate: {agreement_rate:.2%} ({agreements}/{total})")
            print(f"  disagreements: {disagreements}")
            print(f"  gate (>= 0.7): {'PASS' if gate_passed else 'FAIL'}")

            if agreement_rate < 0.7:
                print(
                    "WARNING: Judge-keyword agreement below 0.7 threshold. "
                    "Review quality may need calibration.",
                )
        else:
            print("WARNING: --meta-eval requested but no judge scores available.", file=sys.stderr)

    # 7. Save baseline if requested
    if save_baseline_path:
        live_baseline: dict[str, Any] = {
            "metadata": {
                "model": _model or "eval-runner",
            },
            "overall_pass_rate": overall_rate,
            "severity_accuracy": severity_accuracy if results else 0.0,
            "threshold": threshold,
            "total_cases": len(results) if results else 0,
            "total_passed": sum(1 for r in results if r.passed) if results else 0,
            "results": [
                {
                    "test_id": r.test_id,
                    "category": r.category,
                    "passed": r.passed,
                    "must_contain_hits": r.hits,
                    "must_contain_total": r.total_keywords,
                    "detected_severity": r.detected_severity,
                    "expected_severity": r.expected_severity,
                    "severity_match": r.severity_match,
                }
                for r in results
            ] if results else [],
        }
        save_baseline(save_baseline_path, live_baseline)

    # 8. Baseline comparison if requested
    is_regression = False
    if baseline_path:
        baseline_data = load_baseline(baseline_path)
        is_regression, detail = check_regression(overall_rate, baseline_data)
        print(f"baseline: {detail}")
        if is_regression:
            print("REGRESSION DETECTED -- exiting with code 3")

    # 9. Exit code logic
    if is_regression:
        sys.exit(3)
    elif overall_rate >= threshold:
        print(f"PASS: {overall_rate:.0%} >= {threshold:.0%} threshold")
        sys.exit(0)
    elif overall_rate >= 0.5:
        print(f"PARTIAL: {overall_rate:.0%} (50%-{threshold:.0%})")
        sys.exit(2)
    else:
        print(f"FAIL: {overall_rate:.0%} < 50%")
        sys.exit(1)


# ---------------------------------------------------------------------------
# CLI (argparse)
# ---------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    """Build argument parser for eval-runner."""
    parser = argparse.ArgumentParser(
        prog="eval-runner",
        description=(
            "AI Code Review Bot -- eval runner. "
            "Reads golden dataset YAML files, calls POST /review for each test case, "
            "evaluates must_contain keywords, and prints category-level results."
        ),
    )
    parser.add_argument(
        "--config",
        dest="config_path",
        default=str(DEFAULT_CONFIG_PATH),
        help="Path to eval-config.yaml (default: evals/eval-config.yaml)",
    )
    parser.add_argument(
        "--dataset-dir",
        dest="dataset_dir",
        default=str(DEFAULT_DATASET_DIR),
        help="Path to golden-dataset directory (default: evals/golden-dataset)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Limit number of test cases to run",
    )
    parser.add_argument(
        "--category",
        type=str,
        default=None,
        help="Run only cases from a specific category",
    )
    parser.add_argument(
        "--output",
        dest="output_path",
        type=str,
        default=None,
        help="Write JSON results to this file path",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="Load cases and print summary without calling API",
    )
    parser.add_argument(
        "--save-baseline",
        dest="save_baseline",
        type=str,
        default=None,
        help="Save evaluation results as baseline JSON to this path",
    )
    parser.add_argument(
        "--baseline",
        dest="baseline",
        type=str,
        default=None,
        help="Load baseline JSON from this path and compare with current run",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=None,
        help="Run evaluation N times and report variance",
    )
    parser.add_argument(
        "--with-judge",
        action="store_true",
        default=False,
        help="Enable LLM-as-Judge evaluation via Anthropic API (requires ANTHROPIC_API_KEY in live mode)",
    )
    parser.add_argument(
        "--meta-eval",
        action="store_true",
        default=False,
        help="Compute meta-evaluation agreement between judge and keyword labels (requires --with-judge)",
    )
    return parser


def main() -> None:
    """Entry point."""
    parser = build_parser()
    args = parser.parse_args()

    if args.repeat is not None and args.repeat < 1:
        print("ERROR: --repeat must be >= 1", file=sys.stderr)
        sys.exit(1)

    run_eval(
        config_path=Path(args.config_path),
        dataset_dir=Path(args.dataset_dir),
        limit=args.limit,
        category=args.category,
        output_path=args.output_path,
        dry_run=args.dry_run,
        save_baseline_path=args.save_baseline,
        baseline_path=args.baseline,
        repeat=args.repeat,
        with_judge=args.with_judge,
        meta_eval=args.meta_eval,
    )


if __name__ == "__main__":
    main()
