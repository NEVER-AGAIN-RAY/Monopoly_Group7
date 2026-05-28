#!/usr/bin/env python3
"""Inspect rank-only losses and large score swings in paired reports."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import Counter
from pathlib import Path
from typing import Any


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    pos = max(0.0, min(1.0, q)) * (len(ordered) - 1)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return float(ordered[lo])
    return float(ordered[lo] * (hi - pos) + ordered[hi] * (pos - lo))


def focus(pair: dict[str, Any], side: str) -> dict[str, Any]:
    value = pair.get(side, {}).get("focusPlayer", {})
    return value if isinstance(value, dict) else {}


def rank_only_outcome(pair: dict[str, Any]) -> str:
    control = focus(pair, "control")
    treatment = focus(pair, "treatment")
    if not control and not treatment:
        return "tie"
    if not control:
        return "treatment"
    if not treatment:
        return "control"
    control_natural = bool(control.get("naturalWinner", False))
    treatment_natural = bool(treatment.get("naturalWinner", False))
    if control_natural != treatment_natural:
        return "treatment" if treatment_natural else "control"
    control_rank = int(control.get("boardRank", 0))
    treatment_rank = int(treatment.get("boardRank", 0))
    if control_rank != treatment_rank:
        return "treatment" if treatment_rank < control_rank else "control"
    return "tie"


def score_delta(pair: dict[str, Any]) -> float:
    return float(focus(pair, "treatment").get("boardScore", 0)) - float(
        focus(pair, "control").get("boardScore", 0)
    )


def rank_delta(pair: dict[str, Any]) -> int:
    return int(focus(pair, "control").get("boardRank", 0)) - int(
        focus(pair, "treatment").get("boardRank", 0)
    )


def natural_signature(pair: dict[str, Any]) -> str:
    control = "W" if focus(pair, "control").get("naturalWinner", False) else "L"
    treatment = "W" if focus(pair, "treatment").get("naturalWinner", False) else "L"
    return f"{control}->{treatment}"


def run_field(pair: dict[str, Any], side: str, name: str, default: Any = None) -> Any:
    run = pair.get(side, {})
    return run.get(name, default) if isinstance(run, dict) else default


def summarize(paths: list[Path], largest: int) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    outcomes: Counter[str] = Counter()
    natural_signatures: Counter[str] = Counter()
    end_reasons: Counter[str] = Counter()
    score_deltas: list[float] = []
    rank_deltas: list[int] = []
    snapshot_deltas: list[int] = []

    for path in paths:
        report = json.loads(path.read_text(encoding="utf-8"))
        for pair in report.get("pairs", []):
            outcome = rank_only_outcome(pair)
            outcomes[outcome] += 1
            natural_signatures[natural_signature(pair)] += 1
            for side in ("control", "treatment"):
                end_reasons[str(run_field(pair, side, "endReason", "UNKNOWN"))] += 1
            sd = score_delta(pair)
            rd = rank_delta(pair)
            snapshot_delta = int(run_field(pair, "treatment", "snapshots", 0)) - int(
                run_field(pair, "control", "snapshots", 0)
            )
            score_deltas.append(sd)
            rank_deltas.append(rd)
            snapshot_deltas.append(snapshot_delta)
            rows.append(
                {
                    "path": str(path),
                    "game": pair.get("game"),
                    "deckSeed": pair.get("deckSeed"),
                    "randomizeFirstPlayer": pair.get("randomizeFirstPlayer", False),
                    "outcome": outcome,
                    "naturalSignature": natural_signature(pair),
                    "treatmentMinusControlScore": sd,
                    "treatmentMinusControlRankDelta": rd,
                    "treatmentMinusControlSnapshots": snapshot_delta,
                    "control": focus(pair, "control"),
                    "treatment": focus(pair, "treatment"),
                    "controlWinnerPlayerId": run_field(pair, "control", "winnerPlayerId"),
                    "treatmentWinnerPlayerId": run_field(pair, "treatment", "winnerPlayerId"),
                    "controlLastAction": run_field(pair, "control", "lastActionSummary"),
                    "treatmentLastAction": run_field(pair, "treatment", "lastActionSummary"),
                }
            )

    losses = [row for row in rows if row["outcome"] == "control"]
    treatment_wins = [row for row in rows if row["outcome"] == "treatment"]
    large_negative = sorted(rows, key=lambda row: row["treatmentMinusControlScore"])[:largest]
    large_positive = sorted(rows, key=lambda row: row["treatmentMinusControlScore"], reverse=True)[:largest]

    return {
        "reports": [str(path) for path in paths],
        "pairs": len(rows),
        "rankOnlyOutcomes": dict(outcomes),
        "naturalSignatures": dict(natural_signatures),
        "endReasons": dict(end_reasons),
        "scoreDelta": {
            "min": min(score_deltas) if score_deltas else 0.0,
            "p10": percentile(score_deltas, 0.10),
            "median": statistics.median(score_deltas) if score_deltas else 0.0,
            "p90": percentile(score_deltas, 0.90),
            "max": max(score_deltas) if score_deltas else 0.0,
            "mean": sum(score_deltas) / len(score_deltas) if score_deltas else 0.0,
        },
        "rankDelta": {
            "min": min(rank_deltas) if rank_deltas else 0,
            "median": statistics.median(rank_deltas) if rank_deltas else 0,
            "max": max(rank_deltas) if rank_deltas else 0,
            "mean": sum(rank_deltas) / len(rank_deltas) if rank_deltas else 0.0,
        },
        "snapshotDelta": {
            "min": min(snapshot_deltas) if snapshot_deltas else 0,
            "median": statistics.median(snapshot_deltas) if snapshot_deltas else 0,
            "max": max(snapshot_deltas) if snapshot_deltas else 0,
            "mean": sum(snapshot_deltas) / len(snapshot_deltas) if snapshot_deltas else 0.0,
        },
        "losses": losses[:largest],
        "treatmentWins": treatment_wins[:largest],
        "largestNegativeScoreDeltas": large_negative,
        "largestPositiveScoreDeltas": large_positive,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--largest", type=int, default=10)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = summarize(args.reports, args.largest)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
