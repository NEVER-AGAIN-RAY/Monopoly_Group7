#!/usr/bin/env python3
"""Write a concise delivery status report for Monopoly Deal AI artifacts."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--local-prefix", type=Path, default=Path("backend/models/distillation/local-enhanced-20260524-multiseed"))
    parser.add_argument("--production-prefix", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args(argv)

    report = build_report(args.local_prefix, args.production_prefix)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report["productionReady"] else 1


def build_report(local_prefix: Path, production_prefix: Path | None) -> Dict[str, Any]:
    local_run_summary_path = local_prefix.parent / f"{local_prefix.name}-run_summary.md"
    local_readiness_path = local_prefix.parent / f"{local_prefix.name}-readiness.json"
    local_trace_audit_path = local_prefix.parent / f"{local_prefix.name}-trace_audit.json"
    local_model_path = local_prefix.parent / f"{local_prefix.name}-mlp" / "candidate_ranker_mlp.json"
    local_seed_summary_path = local_prefix.parent / f"{local_prefix.name}-seed_summary.md"
    local_gameplay_matrix_path = local_prefix.parent / f"{local_prefix.name}-gameplay_matrix.md"
    local_gameplay_matrix_smoke_path = local_prefix.parent / f"{local_prefix.name}-gameplay-matrix-smoke" / "summary.md"
    local_gameplay_path = local_prefix.parent / f"{local_prefix.name}-mlp" / "gameplay_vs_hard.json"
    local_seed_model_path = first_existing(
        local_prefix.parent / f"{local_prefix.name}-seed73-mlp" / "candidate_ranker_mlp.json",
        local_prefix.parent / f"{local_prefix.name}-seed42-mlp" / "candidate_ranker_mlp.json",
        local_prefix.parent / f"{local_prefix.name}-seed11-mlp" / "candidate_ranker_mlp.json",
    )
    local_seed_gameplay_path = first_existing(
        local_prefix.parent / f"{local_prefix.name}-seed73-mlp" / "gameplay_vs_hard.json",
        local_prefix.parent / f"{local_prefix.name}-seed42-mlp" / "gameplay_vs_hard.json",
        local_prefix.parent / f"{local_prefix.name}-seed11-mlp" / "gameplay_vs_hard.json",
    )
    local_seed_run_summary_path = first_existing(
        local_prefix.parent / f"{local_prefix.name}-seed73-run_summary.md",
        local_prefix.parent / f"{local_prefix.name}-seed42-run_summary.md",
        local_prefix.parent / f"{local_prefix.name}-seed11-run_summary.md",
    )
    local_seed_readiness_path = first_existing(
        local_prefix.parent / f"{local_prefix.name}-seed73-readiness.json",
        local_prefix.parent / f"{local_prefix.name}-seed42-readiness.json",
        local_prefix.parent / f"{local_prefix.name}-seed11-readiness.json",
    )
    local_seed_trace_audit_path = first_existing(
        local_prefix.parent / f"{local_prefix.name}-seed73-trace_audit.json",
        local_prefix.parent / f"{local_prefix.name}-seed42-trace_audit.json",
        local_prefix.parent / f"{local_prefix.name}-seed11-trace_audit.json",
    )
    local_artifacts_archive = local_prefix.parent / f"{local_prefix.name}-artifacts.tar.gz"
    local_handoff_archive = local_prefix.parent / f"{local_prefix.name}-training-handoff.tar.gz"
    local_seed_summary = load_json(local_prefix.parent / f"{local_prefix.name}-seed_summary.json")
    local_gameplay = load_json(local_prefix.parent / f"{local_prefix.name}-gameplay_matrix.json")
    local_run_summary = load_json(local_prefix.parent / f"{local_prefix.name}-run_summary.json")
    local_readiness = load_json(local_prefix.parent / f"{local_prefix.name}-readiness.json")
    local_metrics = load_json(local_prefix.parent / f"{local_prefix.name}-mlp" / "metrics.json")
    local_gameplay_single = load_json(local_prefix.parent / f"{local_prefix.name}-mlp" / "gameplay_vs_hard.json")
    production_summary = load_json(production_prefix.parent / f"{production_prefix.name}-seed_summary.json") if production_prefix else {}
    production_readiness = load_json(production_prefix.parent / f"{production_prefix.name}-readiness.json") if production_prefix else {}
    production_ready_runs = int_number(production_summary.get("productionReadyRuns"))
    if production_ready_runs == 0 and not production_summary and production_readiness.get("ready"):
        production_ready_runs = 1
    production_ready = production_ready_runs > 0 and bool(production_readiness.get("ready"))
    local_ready_runs = int_number(local_seed_summary.get("readyRuns"))
    if local_ready_runs == 0 and local_readiness.get("ready"):
        local_ready_runs = 1
    local_production_ready_runs = int_number(local_seed_summary.get("productionReadyRuns"))
    local_rows = int_number(local_readiness.get("rows") or local_run_summary.get("rows"))
    if local_rows == 0:
        seed_rows = [
            int_number(row.get("rows"))
            for row in list_value(local_seed_summary.get("rows"))
            if isinstance(row, dict)
        ]
        local_rows = max(seed_rows) if seed_rows else 0
    return {
        "schema": "monopoly-deal-ai-delivery-status-v1",
        "deepseekKeyPresent": bool(os.environ.get("DEEPSEEK_API_KEY") or os.environ.get("MONOPOLY_DEEPSEEK_API_KEY")),
        "localPrefix": str(local_prefix),
        "localReadyRuns": local_ready_runs,
        "localProductionReadyRuns": local_production_ready_runs,
        "localRows": local_rows,
        "localValidationTop1Mean": first_nonzero(
            metric_mean(local_seed_summary, "validationTop1"),
            number(obj(obj(local_metrics.get("validation")).get("top1"))),
            number(obj(local_run_summary.get("validation")).get("top1")),
        ),
        "localRankerWinRate": first_nonzero(
            metric_mean(local_seed_summary, "rankerWinRate"),
            number(local_gameplay.get("rankerWinRate")),
            number(local_gameplay_single.get("rankerWinRate")),
            number(obj(local_run_summary.get("gameplay")).get("rankerWinRate")),
        ),
        "localRankerBoardRankMean": first_nonzero(
            metric_mean(local_seed_summary, "averageRankerBoardRank"),
            number(obj(local_run_summary.get("gameplay")).get("averageRankerBoardRank")),
        ),
        "localArtifacts": {
            "runSummary": path_text(local_run_summary_path, local_seed_run_summary_path),
            "readiness": path_text(local_readiness_path, local_seed_readiness_path),
            "traceAudit": path_text(local_trace_audit_path, local_seed_trace_audit_path),
            "modelJson": path_text(local_model_path, local_seed_model_path),
            "gameplay": path_text(local_gameplay_path, local_seed_gameplay_path),
            "seedSummary": str(local_seed_summary_path) if local_seed_summary_path.exists() else "",
            "gameplayMatrix": path_text(local_gameplay_matrix_path, local_gameplay_matrix_smoke_path),
            "artifactsArchive": str(local_artifacts_archive) if local_artifacts_archive.exists() else "",
            "handoffArchive": str(local_handoff_archive) if local_handoff_archive.exists() else "",
        },
        "productionPrefix": str(production_prefix) if production_prefix else "",
        "productionReady": production_ready,
        "productionReadyRuns": production_ready_runs,
        "productionReadinessMode": production_readiness.get("mode", "missing") if production_prefix else "not-run",
        "productionMissingReason": missing_reason(production_prefix, production_summary, production_readiness),
    }


def missing_reason(
        production_prefix: Path | None,
        production_summary: Dict[str, Any],
        production_readiness: Dict[str, Any]) -> str:
    if production_prefix is None:
        return "No production prefix was provided; DeepSeek production run has not been completed in this checkout."
    if not production_readiness:
        return "Production readiness report is missing."
    if not production_readiness.get("ready"):
        failures = [
            f"{item.get('name', 'unknown')}={item.get('detail', '')}"
            for item in production_readiness.get("checks", [])
            if isinstance(item, dict) and not item.get("ok")
        ]
        return "; ".join(failures) or "Production readiness is false."
    if not production_summary:
        return ""
    if not production_summary.get("productionReadyRuns"):
        return "Production seed summary has no production-ready runs."
    return ""


def metric_mean(summary: Dict[str, Any], metric: str) -> float:
    return number(obj(obj(summary.get("aggregates")).get(metric)).get("mean"))


def load_json(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    return value if isinstance(value, dict) else {}


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def first_existing(*paths: Path) -> Path:
    for path in paths:
        if path.exists():
            return path
    return Path("")


def path_text(*paths: Path) -> str:
    for path in paths:
        if str(path) and path.exists():
            return str(path)
    return ""


def number(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def int_number(value: Any) -> int:
    return int(number(value))


def first_nonzero(*values: float) -> float:
    for value in values:
        if value:
            return value
    return 0.0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
