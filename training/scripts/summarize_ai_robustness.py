#!/usr/bin/env python3
"""Summarize paired-seed AI reports by explicit robustness scenarios."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path


def wilson_interval(successes: int, total: int, z: float = 1.96) -> list[float]:
    if total <= 0:
        return [0.0, 0.0]
    p = successes / total
    denominator = 1.0 + z * z / total
    center = (p + z * z / (2.0 * total)) / denominator
    half = z * math.sqrt((p * (1.0 - p) + z * z / (4.0 * total)) / total) / denominator
    return [center - half, center + half]


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


def load_report(path: Path) -> dict:
    report = json.loads(path.read_text(encoding="utf-8"))
    pairs = report.get("pairs", [])
    summary = report.get("summary", {})
    h2h = report.get("headToHeadSummary", {})
    paired_outcomes = outcome_counts(pairs)
    legacy_outcomes = legacy_outcome_counts(report)
    score_deltas = [pair_score_delta(pair) for pair in pairs]
    rank_deltas = [pair_rank_delta(pair) for pair in pairs]
    return {
        "path": str(path),
        "pairs": len(pairs),
        "controlLineup": report.get("controlLineup", ""),
        "treatmentLineup": report.get("treatmentLineup", ""),
        "focusSeat": report.get("focusSeat", h2h.get("focusSeat", 0)),
        "randomizeFirstPlayer": bool(report.get("randomizeFirstPlayer", False)),
        "lookaheadWins": int(h2h.get("treatmentFocusNaturalWins", 0)),
        "hardWins": int(h2h.get("controlFocusNaturalWins", 0)),
        "pairedLookahead": paired_outcomes["treatment"],
        "pairedHard": paired_outcomes["control"],
        "pairedTie": paired_outcomes["tie"],
        "legacyScoreTiebreakPairedLookahead": legacy_outcomes["treatment"],
        "legacyScoreTiebreakPairedHard": legacy_outcomes["control"],
        "legacyScoreTiebreakPairedTie": legacy_outcomes["tie"],
        "averageScoreDelta": float(
            summary.get(
                "averageTreatmentMinusControlScore",
                sum(score_deltas) / len(score_deltas) if score_deltas else 0.0,
            )
        ),
        "_scoreDeltas": score_deltas,
        "_rankDeltas": rank_deltas,
        "scoreDeltaDistribution": distribution(score_deltas),
        "rankDeltaDistribution": distribution(rank_deltas),
    }


def scenario_name(row: dict) -> str:
    control = row["controlLineup"].lower()
    treatment = row["treatmentLineup"].lower()
    seats = max(len([x for x in control.replace(";", ",").split(",") if x.strip()]), 0)
    if seats >= 4 or treatment.count(",") >= 3:
        return "four_player_free_for_all"
    if row["randomizeFirstPlayer"]:
        return "two_player_random_first"
    return "two_player_fixed_first"


def public_report(row: dict) -> dict:
    return {
        key: value
        for key, value in row.items()
        if not key.startswith("_")
    }


def summarize_rows(rows: list[dict]) -> dict:
    pairs = sum(row["pairs"] for row in rows)
    lookahead_wins = sum(row["lookaheadWins"] for row in rows)
    hard_wins = sum(row["hardWins"] for row in rows)
    paired_lookahead = sum(row["pairedLookahead"] for row in rows)
    paired_hard = sum(row["pairedHard"] for row in rows)
    paired_tie = sum(row["pairedTie"] for row in rows)
    decisive_paired = paired_lookahead + paired_hard
    weighted_score = sum(row["averageScoreDelta"] * row["pairs"] for row in rows)
    score_deltas = [
        value
        for row in rows
        for value in row.get("_scoreDeltas", [])
    ]
    rank_deltas = [
        value
        for row in rows
        for value in row.get("_rankDeltas", [])
    ]
    paired_rate = paired_lookahead / pairs if pairs else 0.0
    decisive_paired_rate = paired_lookahead / decisive_paired if decisive_paired else 0.0
    return {
        "pairs": pairs,
        "lookaheadWins": lookahead_wins,
        "hardWins": hard_wins,
        "lookaheadWinRate": lookahead_wins / pairs if pairs else 0.0,
        "hardWinRate": hard_wins / pairs if pairs else 0.0,
        "lookaheadWinRate95ci": wilson_interval(lookahead_wins, pairs),
        "primaryMetric": "same_seed_paired_focus_rank_result",
        "primaryMetricOrder": [
            "natural win",
            "better focus-seat board rank",
        ],
        "scorePolicy": "board score is diagnostic only and is not used to break same-rank ties",
        "pairedOutcome": {
            "lookahead": paired_lookahead,
            "hard": paired_hard,
            "tie": paired_tie,
        },
        "pairedTreatmentRate": paired_rate,
        "pairedTreatmentRate95ci": wilson_interval(paired_lookahead, pairs),
        "decisivePairedPairs": decisive_paired,
        "decisivePairedTreatmentRate": decisive_paired_rate,
        "decisivePairedTreatmentRate95ci": wilson_interval(paired_lookahead, decisive_paired),
        "pairedMargin": paired_lookahead - paired_hard,
        "averageScoreDelta": weighted_score / pairs if pairs else 0.0,
        "scoreDeltaDistribution": distribution(score_deltas),
        "rankDeltaDistribution": distribution(rank_deltas),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    rows = [load_report(path) for path in args.reports]
    grouped: dict[str, list[dict]] = {}
    for row in rows:
        grouped.setdefault(scenario_name(row), []).append(row)

    out = {
        "overall": summarize_rows(rows),
        "groups": {
            name: {
                **summarize_rows(group_rows),
                "reports": [public_report(row) for row in group_rows],
            }
            for name, group_rows in sorted(grouped.items())
        },
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        parent = args.output.parent
        if parent:
            parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
