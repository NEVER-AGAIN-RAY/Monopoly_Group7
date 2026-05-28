#!/usr/bin/env python3
"""Quality gate for mixed-battle natural win-rate summaries."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def pct(value: float) -> str:
    return f"{value * 100.0:.2f}%"


def as_float(value: Any) -> float:
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return 0.0
    return 0.0


def as_int(value: Any) -> int:
    return int(as_float(value))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("summary", type=Path)
    parser.add_argument("--min-games", type=int, default=200)
    parser.add_argument("--min-natural-rate", type=float, default=0.95)
    parser.add_argument("--min-win-rate", type=float, default=0.55)
    parser.add_argument("--min-ci-low", type=float, default=0.50)
    parser.add_argument("--max-unknown-rate", type=float, default=0.02)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    summary = json.loads(args.summary.read_text(encoding="utf-8"))
    totals = summary.get("totals", {})
    games = as_int(totals.get("games"))
    natural_games = as_int(totals.get("naturalGames"))
    unknown_games = as_int(totals.get("unknownGames"))
    win_rate = as_float(totals.get("teamNaturalWinRate"))
    ci = totals.get("teamNaturalWinRate95ci", [0.0, 0.0])
    ci_low = as_float(ci[0] if isinstance(ci, list) and ci else 0.0)
    natural_rate = natural_games / games if games else 0.0
    unknown_rate = unknown_games / games if games else 0.0

    checks = [
        {
            "name": "min_games",
            "passed": games >= args.min_games,
            "detail": f"{games} >= {args.min_games}",
        },
        {
            "name": "min_natural_rate",
            "passed": natural_rate >= args.min_natural_rate,
            "detail": f"{pct(natural_rate)} >= {pct(args.min_natural_rate)}",
        },
        {
            "name": "max_unknown_rate",
            "passed": unknown_rate <= args.max_unknown_rate,
            "detail": f"{pct(unknown_rate)} <= {pct(args.max_unknown_rate)}",
        },
        {
            "name": "min_win_rate",
            "passed": win_rate >= args.min_win_rate,
            "detail": f"{pct(win_rate)} >= {pct(args.min_win_rate)}",
        },
        {
            "name": "ci_low_above_hard_baseline",
            "passed": ci_low > args.min_ci_low,
            "detail": f"{pct(ci_low)} > {pct(args.min_ci_low)}",
        },
    ]
    out = {
        "schema": "monopoly-mixed-winrate-gate-v1",
        "passed": all(check["passed"] for check in checks),
        "summaryPath": str(args.summary),
        "team": summary.get("team"),
        "primaryMetric": summary.get("primaryMetric"),
        "totals": totals,
        "checks": checks,
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if out["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
