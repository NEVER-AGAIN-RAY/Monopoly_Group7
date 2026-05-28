#!/usr/bin/env python3
"""Summarize paired-seed Monopoly Deal evaluation reports.

The primary comparison is same-seed paired focus-seat rank outcome. Score deltas
are diagnostics only because final Monopoly Deal board scores are heavy tailed
and can swing sharply after set-steal effects.
"""

from __future__ import annotations

import argparse
import statistics
import json
import math
from pathlib import Path


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total <= 0:
        return (0.0, 0.0)
    p = successes / total
    denominator = 1.0 + z * z / total
    center = (p + z * z / (2.0 * total)) / denominator
    half = z * math.sqrt((p * (1.0 - p) + z * z / (4.0 * total)) / total) / denominator
    return (center - half, center + half)


def focus_natural_wins(report: dict, label: str) -> int:
    return sum(
        1
        for pair in report.get("pairs", [])
        if pair.get(label, {}).get("focusPlayer", {}).get("naturalWinner", False)
    )


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    bounded = min(1.0, max(0.0, q))
    position = bounded * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[lower])
    lower_weight = upper - position
    upper_weight = position - lower
    return float(ordered[lower] * lower_weight + ordered[upper] * upper_weight)


def trimmed_mean(values: list[float], trim_fraction: float = 0.10) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    trim = math.floor(len(ordered) * trim_fraction)
    if trim <= 0 or trim * 2 >= len(ordered):
        return sum(ordered) / len(ordered)
    trimmed = ordered[trim:len(ordered) - trim]
    return sum(trimmed) / len(trimmed) if trimmed else sum(ordered) / len(ordered)


def pair_score_delta(pair: dict) -> float:
    comparison = pair.get("comparison", {})
    if "treatmentScore" in comparison and "controlScore" in comparison:
        return float(comparison["treatmentScore"]) - float(comparison["controlScore"])
    treatment_focus = pair.get("treatment", {}).get("focusPlayer", {})
    control_focus = pair.get("control", {}).get("focusPlayer", {})
    return float(treatment_focus.get("boardScore", 0)) - float(control_focus.get("boardScore", 0))


def pair_rank_delta(pair: dict) -> float:
    treatment_focus = pair.get("treatment", {}).get("focusPlayer", {})
    control_focus = pair.get("control", {}).get("focusPlayer", {})
    return float(control_focus.get("boardRank", 0)) - float(treatment_focus.get("boardRank", 0))


def rank_only_outcome(pair: dict) -> str:
    treatment_focus = pair.get("treatment", {}).get("focusPlayer", {})
    control_focus = pair.get("control", {}).get("focusPlayer", {})
    if not control_focus and not treatment_focus:
        return "tie"
    if not control_focus:
        return "treatment"
    if not treatment_focus:
        return "control"
    control_natural = bool(control_focus.get("naturalWinner", False))
    treatment_natural = bool(treatment_focus.get("naturalWinner", False))
    if control_natural != treatment_natural:
        return "treatment" if treatment_natural else "control"
    control_rank = int(control_focus.get("boardRank", 0))
    treatment_rank = int(treatment_focus.get("boardRank", 0))
    if control_rank != treatment_rank:
        return "treatment" if treatment_rank < control_rank else "control"
    return "tie"


def outcome_counts(pairs: list[dict]) -> dict:
    counts = {"treatment": 0, "control": 0, "tie": 0}
    for pair in pairs:
        counts[rank_only_outcome(pair)] += 1
    return counts


def legacy_outcome_counts(report: dict) -> dict:
    outcomes = report.get("summary", {}).get("pairOutcomes", {})
    return {
        "treatment": int(outcomes.get("treatment", 0)),
        "control": int(outcomes.get("control", 0)),
        "tie": int(outcomes.get("tie", 0)),
    }


