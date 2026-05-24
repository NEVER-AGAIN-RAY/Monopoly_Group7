#!/usr/bin/env python3
"""Build a consolidated next-day handoff report for Monopoly Deal AI work."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Sequence


DEFAULT_LOCAL_PREFIX = Path("models/distillation/local-enhanced-20260524-multiseed")
DEFAULT_TRACE = Path("data/distillation/local-enhanced-20260524.jsonl")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--local-prefix", type=Path, default=DEFAULT_LOCAL_PREFIX)
    parser.add_argument("--trace", type=Path, default=DEFAULT_TRACE)
    parser.add_argument("--production-prefix", type=Path, default=None)
    parser.add_argument("--output-md", type=Path, default=None)
    parser.add_argument("--output-json", type=Path, default=None)
    args = parser.parse_args(argv)

    output_md = args.output_md or args.local_prefix.parent / f"{args.local_prefix.name}-handoff_report.md"
    output_json = args.output_json or args.local_prefix.parent / f"{args.local_prefix.name}-handoff_report.json"
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    report = build_report(args.local_prefix, args.trace, args.production_prefix, output_md, output_json)
    markdown = render_markdown(report)
    json_text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    refresh_generated_file_info(report, output_md, output_json, markdown, json_text)
    markdown = render_markdown(report)
    json_text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    refresh_generated_file_info(report, output_md, output_json, markdown, json_text)
    output_md.write_text(markdown, encoding="utf-8")
    output_json.write_text(json_text, encoding="utf-8")
    print(f"[handoff] wrote {output_md}")
    print(f"[handoff] wrote {output_json}")
    return 0


def build_report(
        local_prefix: Path,
        trace: Path,
        production_prefix: Path | None,
        output_md: Path | None = None,
        output_json: Path | None = None) -> Dict[str, Any]:
    delivery = load_json(local_prefix.parent / f"{local_prefix.name}-delivery_status.json")
    seed_summary = load_json(local_prefix.parent / f"{local_prefix.name}-seed_summary.json")
    matrix_summary = load_json(local_prefix.parent / f"{local_prefix.name}-gameplay-matrix-smoke" / "summary.json")
    quality_gate = load_json(local_prefix.parent / f"{local_prefix.name}-quality_gate.json")
    representative_prefix = representative_run_prefix(local_prefix, delivery, seed_summary)
    readiness = load_json(representative_prefix.parent / f"{representative_prefix.name}-readiness.json") if str(representative_prefix) else {}
    run_summary = load_json(representative_prefix.parent / f"{representative_prefix.name}-run_summary.json") if str(representative_prefix) else {}
    production = load_production(production_prefix)
    local_ready_runs = int_number(seed_summary.get("readyRuns"), delivery.get("localReadyRuns"))
    production_ready_runs = int_number(seed_summary.get("productionReadyRuns"), delivery.get("localProductionReadyRuns"))
    production_ready = bool(production.get("ready")) if production else bool(delivery.get("productionReady"))

    return {
        "schema": "monopoly-deal-ai-handoff-report-v1",
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "verdict": {
            "localReady": local_ready_runs > 0 and bool(readiness.get("ready", local_ready_runs > 0)),
            "productionReady": production_ready,
            "label": "production-ready" if production_ready else "local-ready-only",
            "blocker": production_blocker(delivery, production, production_prefix),
        },
        "environment": {
            "deepseekKeyPresent": bool(os.environ.get("DEEPSEEK_API_KEY") or os.environ.get("MONOPOLY_DEEPSEEK_API_KEY")),
            "localPrefix": str(local_prefix),
            "productionPrefix": str(production_prefix) if production_prefix else "",
            "representativeRunPrefix": str(representative_prefix),
        },
        "dataset": {
            "trace": file_info(trace),
            "rows": int_number(readiness.get("rows"), delivery.get("localRows")),
            "sessions": int_number(run_summary.get("sessions")),
            "teacherSources": obj(readiness.get("byTeacherSource")) or obj(run_summary.get("teacherSources")),
            "decisionKinds": obj(readiness.get("byDecisionKind")) or obj(run_summary.get("decisionKinds")),
            "rowsWithTokenUsage": int_number(readiness.get("rowsWithTokenUsage")),
            "readinessMode": readiness.get("mode", "missing"),
            "readinessReady": bool(readiness.get("ready")),
            "failedReadinessChecks": failed_checks(readiness),
        },
        "training": {
            "runs": int_number(seed_summary.get("runs")),
            "readyRuns": local_ready_runs,
            "productionReadyRuns": production_ready_runs,
            "validationTop1": metric_summary(seed_summary, "validationTop1", delivery.get("localValidationTop1Mean")),
            "validationMrr": metric_summary(seed_summary, "validationMrr"),
            "firstCandidateTop1": metric_summary(seed_summary, "firstCandidateTop1"),
            "randomExpectedTop1": metric_summary(seed_summary, "randomExpectedTop1"),
            "rankerWinRate": metric_summary(seed_summary, "rankerWinRate", delivery.get("localRankerWinRate")),
            "averageRankerBoardRank": metric_summary(seed_summary, "averageRankerBoardRank", delivery.get("localRankerBoardRankMean")),
            "representativeModel": file_info(first_path(obj(delivery.get("localArtifacts")).get("modelJson"))),
        },
        "gameplayMatrix": matrix_block(matrix_summary),
        "qualityGate": quality_gate_block(quality_gate),
        "files": files_block(local_prefix, delivery, output_md, output_json),
        "claimBoundaries": [
            "Safe: the Mac pipeline can generate backend-legal decision traces, train MLP/linear/KNN rankers, export Java-loadable models, and run gameplay evaluation.",
            "Safe: the current enhanced local trace is a local heuristic baseline with complete local coverage across decision kinds.",
            "Unsafe: calling the current trace DeepSeek-quality or production training data.",
            "Unsafe: using the current gameplay matrix as a paper-grade performance claim; it is a smoke matrix with few games.",
        ],
        "nextCommands": next_commands(trace, production_prefix),
        "paperDirection": [
            "Frame the system as legal-action policy distillation for a complex rule-based card game.",
            "Keep the student constrained to Java-generated legal candidates; the model ranks actions instead of inventing protocol moves.",
            "Paper-grade evidence still needs DeepSeek or stronger teacher labels, multi-seed learning curves, larger gameplay matrices, and ablation baselines.",
        ],
        "validationCommands": [
            "python3 -m unittest tests.test_distill_dataset",
            "mvn -q test -Dtest=DecisionBrokerTest,SimulationWorkerTest,DeepSeekClientConfigTest,TraceRelabelerTest",
            "python3 scripts/check_ai_quality_gate.py --require local",
            "python3 scripts/check_ai_delivery_status.py --output models/distillation/local-enhanced-20260524-multiseed-delivery_status.json",
            "python3 scripts/summarize_ai_handoff.py",
        ],
    }


def load_production(production_prefix: Path | None) -> Dict[str, Any]:
    if production_prefix is None:
        return {}
    readiness = load_json(production_prefix.parent / f"{production_prefix.name}-readiness.json")
    seed_summary = load_json(production_prefix.parent / f"{production_prefix.name}-seed_summary.json")
    return {
        "ready": bool(readiness.get("ready")) and bool(seed_summary.get("productionReadyRuns")),
        "readiness": readiness,
        "seedSummary": seed_summary,
    }


def representative_run_prefix(local_prefix: Path, delivery: Dict[str, Any], seed_summary: Dict[str, Any]) -> Path:
    artifacts = obj(delivery.get("localArtifacts"))
    readiness_path = first_path(artifacts.get("readiness"))
    if str(readiness_path):
        name = readiness_path.name
        suffix = "-readiness.json"
        if name.endswith(suffix):
            return readiness_path.parent / name[:-len(suffix)]
    rows = list_value(seed_summary.get("rows"))
    ready_rows = [row for row in rows if isinstance(row, dict) and row.get("handoffReady")]
    if ready_rows:
        return Path(str(ready_rows[-1].get("outputPrefix", "")))
    for seed in ("73", "42", "11"):
        candidate = local_prefix.parent / f"{local_prefix.name}-seed{seed}"
        if (candidate.parent / f"{candidate.name}-readiness.json").exists():
            return candidate
    return local_prefix


def production_blocker(
        delivery: Dict[str, Any],
        production: Dict[str, Any],
        production_prefix: Path | None) -> str:
    if production and production.get("ready"):
        return ""
    reason = str(delivery.get("productionMissingReason", "")).strip()
    if reason:
        return reason
    if production_prefix is None:
        return "No production prefix was provided; DeepSeek production collection has not been completed."
    return "Production readiness is missing or false."


def matrix_block(matrix: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "path": "models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.json"
        if matrix else "",
        "runs": int_number(matrix.get("runs")),
        "gamesRequested": int_number(matrix.get("gamesRequested")),
        "completedGames": int_number(matrix.get("completedGames")),
        "rankerWinRate": number(matrix.get("rankerWinRate")),
        "averageRankerBoardRank": number(matrix.get("averageRankerBoardRank")),
        "rankerBoardLeadRate": number(matrix.get("rankerBoardLeadRate")),
        "naturalWinRate": number(matrix.get("naturalWinRate")),
        "endReasons": obj(matrix.get("endReasons")),
        "byOpponent": obj(matrix.get("byOpponent")),
        "byPlayerCount": obj(matrix.get("byPlayerCount")),
    }


def quality_gate_block(quality_gate: Dict[str, Any]) -> Dict[str, Any]:
    verdict = obj(quality_gate.get("verdict"))
    gates = obj(quality_gate.get("gates"))
    return {
        "path": "models/distillation/local-enhanced-20260524-multiseed-quality_gate.json"
        if quality_gate else "",
        "label": verdict.get("label", "missing"),
        "localTrainingReady": bool(verdict.get("localTrainingReady")),
        "productionDataReady": bool(verdict.get("productionDataReady")),
        "paperEvidenceReady": bool(verdict.get("paperEvidenceReady")),
        "localFailures": int_number(obj(gates.get("localTraining")).get("failureCount")),
        "productionFailures": int_number(obj(gates.get("productionData")).get("failureCount")),
        "paperFailures": int_number(obj(gates.get("paperEvidence")).get("failureCount")),
    }


def files_block(
        local_prefix: Path,
        delivery: Dict[str, Any],
        output_md: Path | None,
        output_json: Path | None) -> Dict[str, Dict[str, Any]]:
    artifacts = obj(delivery.get("localArtifacts"))
    files = {
        "seedSummary": first_path(artifacts.get("seedSummary")),
        "gameplayMatrix": first_path(artifacts.get("gameplayMatrix")),
        "representativeRunSummary": first_path(artifacts.get("runSummary")),
        "representativeReadiness": first_path(artifacts.get("readiness")),
        "representativeTraceAudit": first_path(artifacts.get("traceAudit")),
        "representativeModelJson": first_path(artifacts.get("modelJson")),
        "representativeGameplay": first_path(artifacts.get("gameplay")),
        "artifactsArchive": first_path(artifacts.get("artifactsArchive")),
        "handoffArchive": first_path(artifacts.get("handoffArchive")),
        "deliveryStatus": local_prefix.parent / f"{local_prefix.name}-delivery_status.json",
        "qualityGateMarkdown": local_prefix.parent / f"{local_prefix.name}-quality_gate.md",
        "qualityGateJson": local_prefix.parent / f"{local_prefix.name}-quality_gate.json",
        "handoffReportMarkdown": output_md or local_prefix.parent / f"{local_prefix.name}-handoff_report.md",
        "handoffReportJson": output_json or local_prefix.parent / f"{local_prefix.name}-handoff_report.json",
        "deliveryChecklist": Path("docs/ai-delivery-checklist.md"),
        "trainingLog": Path("docs/ai-training-log.md"),
        "researchPlan": Path("docs/ai-research-experiment-plan.md"),
    }
    out = {key: file_info(path) for key, path in files.items()}
    for key in ("handoffReportMarkdown", "handoffReportJson"):
        if not out[key]["exists"]:
            out[key]["exists"] = True
            out[key]["generatedByThisRun"] = True
    return out


def next_commands(trace: Path, production_prefix: Path | None) -> List[Dict[str, str]]:
    production_name = str(production_prefix or Path("models/distillation/deepseek-run1"))
    return [
        {
            "label": "Refresh local status report",
            "command": "python3 scripts/check_ai_delivery_status.py --output models/distillation/local-enhanced-20260524-multiseed-delivery_status.json",
        },
        {
            "label": "Fast paid relabel probe when DeepSeek key is available",
            "command": (
                "DEEPSEEK_API_KEY=... scripts/run_relabel_paid_probe.sh "
                f"{trace} data/distillation/deepseek-relabel-probe.jsonl models/distillation/deepseek-relabel-probe"
            ),
        },
        {
            "label": "Full paid DeepSeek production pipeline after probe approval",
            "command": (
                "DEEPSEEK_API_KEY=... MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 "
                "MONOPOLY_PAID_CONFIRM=run-paid-overnight scripts/run_deepseek_production_pipeline.sh"
            ),
        },
        {
            "label": "Windows 5090 training after production trace exists",
            "command": (
                "MONOPOLY_TRAIN_SOURCES=deepseek MONOPOLY_MIN_TRAIN_ROWS=5000 "
                f"scripts/run_seed_replicates.sh data/distillation/deepseek-run1-merged.jsonl {production_name}"
            ),
        },
        {
            "label": "Production gameplay matrix",
            "command": (
                "MONOPOLY_MATRIX_GAMES=50 MONOPOLY_MATRIX_PLAYERS=2,3,4,5 "
                "MONOPOLY_MATRIX_OPPONENTS=easy,normal,hard "
                f"scripts/evaluate_gameplay_matrix.sh {production_name}-seed73-mlp/candidate_ranker_mlp.json "
                f"{production_name}-gameplay-matrix"
            ),
        },
    ]


def render_markdown(report: Dict[str, Any]) -> str:
    verdict = obj(report["verdict"])
    dataset = obj(report["dataset"])
    training = obj(report["training"])
    matrix = obj(report["gameplayMatrix"])
    quality_gate = obj(report.get("qualityGate"))
    env = obj(report["environment"])
    lines = [
        "# Monopoly Deal AI Next-Day Handoff",
        "",
        f"Generated: `{report['generatedAt']}`",
        "",
        "## Verdict",
        "",
        f"- Status: **{verdict['label']}**",
        f"- Local ready: {verdict['localReady']}",
        f"- Production ready: {verdict['productionReady']}",
        f"- Production blocker: {verdict['blocker'] or 'none'}",
        f"- DeepSeek key present in this shell: {env['deepseekKeyPresent']}",
        f"- Representative local run: `{env['representativeRunPrefix']}`",
        "",
        "## What Is Ready",
        "",
        f"- Trace: `{dataset['trace']['path']}` ({dataset['trace']['sizeMb']:.2f} MB)",
        f"- Rows: {dataset['rows']}",
        f"- Sessions: {dataset['sessions']}",
        f"- Teacher sources: `{inline_json(dataset['teacherSources'])}`",
        f"- Decision kinds: `{inline_json(dataset['decisionKinds'])}`",
        f"- Rows with token usage: {dataset['rowsWithTokenUsage']}",
        f"- Readiness mode: `{dataset['readinessMode']}`, ready={dataset['readinessReady']}",
        "",
        "## Training Evidence",
        "",
        f"- Runs: {training['runs']}",
        f"- Local-ready runs: {training['readyRuns']}",
        f"- Production-ready runs: {training['productionReadyRuns']}",
        f"- Validation top-1 mean/std: {fmt_metric(training['validationTop1'])}",
        f"- Validation MRR mean/std: {fmt_metric(training['validationMrr'])}",
        f"- First-candidate baseline mean/std: {fmt_metric(training['firstCandidateTop1'])}",
        f"- Random expected baseline mean/std: {fmt_metric(training['randomExpectedTop1'])}",
        f"- Ranker win rate mean/std: {fmt_metric(training['rankerWinRate'])}",
        f"- Average ranker board rank mean/std: {fmt_metric(training['averageRankerBoardRank'])}",
        f"- Representative model: `{training['representativeModel']['path']}`",
        "",
        "## Gameplay Matrix Smoke",
        "",
        f"- Runs: {matrix['runs']}",
        f"- Games requested/completed: {matrix['gamesRequested']} / {matrix['completedGames']}",
        f"- Ranker win rate: {matrix['rankerWinRate']:.3f}",
        f"- Average board rank: {matrix['averageRankerBoardRank']:.2f}",
        f"- Board lead rate: {matrix['rankerBoardLeadRate']:.3f}",
        f"- End reasons: `{inline_json(matrix['endReasons'])}`",
        "",
        "## Quality Gate",
        "",
        f"- Status: **{quality_gate.get('label', 'missing')}**",
        f"- Local training ready: {quality_gate.get('localTrainingReady', False)}",
        f"- Production data ready: {quality_gate.get('productionDataReady', False)}",
        f"- Paper evidence ready: {quality_gate.get('paperEvidenceReady', False)}",
        f"- Failures local/production/paper: "
        f"{quality_gate.get('localFailures', 0)} / "
        f"{quality_gate.get('productionFailures', 0)} / "
        f"{quality_gate.get('paperFailures', 0)}",
        "",
        "## Claim Boundaries",
        "",
    ]
    lines.extend(f"- {item}" for item in list_value(report.get("claimBoundaries")))
    lines.extend([
        "",
        "## Next Commands",
        "",
    ])
    for item in list_value(report.get("nextCommands")):
        row = obj(item)
        lines.extend([
            f"### {row.get('label', 'Command')}",
            "",
            "```bash",
            row.get("command", ""),
            "```",
            "",
        ])
    lines.extend([
        "## Paper Direction",
        "",
    ])
    lines.extend(f"- {item}" for item in list_value(report.get("paperDirection")))
    lines.extend([
        "",
        "## Key Files",
        "",
        "| Label | Exists | Size MB | Path |",
        "|---|---:|---:|---|",
    ])
    for label, info in obj(report.get("files")).items():
        row = obj(info)
        lines.append(f"| `{label}` | {row.get('exists', False)} | {number(row.get('sizeMb')):.2f} | `{row.get('path', '')}` |")
    lines.extend([
        "",
        "## Validation Commands",
        "",
    ])
    lines.extend(f"- `{item}`" for item in list_value(report.get("validationCommands")))
    lines.append("")
    return "\n".join(lines)


def metric_summary(summary: Dict[str, Any], metric: str, fallback_mean: Any = None) -> Dict[str, float]:
    item = obj(obj(summary.get("aggregates")).get(metric))
    if item:
        return {
            "mean": number(item.get("mean")),
            "std": number(item.get("std")),
            "min": number(item.get("min")),
            "max": number(item.get("max")),
        }
    mean = number(fallback_mean)
    return {"mean": mean, "std": 0.0, "min": mean, "max": mean}


def fmt_metric(value: Dict[str, Any]) -> str:
    return f"{number(value.get('mean')):.3f} / {number(value.get('std')):.3f}"


def file_info(path: Path) -> Dict[str, Any]:
    exists = path.exists() if str(path) else False
    size = path.stat().st_size if exists else 0
    return {
        "path": str(path),
        "exists": exists,
        "generatedByThisRun": False,
        "sizeBytes": size,
        "sizeMb": size / (1024 * 1024),
    }


def refresh_generated_file_info(
        report: Dict[str, Any],
        output_md: Path,
        output_json: Path,
        markdown: str,
        json_text: str) -> None:
    files = obj(report.get("files"))
    generated = {
        "handoffReportMarkdown": (output_md, markdown),
        "handoffReportJson": (output_json, json_text),
    }
    for key, (path, text) in generated.items():
        info = obj(files.get(key))
        info["path"] = str(path)
        info["exists"] = True
        info["generatedByThisRun"] = True
        info["sizeBytes"] = len(text.encode("utf-8"))
        info["sizeMb"] = info["sizeBytes"] / (1024 * 1024)
        files[key] = info


def inline_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def failed_checks(report: Dict[str, Any]) -> List[Dict[str, Any]]:
    checks = report.get("checks") if isinstance(report, dict) else []
    if not isinstance(checks, list):
        return []
    return [item for item in checks if isinstance(item, dict) and not item.get("ok")]


def load_json(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"{path}: invalid JSON: {exc}") from exc
    return value if isinstance(value, dict) else {}


def first_path(value: Any) -> Path:
    text = str(value or "")
    return Path(text) if text else Path("")


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def number(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def int_number(*values: Any) -> int:
    for value in values:
        parsed = number(value)
        if parsed:
            return int(parsed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
