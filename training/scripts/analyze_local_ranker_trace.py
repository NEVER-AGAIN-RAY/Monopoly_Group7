#!/usr/bin/env python3
"""Analyze LocalRanker trace confidence and hard-policy overrides."""

from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
from typing import Any, Iterable


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float | None:
    try:
        if value is None:
            return None
        out = float(value)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    xs = sorted(values)
    pos = max(0.0, min(1.0, q)) * (len(xs) - 1)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return xs[lo]
    return xs[lo] * (hi - pos) + xs[hi] * (pos - lo)


def load_rows(paths: Iterable[Path]) -> Iterable[dict[str, Any]]:
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                raw = line.strip()
                if not raw:
                    continue
                try:
                    row = json.loads(raw)
                except json.JSONDecodeError as exc:
                    raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
                yield row


def candidate_summaries(row: dict[str, Any]) -> dict[str, str]:
    out: dict[str, str] = {}
    request = obj(row.get("request"))
    for candidate in row.get("candidates", []) or request.get("candidates", []):
        if isinstance(candidate, dict):
            cid = str(candidate.get("id", ""))
            if cid:
                out[cid] = str(candidate.get("summary", ""))
    return out


def action_bucket(summary: str) -> str:
    text = summary.upper()
    if text.startswith("ACTION "):
        parts = text.split()
        return "ACTION_" + (parts[1].strip(".,") if len(parts) > 1 else "UNKNOWN")
    if text.startswith("DEPLOY"):
        return "DEPLOY"
    if "DEPOSIT" in text or text.startswith("BANK"):
        return "DEPOSIT"
    if text.startswith("DISCARD"):
        return "DISCARD"
    if text.startswith("PAY "):
        return "PAYMENT"
    return text.split()[0].strip(".,") if text.split() else "UNKNOWN"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", nargs="+", type=Path)
    parser.add_argument(
        "--thresholds",
        default="0,0.25,0.5,0.75,1,1.5,2,3",
        help="Comma-separated hybrid margin thresholds to simulate.",
    )
    parser.add_argument("--top", type=int, default=12)
    args = parser.parse_args()

    thresholds = [float(x) for x in args.thresholds.split(",") if x.strip()]
    total = 0
    with_scores = 0
    overrides = 0
    margins: list[float] = []
    override_margins: list[float] = []
    by_kind = collections.Counter()
    override_by_kind = collections.Counter()
    low_margin_overrides: list[dict[str, Any]] = []

    for row in load_rows(args.trace):
        total += 1
        result = obj(row.get("result"))
        metadata = obj(result.get("metadata"))
        choice_id = str(result.get("choiceId", ""))
        hard_id = str(metadata.get("hardChoiceId", ""))
        score = number(metadata.get("score"))
        hard_score = number(metadata.get("hardChoiceScore"))
        summaries = candidate_summaries(row)
        summary = summaries.get(choice_id, "")
        kind = action_bucket(summary)
        by_kind[kind] += 1
        if score is None or hard_score is None:
            continue
        with_scores += 1
        margin = score - hard_score
        margins.append(margin)
        is_override = bool(choice_id and hard_id and choice_id != hard_id)
        if is_override:
            overrides += 1
            override_margins.append(margin)
            override_by_kind[kind] += 1
            if margin < 1.0 and len(low_margin_overrides) < args.top:
                low_margin_overrides.append(
                    {
                        "decisionId": obj(row.get("request")).get("decisionId", ""),
                        "margin": margin,
                        "choice": choice_id,
                        "hard": hard_id,
                        "choiceSummary": summary,
                        "hardSummary": summaries.get(hard_id, ""),
                    }
                )

    simulated = {}
    for threshold in thresholds:
        kept = sum(1 for margin in override_margins if margin >= threshold)
        simulated[str(threshold)] = {
            "keptOverrides": kept,
            "fallbackOverrides": len(override_margins) - kept,
            "keptOverrideRateAmongScoredDecisions": kept / with_scores if with_scores else 0.0,
        }

    output = {
        "traces": [str(path) for path in args.trace],
        "rows": total,
        "scoredRows": with_scores,
        "overrideRows": overrides,
        "overrideRate": overrides / with_scores if with_scores else 0.0,
        "marginDistribution": {
            "min": percentile(margins, 0.0),
            "p10": percentile(margins, 0.10),
            "median": percentile(margins, 0.50),
            "p90": percentile(margins, 0.90),
            "max": percentile(margins, 1.0),
        },
        "overrideMarginDistribution": {
            "min": percentile(override_margins, 0.0),
            "p10": percentile(override_margins, 0.10),
            "median": percentile(override_margins, 0.50),
            "p90": percentile(override_margins, 0.90),
            "max": percentile(override_margins, 1.0),
        },
        "simulatedHybridThresholds": simulated,
        "topDecisionKinds": by_kind.most_common(args.top),
        "topOverrideKinds": override_by_kind.most_common(args.top),
        "lowMarginOverrideExamples": low_margin_overrides,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
