#!/usr/bin/env python3
"""Compare two mixed-battle natural win-rate summaries."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


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


def load_totals(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    totals = data.get("totals", {})
    wins = as_int(totals.get("teamNaturalWins"))
    games = as_int(totals.get("naturalGames"))
    return {
        "path": str(path),
        "team": data.get("team", totals.get("team")),
        "wins": wins,
        "naturalGames": games,
        "winRate": wins / games if games else 0.0,
        "ci95": totals.get("teamNaturalWinRate95ci", [0.0, 0.0]),
        "unknownGames": as_int(totals.get("unknownGames")),
    }


def two_proportion_z(a: dict[str, Any], b: dict[str, Any]) -> dict[str, float]:
    wins_a = as_int(a["wins"])
    wins_b = as_int(b["wins"])
    n_a = as_int(a["naturalGames"])
    n_b = as_int(b["naturalGames"])
    if n_a <= 0 or n_b <= 0:
        return {"z": 0.0, "pApprox": 1.0, "standardError": 0.0}
    p_a = wins_a / n_a
    p_b = wins_b / n_b
    pooled = (wins_a + wins_b) / (n_a + n_b)
    se = math.sqrt(pooled * (1.0 - pooled) * (1.0 / n_a + 1.0 / n_b))
    if se <= 0:
        return {"z": 0.0, "pApprox": 1.0, "standardError": 0.0}
    z = (p_a - p_b) / se
    p_approx = math.erfc(abs(z) / math.sqrt(2.0))
    return {"z": z, "pApprox": p_approx, "standardError": se}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("variant_summary", type=Path)
    parser.add_argument("baseline_summary", type=Path)
    parser.add_argument("--min-absolute-lift", type=float, default=0.03)
    parser.add_argument("--max-p-approx", type=float, default=0.10)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    variant = load_totals(args.variant_summary)
    baseline = load_totals(args.baseline_summary)
    stats = two_proportion_z(variant, baseline)
    lift = as_float(variant["winRate"]) - as_float(baseline["winRate"])
    passed = lift >= args.min_absolute_lift and stats["pApprox"] <= args.max_p_approx
    out = {
        "schema": "monopoly-mixed-winrate-comparison-v1",
        "passedPromotionComparison": passed,
        "comparisonPolicy": (
            "Variant should exceed baseline by a practical absolute lift and "
            "a rough two-proportion p-value threshold before promotion."
        ),
        "variant": variant,
        "baseline": baseline,
        "absoluteLift": lift,
        "relativeLift": lift / baseline["winRate"] if baseline["winRate"] else 0.0,
        "twoProportionZ": stats["z"],
        "pApproxTwoSided": stats["pApprox"],
        "checks": [
            {
                "name": "min_absolute_lift",
                "passed": lift >= args.min_absolute_lift,
                "detail": f"{lift:.4f} >= {args.min_absolute_lift:.4f}",
            },
            {
                "name": "rough_significance",
                "passed": stats["pApprox"] <= args.max_p_approx,
                "detail": f"{stats['pApprox']:.4f} <= {args.max_p_approx:.4f}",
            },
        ],
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
