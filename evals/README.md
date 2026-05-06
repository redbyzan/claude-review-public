# Evals Pipeline

Automated evaluation framework for the AI Code Review Bot. Validates review
quality against a golden dataset of 22 test cases across 7 categories.

## Overview

The eval pipeline reads golden-dataset YAML files, sends each case to the
review API (or simulates in dry-run), evaluates keyword hits, detects
severity levels, and optionally runs LLM-as-Judge scoring with meta-evaluation.

## Quick Start (Local)

Run a dry-run to verify all golden dataset files parse correctly:

```bash
python3 evals/eval-runner.py --dry-run
```

This loads all 22 test cases without making any API calls. No secrets required.

## Golden Dataset Structure

```
evals/golden-dataset/
  architecture/   arch-001.yaml .. arch-003.yaml
  exception/      exc-001.yaml  .. exc-003.yaml
  performance/    perf-001.yaml .. perf-003.yaml
  readability/    read-001.yaml .. read-003.yaml
  security/       sec-001.yaml  .. sec-003.yaml
  spring/         spring-001.yaml .. spring-003.yaml
  test/           test-001.yaml .. test-004.yaml
```

Each YAML file follows this schema:

```yaml
id: "unique-test-id"
category: "architecture"
input:
  diff: |
    <git diff content>
  pr_title: "PR title"
  pr_author: "author"
  repo: "org/repo"
  persona: "mentor" | "cynic"        # evaluation-only, NOT sent to API
expected:
  must_contain:                       # keywords the review must include
    - "keyword1"
    - "keyword2"
  severity: "info" | "warning" | "critical"
source:
  type: "real" | "synthetic"
```

**Fields:**

- `id` -- Unique test case identifier (e.g., `arch-001`)
- `category` -- One of: architecture, exception, performance, readability,
  security, spring, test
- `input.diff` -- The git diff to review
- `input.pr_title` -- Pull request title
- `input.pr_author` -- Pull request author
- `input.repo` -- GitHub repository (`org/repo` format)
- `input.persona` -- Review persona (mentor or cynic). Used for logging only;
  NOT sent to the API
- `expected.must_contain` -- List of keywords the review response must contain
  (case-insensitive match)
- `expected.severity` -- Expected severity level: info, warning, or critical
- `source.type` -- `real` (from production) or `synthetic` (hand-crafted)

## eval-config.yaml

Configuration file at `evals/eval-config.yaml`:

| Field | Purpose |
|-------|---------|
| `model` | Model identifier for logging |
| `temperature` | Generation temperature (reserved) |
| `server_url` | Production review API endpoint |
| `judge_model` | Anthropic model for LLM-as-Judge |
| `judge_weight` | Weight for blended score (0-1) |
| `thresholds.must_contain_hit_rate` | Pass threshold (default 80%) |
| `thresholds.severity_accuracy` | Severity accuracy threshold |
| `thresholds.max_response_time_ms` | Maximum allowed response time |

## CLI Reference

```
python3 evals/eval-runner.py [OPTIONS]
```

| Flag | Description |
|------|-------------|
| `--dry-run` | Load cases and print summary without calling API. No secrets needed. |
| `--config PATH` | Path to eval-config.yaml (default: evals/eval-config.yaml) |
| `--dataset-dir PATH` | Path to golden-dataset directory (default: evals/golden-dataset) |
| `--limit N` | Run only the first N test cases |
| `--category CAT` | Run only cases from a specific category |
| `--output PATH` | Write JSON results to this file path |
| `--with-judge` | Enable LLM-as-Judge evaluation via Anthropic API (requires `ANTHROPIC_API_KEY` in live mode) |
| `--meta-eval` | Compute judge-keyword agreement rate (requires `--with-judge`) |
| `--baseline PATH` | Load baseline JSON and compare current run against it for regression |
| `--save-baseline PATH` | Save current run results as baseline JSON |
| `--repeat N` | Run evaluation N times and report variance |

**Exit codes:**

| Code | Meaning |
|------|---------|
| 0 | pass_rate >= threshold |
| 1 | pass_rate < 50% or fatal setup error |
| 2 | pass_rate between 50% and threshold-1 |
| 3 | regression detected (baseline comparison failed) |

## CI Workflows

### PR Fast-Check (`eval.yml`)

- **Trigger:** pull_request to `main` (paths: `evals/**`)
- **Job:** `pr-fast-check`
- **What it does:** Runs `python3 evals/eval-runner.py --dry-run`
- **Secrets:** None. Dry-run mode makes no API calls and requires no secrets.
  Safe for fork PRs.
- **Purpose:** Validates that golden dataset YAML files parse correctly and
  eval-runner.py can load all cases.

### Nightly Full Run (`eval-nightly.yml`)

- **Trigger:** schedule (daily at 03:00 UTC) and workflow_dispatch
- **Job:** `nightly-full`
- **What it does:** Runs `python3 evals/eval-runner.py --limit 3 --with-judge --meta-eval --save-baseline`
- **Secrets:** `REVIEW_SECRET` and `ANTHROPIC_API_KEY` (from GitHub Actions secrets)
- **No pull_request trigger:** Prevents secret exposure from fork PRs
- **Purpose:** Runs 3 live review API calls with judge scoring and meta-evaluation.
  Uses `--limit 3` to minimize production API calls while still providing signal.
  Saves a dated baseline file for regression tracking.
- **Artifact:** Baseline JSON uploaded with 30-day retention

## Regression Gate and ROLLBACK

The regression gate compares the current run's overall pass_rate against a saved
baseline. A regression is detected if the pass_rate drops by 5% or more
from the baseline.

**How to use:**

1. Save a baseline after a good run:
   ```bash
   python3 evals/eval-runner.py --save-baseline evals/baselines/good-run.json
   ```

2. Compare future runs against it:
   ```bash
   python3 evals/eval-runner.py --baseline evals/baselines/good-run.json
   ```

3. If exit code is 3, a regression was detected and the change should be rolled
   back.

**ROLLBACK procedure:** If the nightly full run detects a regression (exit code 3),
revert the most recent deployment and re-deploy the last known good version.
The baseline files in `evals/baselines/` track historical performance for
comparison.

## How to Add a New Test Case

1. Choose the appropriate category directory under `evals/golden-dataset/`
2. Create a new YAML file following the naming convention: `{category-prefix}-{NNN}.yaml`
3. Use the schema above to fill in all required fields
4. Set `expected.must_contain` to keywords the review must mention
5. Set `expected.severity` to the appropriate level
6. Run `python3 evals/eval-runner.py --dry-run` to verify the file parses correctly
7. The PR fast-check will validate the new file on pull request
