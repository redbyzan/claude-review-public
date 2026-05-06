"""
judge_client.py -- LLM-as-Judge client for eval-runner.

Calls the Anthropic Messages API via urllib (stdlib-only) to evaluate
review quality, extracts scores, blends with keyword metrics, and
computes meta-evaluation agreement rates.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
PROMPT_TEMPLATE_PATH = Path(__file__).resolve().parent / "prompts" / "judge-prompt.md"
ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
ANTHROPIC_API_VERSION = "2023-06-01"
MAX_RETRIES = 3


# ---------------------------------------------------------------------------
# Score extraction
# ---------------------------------------------------------------------------
def extract_score(response_text: str) -> int | None:
    """
    Parse the LLM response for the score pattern.

    Looks for "Score: X/5" where X is a single digit.
    Returns the integer 1-5, or None if no valid score is found.
    """
    match = re.search(r"Score:\s*(\d)/5", response_text)
    if match is None:
        return None
    score = int(match.group(1))
    if 1 <= score <= 5:
        return score
    return None


# ---------------------------------------------------------------------------
# Score blending
# ---------------------------------------------------------------------------
def blend_scores(
    keyword_rate: float,
    judge_score: int,
    judge_weight: float = 0.2,
) -> float:
    """
    Blend keyword hit rate with judge score using weighted average.

    Formula: keyword_rate * (1 - judge_weight) + (judge_score / 5) * judge_weight

    Args:
        keyword_rate: Keyword hit rate (0.0 to 1.0).
        judge_score: LLM judge score (1 to 5).
        judge_weight: Weight for the judge component (0.0 to 1.0 inclusive).

    Returns:
        Blended score as a float.

    Raises:
        ValueError: If judge_weight is outside [0.0, 1.0].
    """
    if not (0.0 <= judge_weight <= 1.0):
        raise ValueError(
            f"judge_weight must be between 0.0 and 1.0 inclusive, got {judge_weight}"
        )
    return keyword_rate * (1.0 - judge_weight) + (judge_score / 5.0) * judge_weight


# ---------------------------------------------------------------------------
# Meta-evaluation: agreement computation
# ---------------------------------------------------------------------------
def compute_agreement(labels: dict[str, dict[str, Any]]) -> float:
    """
    Compute agreement rate between judge and human labels.

    Binarizes judge_score >= 4 as "pass" (otherwise "fail").
    Compares against human label ("pass"/"fail").

    Args:
        labels: Dict keyed by test ID. Each value has:
            - human: "pass" or "fail"
            - judge_score: int (1-5)

    Returns:
        Agreement rate as a float (0.0 to 1.0).
    """
    if not labels:
        return 0.0

    agreements = 0
    for _tid, entry in labels.items():
        human_label = entry["human"]
        judge_score = entry["judge_score"]
        judge_label = "pass" if judge_score >= 4 else "fail"
        if judge_label == human_label:
            agreements += 1

    return agreements / len(labels)


def check_agreement_gate(rate: float, threshold: float = 0.7) -> bool:
    """
    Check if agreement rate meets the threshold.

    Args:
        rate: Agreement rate (0.0 to 1.0).
        threshold: Minimum acceptable agreement rate.

    Returns:
        True if rate >= threshold, False otherwise.
    """
    return rate >= threshold


# ---------------------------------------------------------------------------
# Anthropic API call
# ---------------------------------------------------------------------------
def call_judge(
    review_text: str,
    diff: str,
    category: str = "unknown",
    api_key: str = "",
    model: str = "claude-sonnet-4-20250514",
    severity: str = "info",
) -> int:
    """
    Call the Anthropic Messages API to evaluate a review.

    Reads the judge prompt template, substitutes placeholders, sends
    to the API, and extracts the score from the response.

    Args:
        review_text: The review text to evaluate.
        diff: The diff that was reviewed.
        category: Review category for context.
        api_key: Anthropic API key. Falls back to ANTHROPIC_API_KEY env var.
        model: Model to use for judging.
        severity: Expected severity level.

    Returns:
        Judge score as integer 1-5.

    Raises:
        SystemExit: If API key is missing or API call fails after retries.
    """
    # Resolve API key
    resolved_key = api_key or os.environ.get("ANTHROPIC_API_KEY", "")
    if not resolved_key:
        print(
            "ERROR: ANTHROPIC_API_KEY is required for live judge mode. "
            "Set the ANTHROPIC_API_KEY environment variable or pass --dry-run.",
            file=sys.stderr,
        )
        sys.exit(1)

    # Read prompt template
    if not PROMPT_TEMPLATE_PATH.exists():
        print(
            f"ERROR: Judge prompt template not found: {PROMPT_TEMPLATE_PATH}",
            file=sys.stderr,
        )
        sys.exit(1)

    prompt_text = PROMPT_TEMPLATE_PATH.read_text(encoding="utf-8")

    # Substitute placeholders
    prompt_text = prompt_text.replace("{{diff}}", diff)
    prompt_text = prompt_text.replace("{{review}}", review_text)
    prompt_text = prompt_text.replace("{{category}}", category)
    prompt_text = prompt_text.replace("{{severity}}", severity)

    # Build API request
    headers = {
        "x-api-key": resolved_key,
        "anthropic-version": ANTHROPIC_API_VERSION,
        "Content-Type": "application/json",
    }

    body: dict[str, Any] = {
        "model": model,
        "max_tokens": 1024,
        "messages": [
            {"role": "user", "content": prompt_text},
        ],
    }

    req = urllib.request.Request(
        ANTHROPIC_API_URL,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )

    # Call with exponential backoff on 429
    response_text = ""
    for attempt in range(MAX_RETRIES):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                resp_data = json.loads(resp.read().decode("utf-8"))
                # Extract text from response content blocks
                content_blocks = resp_data.get("content", [])
                response_text = " ".join(
                    block.get("text", "")
                    for block in content_blocks
                    if block.get("type") == "text"
                )
                break

        except urllib.error.HTTPError as exc:
            if exc.code == 429:
                wait_time = 2 ** attempt
                print(
                    f"  judge: rate limited (429), retry {attempt + 1}/{MAX_RETRIES} "
                    f"in {wait_time}s...",
                )
                time.sleep(wait_time)
                if attempt == MAX_RETRIES - 1:
                    print(
                        f"ERROR: Judge API rate limit exceeded after {MAX_RETRIES} retries",
                        file=sys.stderr,
                    )
                    sys.exit(1)
                continue
            else:
                body_preview = ""
                try:
                    body_preview = exc.read().decode("utf-8", errors="replace")[:300]
                except Exception:
                    pass
                print(
                    f"ERROR: Judge API HTTP {exc.code}: {body_preview}",
                    file=sys.stderr,
                )
                sys.exit(1)

        except Exception as exc:
            print(
                f"ERROR: Judge API call failed: {type(exc).__name__}: {exc}",
                file=sys.stderr,
            )
            sys.exit(1)

    # Extract score
    score = extract_score(response_text)
    if score is None:
        print(
            f"WARNING: Could not extract score from judge response. "
            f"Response preview: {response_text[:200]}",
            file=sys.stderr,
        )
        return 3  # default to middle score

    return score
