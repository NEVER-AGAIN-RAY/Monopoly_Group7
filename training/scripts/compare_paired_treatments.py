#!/usr/bin/env python3
"""Compare treatment focus-seat results from two same-seed paired reports.

This is for A/B testing two candidate policies that were each evaluated as
`hard,hard` control vs `hard,llm` treatment on the same seed range. The primary
comparison is rank-only: natural win first, then board rank. Board score is
reported only as a diagnostic.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total <= 0:
        return (0.0, 0.0)
    p = successes / total
    denominator = 1.0 + z * z / total
    center = (p + z * z / (2.0 * total)) / denominator
    half = z * math.sqrt((p * (1.0 - p) + z * z / (4.0 * total)) / total) / denominator
    return (center - half, center + half)


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = max(0.0, min(1.0, q)) * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[lower])
    return float(ordered[lower] * (upper - position) + ordered[upper] * (position - lower))


def distribution(values: list[float]) -> dict[str, Any]:
    if not values:
        return {
            "min": 0.0,
            "p10": 0.0,
            "median": 0.0,
            "p90": 0.0,
            "max": 0.0,
            "mean": 0.0,
            "positive": 0,
            "zero": 0,
            "negative": 0,
        }
    return {
        "min": min(values),
        "p10": percentile(values, 0.10),
        "median": statistics.median(values),
        "p90": percentile(values, 0.90),
        "max": max(values),
        "mean": sum(values) / len(values),
        "positive": sum(1 for value in values if value > 0),
        "zero": sum(1 for value in values if value == 0),
        "negative": sum(1 for value in values if value < 0),
    }


def pair_key(pair: dict[str, Any]) -> tuple[int, int, bool]:
    return (
        int(pair.get("game", 0)),
        int(pair.get("deckSeed", 0)),
        bool(pair.get("randomizeFirstPlayer", False)),
    )


def focus(pair: dict[str, Any]) -> dict[str, Any]:
    return obj(obj(pair.get("treatment")).get("focusPlayer"))


def compare_focus(baseline: dict[str, Any], variant: dict[str, Any]) -> str:
    if not baseline and not variant:
        return "tie"
    if not baseline:
        return "variant"
    if not variant:
        return "baseline"
    baseline_natural = bool(baseline.get("naturalWinner", False))
    variant_natural = bool(variant.get("naturalWinner", False))
    if baseline_natural != variant_natural:
        return "variant" if variant_natural else "baseline"
    baseline_rank = int(baseline.get("boardRank", 0))
    variant_rank = int(variant.get("boardRank", 0))
    if baseline_rank != variant_rank:
        return "variant" if variant_rank < baseline_rank else "baseline"
    return "tie"


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path)
    parser.add_argument("variant", type=Path)
    parser.add_argument("--baseline-label", default="baseline")
    parser.add_argument("--variant-label", default="variant")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    baseline_report = json.loads(args.baseline.read_text(encoding="utf-8"))
    variant_report = json.loads(args.variant.read_text(encoding="utf-8"))
    baseline_pairs = {pair_key(pair): pair for pair in baseline_report.get("pairs", [])}
    variant_pairs = {pair_key(pair): pair for pair in variant_report.get("pairs", [])}
    shared_keys = [key for key in baseline_pairs if key in variant_pairs]
    shared_keys.sort()

    counts = {"variant": 0, "baseline": 0, "tie": 0}
    score_deltas: list[float] = []
    rank_deltas: list[float] = []
    rows: list[dict[str, Any]] = []
    for key in shared_keys:
        baseline_pair = baseline_pairs[key]
        variant_pair = variant_pairs[key]
        baseline_focus = focus(baseline_pair)
        variant_focus = focus(variant_pair)
        winner = compare_focus(baseline_focus, variant_focus)
        counts[winner] += 1
        score_delta = float(variant_focus.get("boardScore", 0)) - float(baseline_focus.get("boardScore", 0))
        rank_delta = float(baseline_focus.get("boardRank", 0)) - float(variant_focus.get("boardRank", 0))
        score_deltas.append(score_delta)
        rank_deltas.append(rank_delta)
        rows.append(
            {
                "game": key[0],
                "deckSeed": key[1],
                "randomizeFirstPlayer": key[2],
                "winner": winner,
                "baselineNaturalWinner": bool(baseline_focus.get("naturalWinner", False)),
                "variantNaturalWinner": bool(variant_focus.get("naturalWinner", False)),
                "baselineRank": int(baseline_focus.get("boardRank", 0)),
                "variantRank": int(variant_focus.get("boardRank", 0)),
                "variantMinusBaselineScore": score_delta,
                "variantMinusBaselineRankDelta": rank_delta,
            }
        )

    decisive = counts["variant"] + counts["baseline"]
    decisive_rate = counts["variant"] / decisive if decisive else 0.0
    result = {
        "primary_metric": "same_seed_treatment_focus_rank_result",
        "baseline": str(args.baseline),
        "variant": str(args.variant),
        "baselineLabel": args.baseline_label,
        "variantLabel": args.variant_label,
        "sharedPairs": len(shared_keys),
        "missingFromBaseline": len(set(variant_pairs) - set(baseline_pairs)),
        "missingFromVariant": len(set(baseline_pairs) - set(variant_pairs)),
        "outcomes": counts,
        "decisivePairs": decisive,
        "decisiveVariantRate": decisive_rate,
        "decisiveVariantRate95ci": list(wilson_interval(counts["variant"], decisive)),
        "variantMargin": counts["variant"] - counts["baseline"],
        "scoreDeltaDistribution": distribution(score_deltas),
        "rankDeltaDistribution": distribution(rank_deltas),
        "rows": rows,
    }
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