def distribution(values: list[float]) -> dict:
    if not values:
        return {
            "min": 0.0,
            "p10": 0.0,
            "median": 0.0,
            "p90": 0.0,
            "max": 0.0,
            "mean": 0.0,
            "trimmed_mean_10pct": 0.0,
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
        "trimmed_mean_10pct": trimmed_mean(values),
        "positive": sum(1 for value in values if value > 0),
        "zero": sum(1 for value in values if value == 0),
        "negative": sum(1 for value in values if value < 0),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--min-pairs", type=int, default=0)
    parser.add_argument("--min-treatment-rate", type=float, default=0.0)
    parser.add_argument("--min-paired-treatment-rate", type=float, default=0.0)
    parser.add_argument("--min-decisive-paired-treatment-rate", type=float, default=None)
    parser.add_argument("--min-decisive-paired-pairs", type=int, default=0)
    parser.add_argument("--min-paired-margin", type=int, default=0)
    parser.add_argument("--min-average-score-delta", type=float, default=None)
    parser.add_argument("--min-median-score-delta", type=float, default=None)
    parser.add_argument("--min-trimmed-score-delta", type=float, default=None)
    args = parser.parse_args()

    rows = []
    totals = {
        "pairs": 0,
        "treatment_head_to_head_wins": 0,
        "control_head_to_head_wins": 0,
        "paired_treatment": 0,
        "paired_control": 0,
        "paired_tie": 0,
    }
    all_score_deltas = []
    all_rank_deltas = []
    for path in args.reports:
        report = json.loads(path.read_text(encoding="utf-8"))
        pairs = report.get("pairs", [])
        pair_count = len(pairs)
        treatment_h2h = focus_natural_wins(report, "treatment")
        control_h2h = focus_natural_wins(report, "control")
        paired_outcomes = outcome_counts(pairs)
        paired_treatment = paired_outcomes["treatment"]
        paired_control = paired_outcomes["control"]
        paired_tie = paired_outcomes["tie"]
        legacy_outcomes = legacy_outcome_counts(report)
        score_deltas = [pair_score_delta(pair) for pair in pairs]
        rank_deltas = [pair_rank_delta(pair) for pair in pairs]
        all_score_deltas.extend(score_deltas)
        all_rank_deltas.extend(rank_deltas)

        totals["pairs"] += pair_count
        totals["treatment_head_to_head_wins"] += treatment_h2h
        totals["control_head_to_head_wins"] += control_h2h
        totals["paired_treatment"] += paired_treatment
        totals["paired_control"] += paired_control
        totals["paired_tie"] += paired_tie
        rows.append(
            {
                "path": str(path),
                "pairs": pair_count,
                "treatment_head_to_head_wins": treatment_h2h,
                "control_head_to_head_wins": control_h2h,
                "paired_treatment": paired_treatment,
                "paired_control": paired_control,
                "paired_tie": paired_tie,
                "decisive_paired_pairs": paired_treatment + paired_control,
                "decisive_paired_treatment_rate": (
                    paired_treatment / (paired_treatment + paired_control)
                    if paired_treatment + paired_control else 0.0
                ),
                "legacy_score_tiebreak_paired_treatment": legacy_outcomes["treatment"],
                "legacy_score_tiebreak_paired_control": legacy_outcomes["control"],
                "legacy_score_tiebreak_paired_tie": legacy_outcomes["tie"],
                "average_treatment_minus_control_score": report.get("summary", {}).get(
                    "averageTreatmentMinusControlScore", 0.0
                ),
                "score_delta_distribution": distribution(score_deltas),
                "rank_delta_distribution": distribution(rank_deltas),
                "treatment_head_to_head_rate": treatment_h2h / pair_count if pair_count else 0.0,
                "control_head_to_head_rate": control_h2h / pair_count if pair_count else 0.0,
                "paired_treatment_rate": paired_treatment / pair_count if pair_count else 0.0,
            }
        )

    n = totals["pairs"]
    h2h_ci = wilson_interval(totals["treatment_head_to_head_wins"], n)
    paired_ci = wilson_interval(totals["paired_treatment"], n)
    score_deltas = [float(row["average_treatment_minus_control_score"]) for row in rows]
    paired_margin = totals["paired_treatment"] - totals["paired_control"]
    decisive_paired_pairs = totals["paired_treatment"] + totals["paired_control"]
    average_score_delta = (
        sum(float(row["average_treatment_minus_control_score"]) * int(row["pairs"]) for row in rows) / n
        if n else 0.0
    )
    paired_treatment_rate = totals["paired_treatment"] / n if n else 0.0
    decisive_paired_treatment_rate = (
        totals["paired_treatment"] / decisive_paired_pairs if decisive_paired_pairs else 0.0
    )
    decisive_paired_ci = wilson_interval(totals["paired_treatment"], decisive_paired_pairs)
    score_delta_dist = distribution(all_score_deltas)
    rank_delta_dist = distribution(all_rank_deltas)
    checks = [
        {
            "name": "min_pairs",
            "passed": n >= args.min_pairs,
            "detail": f"{n} >= {args.min_pairs}",
        },
        {
            "name": "min_treatment_rate",
            "passed": (totals["treatment_head_to_head_wins"] / n if n else 0.0) >= args.min_treatment_rate,
            "detail": f"{totals['treatment_head_to_head_wins'] / n if n else 0.0:.4f} >= {args.min_treatment_rate:.4f}",
        },
        {
            "name": "paired_margin",
            "passed": paired_margin >= args.min_paired_margin,
            "detail": f"{paired_margin} >= {args.min_paired_margin}",
        },
        {
            "name": "min_decisive_paired_pairs",
            "passed": decisive_paired_pairs >= args.min_decisive_paired_pairs,
            "detail": f"{decisive_paired_pairs} >= {args.min_decisive_paired_pairs}",
        },
        {
            "name": "min_paired_treatment_rate",
            "passed": paired_treatment_rate >= args.min_paired_treatment_rate,
            "detail": f"{paired_treatment_rate:.4f} >= {args.min_paired_treatment_rate:.4f}",
        },
    ]
    if args.min_decisive_paired_treatment_rate is not None:
        checks.append({
            "name": "min_decisive_paired_treatment_rate",
            "passed": decisive_paired_treatment_rate >= args.min_decisive_paired_treatment_rate,
            "detail": (
                f"{decisive_paired_treatment_rate:.4f} "
                f">= {args.min_decisive_paired_treatment_rate:.4f}"
            ),
        })
    if args.min_average_score_delta is not None:
        checks.append({
            "name": "average_score_delta",
            "passed": average_score_delta >= args.min_average_score_delta,
            "detail": f"{average_score_delta:.3f} >= {args.min_average_score_delta:.3f}",
        })
    if args.min_median_score_delta is not None:
        checks.append({
            "name": "median_score_delta",
            "passed": score_delta_dist["median"] >= args.min_median_score_delta,
            "detail": f"{score_delta_dist['median']:.3f} >= {args.min_median_score_delta:.3f}",
        })
    if args.min_trimmed_score_delta is not None:
        checks.append({
            "name": "trimmed_score_delta",
            "passed": score_delta_dist["trimmed_mean_10pct"] >= args.min_trimmed_score_delta,
            "detail": (
                f"{score_delta_dist['trimmed_mean_10pct']:.3f} "
                f">= {args.min_trimmed_score_delta:.3f}"
            ),
        })
    out = {
        "passed": all(check["passed"] for check in checks),
        "primary_metric": "same_seed_paired_focus_rank_result",
        "primary_metric_order": [
            "natural win",
            "better focus-seat board rank",
        ],
        "score_policy": "board score is diagnostic only and is not used to break same-rank ties",
        "reports": rows,
        "totals": {
            **totals,
            "treatment_head_to_head_rate": totals["treatment_head_to_head_wins"] / n if n else 0.0,
            "control_head_to_head_rate": totals["control_head_to_head_wins"] / n if n else 0.0,
            "treatment_head_to_head_95ci": [h2h_ci[0], h2h_ci[1]],
            "paired_treatment_rate": paired_treatment_rate,
            "paired_treatment_rate_95ci": [paired_ci[0], paired_ci[1]],
            "decisive_paired_pairs": decisive_paired_pairs,
            "decisive_paired_treatment_rate": decisive_paired_treatment_rate,
            "decisive_paired_treatment_rate_95ci": [decisive_paired_ci[0], decisive_paired_ci[1]],
            "paired_margin": paired_margin,
            "average_treatment_minus_control_score": average_score_delta,
            "score_delta_distribution": score_delta_dist,
            "rank_delta_distribution": rank_delta_dist,
            "report_score_delta_min": min(score_deltas) if score_deltas else 0.0,
            "report_score_delta_median": statistics.median(score_deltas) if score_deltas else 0.0,
            "report_score_delta_max": max(score_deltas) if score_deltas else 0.0,
        },
        "checks": checks,
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    if not out["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
