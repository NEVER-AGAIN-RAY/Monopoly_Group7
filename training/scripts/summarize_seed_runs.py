#!/usr/bin/env python3
"""Summarize multiple Monopoly Deal training runs produced with different seeds."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any, Dict, List, Sequence


METRICS = [
    "validationTop1",
    "validationMrr",
    "firstCandidateTop1",
    "randomExpectedTop1",
    "averageRankerBoardRank",
    "rankerBoardLeadRate",
    "rankerWinRate",
    "naturalWinRate",
]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_summaries", nargs="+", type=Path)
    parser.add_argument("--markdown-output", type=Path, default=None)
    parser.add_argument("--json-output", type=Path, default=None)
    args = parser.parse_args(argv)

    rows = [load_row(path) for path in args.run_summaries]
    summary = build_summary(rows)
    markdown = render_markdown(summary)
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(markdown, encoding="utf-8")
    else:
        print(markdown)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if summary["readyRuns"] == summary["runs"] and summary["runs"] > 0 else 1


def load_row(path: Path) -> Dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return load_row_from_data(data, str(path))


def load_row_from_data(data: Dict[str, Any], path: str = "") -> Dict[str, Any]:
    validation = obj(data.get("validation"))
    gameplay = obj(data.get("gameplay"))
    model = obj(data.get("model"))
    return {
        "path": path,
        "outputPrefix": data.get("outputPrefix", ""),
        "handoffReady": bool(data.get("handoffReady")),
        "productionReady": bool(data.get("productionReady")),
        "readinessMode": data.get("readinessMode", "missing"),
        "rows": int_number(data.get("rows")),
        "validationTop1": number(validation.get("top1")),
        "validationMrr": number(validation.get("mrr")),
        "firstCandidateTop1": number(validation.get("firstCandidateTop1")),
        "randomExpectedTop1": number(validation.get("randomExpectedTop1")),
        "averageRankerBoardRank": number(gameplay.get("averageRankerBoardRank")),
        "rankerBoardLeadRate": number(gameplay.get("rankerBoardLeadRate")),
        "rankerWinRate": number(gameplay.get("rankerWinRate")),
        "naturalWinRate": number(gameplay.get("naturalWinRate")),
        "evaluatedGames": int_number(gameplay.get("evaluatedGames")),
        "completedGames": int_number(gameplay.get("completedGames")),
        "device": model.get("device", "unknown"),
        "splitBy": model.get("splitBy", "unknown"),
        "epochs": int_number(model.get("epochs")),
    }


def build_summary(rows: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    aggregates = {metric: aggregate([number(row.get(metric)) for row in rows]) for metric in METRICS}
    return {
        "schema": "monopoly-deal-seed-run-summary-v1",
        "runs": len(rows),
        "readyRuns": sum(1 for row in rows if row["handoffReady"]),
        "productionReadyRuns": sum(1 for row in rows if row["productionReady"]),
        "rows": list(rows),
        "aggregates": aggregates,
    }


def render_markdown(summary: Dict[str, Any]) -> str:
    lines = [
        "# Monopoly Deal Multi-Seed Training Summary",
        "",
        f"- Runs: {summary['runs']}",
        f"- Ready runs: {summary['readyRuns']}",
        f"- Production-ready runs: {summary['productionReadyRuns']}",
        "",
        "## Aggregate Metrics",
        "| Metric | Mean | Std | Min | Max |",
        "|---|---:|---:|---:|---:|",
    ]
    for metric in METRICS:
        item = summary["aggregates"][metric]
        lines.append(
            f"| `{metric}` | {item['mean']:.3f} | {item['std']:.3f} | {item['min']:.3f} | {item['max']:.3f} |"
        )
    lines.extend([
        "",
        "## Runs",
        "| Output | Ready | Production | Rows | Top1 | MRR | Board Rank | Lead Rate | Win Rate | Evaluated/Natural |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ])
    for row in summary["rows"]:
        lines.append(
            f"| `{row['outputPrefix']}` | {row['handoffReady']} | {row['productionReady']} | "
            f"{row['rows']} | {row['validationTop1']:.3f} | {row['validationMrr']:.3f} | "
            f"{row['averageRankerBoardRank']:.2f} | {row['rankerBoardLeadRate']:.3f} | "
            f"{row['rankerWinRate']:.3f} | "
            f"{row['evaluatedGames']}/{row['completedGames']} |"
        )
    return "\n".join(lines) + "\n"


def aggregate(values: Sequence[float]) -> Dict[str, float]:
    if not values:
        return {"mean": 0.0, "std": 0.0, "min": 0.0, "max": 0.0}
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    return {
        "mean": mean,
        "std": math.sqrt(variance),
        "min": min(values),
        "max": max(values),
    }


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def int_number(value: Any) -> int:
    return int(number(value))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
