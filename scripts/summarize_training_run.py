#!/usr/bin/env python3
"""Summarize one Monopoly Deal distillation/training run for handoff review."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("output_prefix", type=Path)
    parser.add_argument("--gameplay", type=Path, default=None)
    parser.add_argument("--markdown-output", type=Path, default=None)
    parser.add_argument("--json-output", type=Path, default=None)
    args = parser.parse_args(argv)

    summary = build_summary(args.output_prefix, args.gameplay)
    markdown = render_markdown(summary)
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(markdown, encoding="utf-8")
    else:
        print(markdown)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if summary["handoffReady"] else 1


def build_summary(output_prefix: Path, gameplay_path: Path | None = None) -> Dict[str, Any]:
    model_dir = output_prefix.parent / f"{output_prefix.name}-mlp"
    paths = {
        "manifest": output_prefix.parent / f"{output_prefix.name}-dataset_manifest.json",
        "audit": output_prefix.parent / f"{output_prefix.name}-trace_audit.json",
        "quality": output_prefix.parent / f"{output_prefix.name}-quality_report.json",
        "readiness": output_prefix.parent / f"{output_prefix.name}-readiness.json",
        "metrics": model_dir / "metrics.json",
        "gameplay": gameplay_path or model_dir / "gameplay_vs_hard.json",
    }
    manifest = load_json(paths["manifest"])
    audit = load_json(paths["audit"])
    quality = load_json(paths["quality"])
    readiness = load_json(paths["readiness"])
    metrics = load_json(paths["metrics"])
    gameplay = load_json(paths["gameplay"])
    validation = obj(metrics.get("validation"))
    token_usage = obj(quality.get("tokenUsage")) or obj(manifest.get("tokenUsageEstimate"))

    readiness_failures = failed_checks(readiness)
    audit_failures = failed_checks(audit)
    handoff_ready = bool(readiness.get("ready")) and not readiness_failures
    production_ready = handoff_ready and readiness.get("mode") == "production"

    return {
        "schema": "monopoly-deal-training-run-summary-v1",
        "outputPrefix": str(output_prefix),
        "handoffReady": handoff_ready,
        "productionReady": production_ready,
        "readinessMode": readiness.get("mode", "missing"),
        "readinessFailures": readiness_failures,
        "auditOk": bool(audit.get("ok")) if audit else False,
        "auditFailures": audit_failures,
        "rows": int_number(readiness.get("rows"), manifest.get("rows"), quality.get("rows")),
        "sessions": int_number(manifest.get("sessions"), quality.get("sessions")),
        "teacherSources": obj(readiness.get("byTeacherSource")) or obj(manifest.get("byTeacherSource")),
        "decisionKinds": obj(readiness.get("byDecisionKind")) or obj(manifest.get("byDecisionKind")),
        "playerCounts": obj(manifest.get("byPlayerCount")) or obj(quality.get("playerCounts")),
        "rowsWithTokenUsage": int_number(readiness.get("rowsWithTokenUsage"), token_usage.get("rowsWithUsage")),
        "tokenUsage": token_usage,
        "validation": {
            "top1": number(validation.get("top1")),
            "mrr": number(validation.get("mrr")),
            "firstCandidateTop1": number(validation.get("firstCandidateTop1")),
            "randomExpectedTop1": number(validation.get("randomExpectedTop1")),
            "byKind": obj(validation.get("byKind")),
        },
        "model": {
            "type": metrics.get("modelType", "unknown"),
            "device": metrics.get("device", "unknown"),
            "inputDim": int_number(metrics.get("inputDim")),
            "epochs": int_number(metrics.get("epochs")),
            "splitBy": metrics.get("splitBy", "unknown"),
            "balanceByKind": bool(metrics.get("balanceByKind")),
        },
        "gameplay": compact_gameplay(gameplay),
        "artifacts": {key: str(path) for key, path in paths.items()},
    }


def render_markdown(summary: Dict[str, Any]) -> str:
    readiness_word = "production-ready" if summary["productionReady"] else "local-ready" if summary["handoffReady"] else "not-ready"
    lines = [
        "# Monopoly Deal Training Run Summary",
        "",
        f"- Output prefix: `{summary['outputPrefix']}`",
        f"- Status: **{readiness_word}**",
        f"- Readiness mode: `{summary['readinessMode']}`",
        f"- Rows: {summary['rows']}",
        f"- Sessions: {summary['sessions']}",
        f"- Teacher sources: {summary['teacherSources']}",
        f"- Decision kinds: {summary['decisionKinds']}",
        f"- Player counts: {summary['playerCounts']}",
        f"- Rows with token usage: {summary['rowsWithTokenUsage']}",
        "",
        "## Readiness",
    ]
    if summary["readinessFailures"]:
        for item in summary["readinessFailures"]:
            lines.append(f"- FAIL `{item.get('name', 'unknown')}`: {item.get('detail', '')}")
    else:
        lines.append("- All readiness checks passed.")

    lines.append("")
    lines.append("## Trace Audit")
    if summary["auditOk"]:
        lines.append("- Audit passed.")
    elif summary["auditFailures"]:
        for item in summary["auditFailures"]:
            lines.append(f"- FAIL `{item.get('name', 'unknown')}`: {item.get('detail', '')}")
    else:
        lines.append("- Audit missing or not passed.")

    val = summary["validation"]
    lines.extend([
        "",
        "## Imitation Metrics",
        f"- Validation top-1: {val['top1']:.3f}",
        f"- Validation MRR: {val['mrr']:.3f}",
        f"- First-candidate baseline: {val['firstCandidateTop1']:.3f}",
        f"- Random expected baseline: {val['randomExpectedTop1']:.3f}",
    ])
    if val["byKind"]:
        lines.append("- Per-kind top-1:")
        for kind, row in sorted(val["byKind"].items()):
            lines.append(f"  - `{kind}`: {number(obj(row).get('top1')):.3f} over {int_number(obj(row).get('decisions'))} decisions")

    model = summary["model"]
    lines.extend([
        "",
        "## Model",
        f"- Type: `{model['type']}`",
        f"- Device: `{model['device']}`",
        f"- Input dim: {model['inputDim']}",
        f"- Epochs: {model['epochs']}",
        f"- Split: `{model['splitBy']}`",
        f"- Balance by kind: {model['balanceByKind']}",
    ])

    gameplay = summary["gameplay"]
    lines.extend([
        "",
        "## Gameplay",
        f"- Opponent: `{gameplay.get('opponentStrategy', 'unknown')}`",
        f"- Games requested/evaluated/natural: {gameplay.get('gamesRequested', 0)} / {gameplay.get('evaluatedGames', 0)} / {gameplay.get('completedGames', 0)}",
        f"- Natural win rate: {number(gameplay.get('naturalWinRate')):.3f}",
        f"- Ranker win rate: {number(gameplay.get('rankerWinRate')):.3f}",
        f"- Average ranker board rank: {number(gameplay.get('averageRankerBoardRank')):.2f}",
        f"- Board lead rate: {number(gameplay.get('rankerBoardLeadRate')):.3f}",
        f"- End reasons: {gameplay.get('endReasons', {})}",
        "",
        "## Token Usage",
    ])
    usage = summary["tokenUsage"]
    if summary["rowsWithTokenUsage"]:
        lines.extend([
            f"- Prompt tokens share: {number(usage.get('promptTokensShare')):.0f}",
            f"- Completion tokens share: {number(usage.get('completionTokensShare')):.0f}",
            f"- Total tokens share: {number(usage.get('totalTokensShare')):.0f}",
            f"- Avg total tokens per decision: {number(usage.get('avgTotalTokensPerDecision')):.1f}",
        ])
    else:
        lines.append("- No token usage metadata. This is expected for local heuristic runs and invalid for production DeepSeek readiness.")

    lines.extend([
        "",
        "## Artifacts",
    ])
    for key, path in summary["artifacts"].items():
        lines.append(f"- `{key}`: `{path}`")
    return "\n".join(lines) + "\n"


def compact_gameplay(gameplay: Dict[str, Any]) -> Dict[str, Any]:
    if not gameplay:
        return {}
    games = gameplay.get("games")
    evaluated = int_number(gameplay.get("evaluatedGames"))
    if evaluated <= 0 and isinstance(games, list):
        evaluated = len(games)
    return {
        "opponentStrategy": gameplay.get("opponentStrategy", "unknown"),
        "gamesRequested": int_number(gameplay.get("gamesRequested")),
        "evaluatedGames": evaluated,
        "completedGames": int_number(gameplay.get("completedGames")),
        "naturalWinRate": number(gameplay.get("naturalWinRate")),
        "rankerWinRate": ranker_win_rate(gameplay, evaluated),
        "averageSnapshots": number(gameplay.get("averageSnapshots")),
        "averageRankerBoardRank": number(gameplay.get("averageRankerBoardRank")),
        "rankerBoardLeadRate": number(gameplay.get("rankerBoardLeadRate")),
        "endReasons": obj(gameplay.get("endReasons")),
    }


def failed_checks(report: Dict[str, Any]) -> List[Dict[str, Any]]:
    checks = report.get("checks") if isinstance(report, dict) else []
    if not isinstance(checks, list):
        return []
    return [item for item in checks if isinstance(item, dict) and not item.get("ok")]


def ranker_win_rate(gameplay: Dict[str, Any], evaluated: int) -> float:
    if "rankerWinRate" in gameplay:
        return number(gameplay.get("rankerWinRate"))
    games = gameplay.get("games")
    if evaluated <= 0 or not isinstance(games, list):
        return 0.0
    wins = sum(1 for game in games if isinstance(game, dict) and bool(game.get("rankerWon")))
    return wins / evaluated


def load_json(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"{path}: invalid JSON: {exc}") from exc
    return value if isinstance(value, dict) else {}


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


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
