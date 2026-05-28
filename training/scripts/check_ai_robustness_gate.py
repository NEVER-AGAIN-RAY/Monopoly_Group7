#!/usr/bin/env python3
"""Quality gate for local AI robustness summaries.

The primary gameplay metric is same-seed paired focus-seat outcome. Final board
score is high variance in Monopoly Deal, so score checks are auxiliary and are
not enabled by default.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def pct(value: float) -> str:
    return f"{value * 100.0:.2f}%"


def wilson_interval(successes: int, total: int, z: float = 1.96) -> list[float]:
    if total <= 0:
        return [0.0, 0.0]
    p = successes / total
    denominator = 1.0 + z * z / total
    center = (p + z * z / (2.0 * total)) / denominator
    half = z * math.sqrt((p * (1.0 - p) + z * z / (4.0 * total)) / total) / denominator
    return [center - half, center + half]


def check_scope(name: str, data: dict, args: argparse.Namespace, require_ci: bool) -> list[dict]:
    checks: list[dict] = []
    pairs = int(data.get("pairs", 0))
    lookahead_rate = float(data.get("lookaheadWinRate", 0.0))
    hard_rate = float(data.get("hardWinRate", 0.0))
    ci = data.get("lookaheadWinRate95ci", [0.0, 0.0])
    ci_low = float(ci[0]) if isinstance(ci, list) and ci else 0.0
    paired = data.get("pairedOutcome", {})
    paired_lookahead = int(paired.get("lookahead", 0))
    paired_hard = int(paired.get("hard", 0))
    paired_ties = int(paired.get("tie", 0))
    decisive_pairs = int(data.get("decisivePairedPairs", paired_lookahead + paired_hard))
    paired_rate = float(data.get("pairedTreatmentRate", paired_lookahead / pairs if pairs else 0.0))
    decisive_paired_rate = float(data.get(
        "decisivePairedTreatmentRate",
        paired_lookahead / decisive_pairs if decisive_pairs else 0.0,
    ))
    paired_margin = paired_lookahead - paired_hard
    paired_ci = data.get("pairedTreatmentRate95ci", wilson_interval(paired_lookahead, pairs))
    paired_ci_low = paired_ci[0]
    decisive_paired_ci = data.get(
        "decisivePairedTreatmentRate95ci",
        wilson_interval(paired_lookahead, decisive_pairs),
    )
    decisive_paired_ci_low = decisive_paired_ci[0]
    average_score_delta = float(data.get("averageScoreDelta", 0.0))

    checks.append({
        "scope": name,
        "name": "min_pairs",
        "passed": pairs >= args.min_pairs,
        "detail": f"{pairs} >= {args.min_pairs}",
    })
    checks.append({
        "scope": name,
        "name": "auxiliary_natural_win_rate",
        "passed": args.min_margin is None or lookahead_rate >= hard_rate + args.min_margin,
        "detail": (
            "disabled"
            if args.min_margin is None
            else f"{pct(lookahead_rate)} >= {pct(hard_rate)} + {pct(args.min_margin)}"
        ),
    })
    checks.append({
        "scope": name,
        "name": "paired_outcome_leads",
        "passed": paired_lookahead > paired_hard,
        "detail": f"{paired_lookahead} > {paired_hard}",
    })
    checks.append({
        "scope": name,
        "name": "decisive_paired_treatment_rate",
        "passed": decisive_paired_rate >= args.min_paired_rate,
        "detail": f"{pct(decisive_paired_rate)} >= {pct(args.min_paired_rate)} "
                f"({paired_lookahead}/{paired_hard}/{paired_ties})",
    })
    checks.append({
        "scope": name,
        "name": "min_decisive_paired_pairs",
        "passed": decisive_pairs >= args.min_decisive_pairs,
        "detail": f"{decisive_pairs} >= {args.min_decisive_pairs}",
    })
    checks.append({
        "scope": name,
        "name": "paired_margin",
        "passed": paired_margin >= args.min_paired_margin,
        "detail": f"{paired_margin} >= {args.min_paired_margin}",
    })
    checks.append({
        "scope": name,
        "name": "auxiliary_score_delta",
        "passed": args.min_average_score_delta is None or average_score_delta >= args.min_average_score_delta,
        "detail": (
            "disabled"
            if args.min_average_score_delta is None
            else f"{average_score_delta:.3f} >= {args.min_average_score_delta:.3f}"
        ),
    })
    if require_ci:
        if args.min_ci_lower is not None:
            checks.append({
                "scope": name,
                "name": "auxiliary_natural_win_ci_lower_bound",
                "passed": ci_low >= args.min_ci_lower,
                "detail": f"{pct(ci_low)} >= {pct(args.min_ci_lower)}",
            })
        checks.append({
            "scope": name,
            "name": "decisive_paired_ci_lower_bound",
            "passed": decisive_paired_ci_low >= args.min_paired_ci_lower,
            "detail": f"{pct(decisive_paired_ci_low)} >= {pct(args.min_paired_ci_lower)}",
        })
        checks.append({
            "scope": name,
            "name": "paired_all_pairs_rate_reported",
            "passed": True,
            "detail": f"{pct(paired_rate)} over all pairs; all-pair CI low {pct(paired_ci_low)}",
        })
    return checks


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("summary", type=Path)
    parser.add_argument("--min-pairs", type=int, default=100)
    parser.add_argument("--overall-min-pairs", type=int, default=1000)
    parser.add_argument("--min-margin", type=float, default=None)
    parser.add_argument("--min-ci-lower", type=float, default=None)
    parser.add_argument("--min-paired-rate", type=float, default=0.52)
    parser.add_argument("--min-paired-ci-lower", type=float, default=0.52)
    parser.add_argument("--min-paired-margin", type=int, default=1)
    parser.add_argument("--overall-min-paired-margin", type=int, default=None)
    parser.add_argument("--min-decisive-pairs", type=int, default=20)
    parser.add_argument("--overall-min-decisive-pairs", type=int, default=100)
    parser.add_argument("--min-average-score-delta", type=float, default=None)
    parser.add_argument("--require-groups", default="two_player_fixed_first,two_player_random_first,four_player_free_for_all")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    summary = json.loads(args.summary.read_text(encoding="utf-8"))
    checks: list[dict] = []
    overall_args = argparse.Namespace(**{
        **vars(args),
        "min_pairs": args.overall_min_pairs,
        "min_decisive_pairs": args.overall_min_decisive_pairs,
        "min_paired_margin": (
            args.overall_min_paired_margin
            if args.overall_min_paired_margin is not None
            else args.min_paired_margin
        ),
    })
    checks.extend(check_scope("overall", summary.get("overall", {}), overall_args, True))

    groups = summary.get("groups", {})
    required = [name.strip() for name in args.require_groups.split(",") if name.strip()]
    for name in required:
        exists = name in groups
        checks.append({
            "scope": name,
            "name": "group_present",
            "passed": exists,
            "detail": "present" if exists else "missing",
        })
        if exists:
            checks.extend(check_scope(name, groups[name], args, False))

    passed = all(check["passed"] for check in checks)
    out = {
        "passed": passed,
        "summaryPath": str(args.summary),
        "checks": checks,
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        parent = args.output.parent
        if parent:
            parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
