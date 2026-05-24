#!/usr/bin/env python3
"""Evaluate local, production, and paper-readiness gates for AI artifacts."""

from __future__ import annotations

import argparse
import collections
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Sequence


DEFAULT_TRACE = Path("data/distillation/local-enhanced-20260524.jsonl")
DEFAULT_PREFIX = Path("models/distillation/local-enhanced-20260524-multiseed")
REQUIRED_KINDS = ["PLAY_CARD", "JUST_SAY_NO", "PAYMENT", "OVERFLOW_DISCARD"]
REPORTABLE_PLAYER_COUNTS = ["2", "3", "4", "5"]
REPORTABLE_OPPONENTS = ["easy", "normal", "hard"]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, default=DEFAULT_TRACE)
    parser.add_argument("--local-prefix", type=Path, default=DEFAULT_PREFIX)
    parser.add_argument("--production-prefix", type=Path, default=None)
    parser.add_argument("--output-json", type=Path, default=None)
    parser.add_argument("--output-md", type=Path, default=None)
    parser.add_argument("--require", choices=["none", "local", "production", "paper"], default="none")
    parser.add_argument("--min-local-rows", type=int, default=5000)
    parser.add_argument("--min-production-rows", type=int, default=5000)
    parser.add_argument("--min-rare-kind-rows", type=int, default=100)
    parser.add_argument("--min-validation-top1", type=float, default=0.55)
    parser.add_argument("--min-paper-games-per-cell", type=int, default=50)
    args = parser.parse_args(argv)

    output_json = args.output_json or args.local_prefix.parent / f"{args.local_prefix.name}-quality_gate.json"
    output_md = args.output_md or args.local_prefix.parent / f"{args.local_prefix.name}-quality_gate.md"
    report = build_report(
        trace=args.trace,
        local_prefix=args.local_prefix,
        production_prefix=args.production_prefix,
        min_local_rows=args.min_local_rows,
        min_production_rows=args.min_production_rows,
        min_rare_kind_rows=args.min_rare_kind_rows,
        min_validation_top1=args.min_validation_top1,
        min_paper_games_per_cell=args.min_paper_games_per_cell,
    )
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    output_md.write_text(render_markdown(report), encoding="utf-8")
    print(f"[quality-gate] wrote {output_json}")
    print(f"[quality-gate] wrote {output_md}")
    return exit_code(report, args.require)


def build_report(
        trace: Path,
        local_prefix: Path,
        production_prefix: Path | None,
        min_local_rows: int,
        min_production_rows: int,
        min_rare_kind_rows: int,
        min_validation_top1: float,
        min_paper_games_per_cell: int) -> Dict[str, Any]:
    rows = load_rows(trace)
    delivery = load_json(local_prefix.parent / f"{local_prefix.name}-delivery_status.json")
    seed_summary = load_json(local_prefix.parent / f"{local_prefix.name}-seed_summary.json")
    matrix = load_json(local_prefix.parent / f"{local_prefix.name}-gameplay-matrix-smoke" / "summary.json")
    representative_prefix = representative_prefix_from_delivery(local_prefix, delivery, seed_summary)
    readiness = load_json(representative_prefix.parent / f"{representative_prefix.name}-readiness.json") if str(representative_prefix) else {}
    run_summary = load_json(representative_prefix.parent / f"{representative_prefix.name}-run_summary.json") if str(representative_prefix) else {}
    production_readiness = load_json(production_prefix.parent / f"{production_prefix.name}-readiness.json") if production_prefix else {}
    production_seed_summary = load_json(production_prefix.parent / f"{production_prefix.name}-seed_summary.json") if production_prefix else {}
    trace_summary = summarize_trace(rows)
    metrics = summarize_metrics(seed_summary, run_summary)
    local_gate = local_training_gate(
        trace=trace,
        trace_summary=trace_summary,
        readiness=readiness,
        seed_summary=seed_summary,
        metrics=metrics,
        delivery=delivery,
        min_local_rows=min_local_rows,
        min_validation_top1=min_validation_top1,
    )
    production_gate = production_data_gate(
        production_prefix=production_prefix,
        production_readiness=production_readiness,
        production_seed_summary=production_seed_summary,
        trace_summary=trace_summary,
        min_production_rows=min_production_rows,
        min_rare_kind_rows=min_rare_kind_rows,
    )
    paper_gate = paper_evidence_gate(
        production_gate=production_gate,
        seed_summary=production_seed_summary or seed_summary,
        matrix=matrix,
        min_games_per_cell=min_paper_games_per_cell,
    )
    return {
        "schema": "monopoly-deal-ai-quality-gate-v1",
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "trace": str(trace),
        "localPrefix": str(local_prefix),
        "productionPrefix": str(production_prefix) if production_prefix else "",
        "verdict": verdict(local_gate, production_gate, paper_gate),
        "traceSummary": trace_summary,
        "trainingMetrics": metrics,
        "gates": {
            "localTraining": local_gate,
            "productionData": production_gate,
            "paperEvidence": paper_gate,
        },
        "nextActions": next_actions(local_gate, production_gate, paper_gate),
    }


def summarize_trace(rows: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    by_source = collections.Counter(source(row) for row in rows)
    by_kind = collections.Counter(decision_kind(row) for row in rows)
    by_player_count = collections.Counter(str(player_count(row)) for row in rows)
    candidate_counts = [len(candidates(row)) for row in rows]
    chosen_indices = [chosen_index(row) for row in rows]
    sessions = {session_id(row) for row in rows if session_id(row)}
    rows_with_token_usage = sum(1 for row in rows if has_token_usage(row))
    return {
        "rows": len(rows),
        "sessions": len(sessions),
        "byTeacherSource": dict(sorted(by_source.items())),
        "byDecisionKind": dict(sorted(by_kind.items())),
        "byPlayerCount": dict(sorted(by_player_count.items())),
        "candidateCounts": {
            "min": min(candidate_counts) if candidate_counts else 0,
            "p50": percentile(candidate_counts, 50),
            "p90": percentile(candidate_counts, 90),
            "max": max(candidate_counts) if candidate_counts else 0,
            "avg": sum(candidate_counts) / len(candidate_counts) if candidate_counts else 0.0,
        },
        "firstChoiceRatio": ratio(sum(1 for item in chosen_indices if item == 0), len(chosen_indices)),
        "invalidChoiceRows": sum(1 for item in chosen_indices if item < 0),
        "rowsWithTokenUsage": rows_with_token_usage,
        "deepseekRatio": ratio(by_source.get("deepseek", 0), len(rows)),
        "localHeuristicRatio": ratio(by_source.get("local_heuristic", 0), len(rows)),
    }


def summarize_metrics(seed_summary: Dict[str, Any], run_summary: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "runs": int_number(seed_summary.get("runs")),
        "readyRuns": int_number(seed_summary.get("readyRuns")),
        "productionReadyRuns": int_number(seed_summary.get("productionReadyRuns")),
        "validationTop1": metric_summary(seed_summary, "validationTop1", obj(run_summary.get("validation")).get("top1")),
        "validationMrr": metric_summary(seed_summary, "validationMrr", obj(run_summary.get("validation")).get("mrr")),
        "firstCandidateTop1": metric_summary(
            seed_summary,
            "firstCandidateTop1",
            obj(run_summary.get("validation")).get("firstCandidateTop1"),
        ),
        "randomExpectedTop1": metric_summary(
            seed_summary,
            "randomExpectedTop1",
            obj(run_summary.get("validation")).get("randomExpectedTop1"),
        ),
        "rankerWinRate": metric_summary(seed_summary, "rankerWinRate", obj(run_summary.get("gameplay")).get("rankerWinRate")),
        "averageRankerBoardRank": metric_summary(
            seed_summary,
            "averageRankerBoardRank",
            obj(run_summary.get("gameplay")).get("averageRankerBoardRank"),
        ),
    }


def local_training_gate(
        trace: Path,
        trace_summary: Dict[str, Any],
        readiness: Dict[str, Any],
        seed_summary: Dict[str, Any],
        metrics: Dict[str, Any],
        delivery: Dict[str, Any],
        min_local_rows: int,
        min_validation_top1: float) -> Dict[str, Any]:
    model_path = Path(str(obj(delivery.get("localArtifacts")).get("modelJson", "")))
    gameplay_path = Path(str(obj(delivery.get("localArtifacts")).get("gameplay", "")))
    checks = [
        check("trace_exists", trace.exists(), str(trace)),
        check("min_local_rows", int_number(trace_summary.get("rows")) >= min_local_rows,
              f"{trace_summary.get('rows', 0)} / {min_local_rows}"),
        check("all_decision_kinds", has_all_kinds(trace_summary), inline_json(trace_summary.get("byDecisionKind"))),
        check("readiness_local_ready", bool(readiness.get("ready")), str(readiness.get("ready"))),
        check("three_seed_summary", int_number(seed_summary.get("runs")) >= 3, f"{seed_summary.get('runs', 0)} / 3"),
        check("all_seed_runs_ready", int_number(seed_summary.get("readyRuns")) == int_number(seed_summary.get("runs"))
              and int_number(seed_summary.get("runs")) > 0,
              f"{seed_summary.get('readyRuns', 0)} / {seed_summary.get('runs', 0)}"),
        check("validation_top1_floor",
              number(obj(metrics.get("validationTop1")).get("mean")) >= min_validation_top1,
              f"{number(obj(metrics.get('validationTop1')).get('mean')):.3f} / {min_validation_top1:.3f}"),
        check("validation_beats_first_candidate",
              number(obj(metrics.get("validationTop1")).get("mean")) >
              number(obj(metrics.get("firstCandidateTop1")).get("mean")),
              f"{number(obj(metrics.get('validationTop1')).get('mean')):.3f} vs "
              f"{number(obj(metrics.get('firstCandidateTop1')).get('mean')):.3f}"),
        check("model_json_exists", model_path.exists(), str(model_path)),
        check("gameplay_json_exists", gameplay_path.exists(), str(gameplay_path)),
    ]
    return gate("local-training-ready", checks)


def production_data_gate(
        production_prefix: Path | None,
        production_readiness: Dict[str, Any],
        production_seed_summary: Dict[str, Any],
        trace_summary: Dict[str, Any],
        min_production_rows: int,
        min_rare_kind_rows: int) -> Dict[str, Any]:
    checks = [
        check("production_prefix_provided", production_prefix is not None, str(production_prefix or "")),
        check("production_readiness_ready", bool(production_readiness.get("ready")), str(production_readiness.get("ready"))),
        check("production_seed_summary_present", bool(production_seed_summary), str(bool(production_seed_summary))),
        check("production_ready_seed_runs",
              int_number(production_seed_summary.get("productionReadyRuns")) >= 1,
              f"{production_seed_summary.get('productionReadyRuns', 0)} / 1"),
        check("min_deepseek_rows",
              int_number(production_readiness.get("rows")) >= min_production_rows,
              f"{production_readiness.get('rows', 0)} / {min_production_rows}"),
        check("deepseek_source_ratio",
              ratio(int_number(obj(production_readiness.get("byTeacherSource")).get("deepseek")),
                    int_number(production_readiness.get("rows"))) >= 0.95,
              inline_json(production_readiness.get("byTeacherSource"))),
        check("token_usage_present",
              int_number(production_readiness.get("rowsWithTokenUsage")) == int_number(production_readiness.get("rows"))
              and int_number(production_readiness.get("rows")) > 0,
              f"{production_readiness.get('rowsWithTokenUsage', 0)} / {production_readiness.get('rows', 0)}"),
        check("rare_kind_rows",
              production_rare_kinds_ok(production_readiness, min_rare_kind_rows),
              f"min {min_rare_kind_rows}; counts={inline_json(production_readiness.get('byDecisionKind'))}"),
    ]
    if production_prefix is None:
        checks.append(check(
            "current_trace_is_not_production",
            False,
            f"current sources={inline_json(trace_summary.get('byTeacherSource'))}; "
            "use DeepSeek relabel or collection first",
        ))
    return gate("production-data-ready", checks)


def paper_evidence_gate(
        production_gate: Dict[str, Any],
        seed_summary: Dict[str, Any],
        matrix: Dict[str, Any],
        min_games_per_cell: int) -> Dict[str, Any]:
    by_run = list_value(matrix.get("byRun"))
    cells = {(str(row.get("players")), str(row.get("opponentStrategy"))) for row in by_run if isinstance(row, dict)}
    required_cells = {(players, opponent) for players in REPORTABLE_PLAYER_COUNTS for opponent in REPORTABLE_OPPONENTS}
    missing_cells = sorted(f"{players}-{opponent}" for players, opponent in required_cells - cells)
    low_game_cells = []
    for row in by_run:
        if not isinstance(row, dict):
            continue
        requested = int_number(row.get("gamesRequested"))
        if requested < min_games_per_cell:
            low_game_cells.append(f"{row.get('players')}-{row.get('opponentStrategy')}:{requested}/{min_games_per_cell}")
    checks = [
        check("production_data_gate_ready", bool(production_gate.get("ready")), str(production_gate.get("ready"))),
        check("three_or_more_seed_runs", int_number(seed_summary.get("runs")) >= 3, f"{seed_summary.get('runs', 0)} / 3"),
        check("all_seed_runs_production_ready",
              int_number(seed_summary.get("productionReadyRuns")) == int_number(seed_summary.get("runs"))
              and int_number(seed_summary.get("runs")) > 0,
              f"{seed_summary.get('productionReadyRuns', 0)} / {seed_summary.get('runs', 0)}"),
        check("gameplay_matrix_present", bool(matrix), str(bool(matrix))),
        check("reportable_cells_present", not missing_cells, ",".join(missing_cells) or "all cells present"),
        check("games_per_cell_floor", not low_game_cells, ",".join(low_game_cells[:8]) or "ok"),
        check("natural_completion_reported", int_number(matrix.get("completedGames")) > 0,
              f"{matrix.get('completedGames', 0)} completed"),
    ]
    return gate("paper-evidence-ready", checks)


def gate(label: str, checks: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    failures = [item for item in checks if not item["ok"]]
    return {
        "label": label,
        "ready": not failures,
        "failureCount": len(failures),
        "checks": list(checks),
        "failures": failures,
    }


def check(name: str, ok: bool, detail: str) -> Dict[str, Any]:
    return {"name": name, "ok": bool(ok), "detail": str(detail)}


def verdict(local_gate: Dict[str, Any], production_gate: Dict[str, Any], paper_gate: Dict[str, Any]) -> Dict[str, Any]:
    if paper_gate["ready"]:
        label = "paper-evidence-ready"
    elif production_gate["ready"]:
        label = "production-training-ready"
    elif local_gate["ready"]:
        label = "local-training-ready"
    else:
        label = "not-ready"
    return {
        "label": label,
        "localTrainingReady": bool(local_gate["ready"]),
        "productionDataReady": bool(production_gate["ready"]),
        "paperEvidenceReady": bool(paper_gate["ready"]),
    }


def next_actions(local_gate: Dict[str, Any], production_gate: Dict[str, Any], paper_gate: Dict[str, Any]) -> List[str]:
    actions: List[str] = []
    if not local_gate["ready"]:
        actions.append("Fix local gate failures before spending paid teacher budget.")
    if not production_gate["ready"]:
        actions.append("Run a DeepSeek paid probe or relabel pass, then collect/merge at least 5000 DeepSeek rows with token usage.")
        actions.append("Train production multi-seed students with MONOPOLY_TRAIN_SOURCES=deepseek and rerun readiness in production mode.")
    if not paper_gate["ready"]:
        actions.append("After production readiness, run a 2/3/4/5-player by easy/normal/hard gameplay matrix with at least 50 games per cell.")
        actions.append("Add scaling curves and ablations before making paper-strength claims.")
    return actions


def render_markdown(report: Dict[str, Any]) -> str:
    verdict_block = obj(report.get("verdict"))
    trace = obj(report.get("traceSummary"))
    metrics = obj(report.get("trainingMetrics"))
    gates = obj(report.get("gates"))
    lines = [
        "# Monopoly Deal AI Quality Gate",
        "",
        f"Generated: `{report.get('generatedAt', '')}`",
        "",
        "## Verdict",
        "",
        f"- Status: **{verdict_block.get('label', 'unknown')}**",
        f"- Local training ready: {verdict_block.get('localTrainingReady', False)}",
        f"- Production data ready: {verdict_block.get('productionDataReady', False)}",
        f"- Paper evidence ready: {verdict_block.get('paperEvidenceReady', False)}",
        "",
        "## Trace Summary",
        "",
        f"- Rows: {trace.get('rows', 0)}",
        f"- Sessions: {trace.get('sessions', 0)}",
        f"- Teacher sources: `{inline_json(trace.get('byTeacherSource'))}`",
        f"- Decision kinds: `{inline_json(trace.get('byDecisionKind'))}`",
        f"- Player counts: `{inline_json(trace.get('byPlayerCount'))}`",
        f"- Rows with token usage: {trace.get('rowsWithTokenUsage', 0)}",
        f"- First-choice ratio: {number(trace.get('firstChoiceRatio')):.3f}",
        "",
        "## Training Metrics",
        "",
        f"- Runs: {metrics.get('runs', 0)}",
        f"- Ready runs: {metrics.get('readyRuns', 0)}",
        f"- Production-ready runs: {metrics.get('productionReadyRuns', 0)}",
        f"- Validation top-1 mean/std: {fmt_metric(metrics.get('validationTop1'))}",
        f"- Validation MRR mean/std: {fmt_metric(metrics.get('validationMrr'))}",
        f"- Ranker win rate mean/std: {fmt_metric(metrics.get('rankerWinRate'))}",
        "",
    ]
    for key in ("localTraining", "productionData", "paperEvidence"):
        lines.extend(render_gate(key, obj(gates.get(key))))
    lines.extend([
        "## Next Actions",
        "",
    ])
    lines.extend(f"- {item}" for item in list_value(report.get("nextActions")))
    lines.append("")
    return "\n".join(lines)


def render_gate(name: str, gate_report: Dict[str, Any]) -> List[str]:
    lines = [
        f"## {name}",
        "",
        f"- Ready: {gate_report.get('ready', False)}",
        f"- Failures: {gate_report.get('failureCount', 0)}",
        "",
        "| Check | OK | Detail |",
        "|---|---:|---|",
    ]
    for row in list_value(gate_report.get("checks")):
        item = obj(row)
        detail = str(item.get("detail", "")).replace("|", "\\|")
        lines.append(f"| `{item.get('name', '')}` | {item.get('ok', False)} | {detail} |")
    lines.append("")
    return lines


def representative_prefix_from_delivery(local_prefix: Path, delivery: Dict[str, Any], seed_summary: Dict[str, Any]) -> Path:
    readiness = str(obj(delivery.get("localArtifacts")).get("readiness", ""))
    if readiness.endswith("-readiness.json"):
        path = Path(readiness)
        return path.parent / path.name[:-len("-readiness.json")]
    for row in list_value(seed_summary.get("rows")):
        item = obj(row)
        if item.get("handoffReady"):
            return Path(str(item.get("outputPrefix", "")))
    return local_prefix


def production_rare_kinds_ok(readiness: Dict[str, Any], min_rows: int) -> bool:
    counts = obj(readiness.get("byDecisionKind"))
    return all(int_number(counts.get(kind)) >= min_rows for kind in REQUIRED_KINDS if kind != "PLAY_CARD")


def metric_summary(summary: Dict[str, Any], metric: str, fallback: Any = None) -> Dict[str, float]:
    item = obj(obj(summary.get("aggregates")).get(metric))
    if item:
        return {
            "mean": number(item.get("mean")),
            "std": number(item.get("std")),
            "min": number(item.get("min")),
            "max": number(item.get("max")),
        }
    value = number(fallback)
    return {"mean": value, "std": 0.0, "min": value, "max": value}


def fmt_metric(value: Any) -> str:
    item = obj(value)
    return f"{number(item.get('mean')):.3f} / {number(item.get('std')):.3f}"


def has_all_kinds(trace_summary: Dict[str, Any]) -> bool:
    counts = obj(trace_summary.get("byDecisionKind"))
    return all(int_number(counts.get(kind)) > 0 for kind in REQUIRED_KINDS)


def load_rows(path: Path) -> List[Dict[str, Any]]:
    if not path.exists():
        return []
    rows: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            if isinstance(row, dict):
                rows.append(row)
    return rows


def load_json(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"{path}: invalid JSON: {exc}") from exc
    return value if isinstance(value, dict) else {}


def candidates(row: Dict[str, Any]) -> List[Any]:
    value = obj(row.get("request")).get("candidates")
    return value if isinstance(value, list) else []


def chosen_index(row: Dict[str, Any]) -> int:
    choice = obj(row.get("result")).get("choiceId")
    for index, candidate in enumerate(candidates(row)):
        if isinstance(candidate, dict) and candidate.get("id") == choice:
            return index
    return -1


def source(row: Dict[str, Any]) -> str:
    return str(obj(obj(row.get("result")).get("metadata")).get("source", "unknown"))


def decision_kind(row: Dict[str, Any]) -> str:
    return str(obj(row.get("request")).get("decisionKind", "unknown"))


def player_count(row: Dict[str, Any]) -> int:
    meta = obj(obj(obj(row.get("request")).get("context")).get("gameMeta"))
    return int_number(meta.get("playerCount"))


def session_id(row: Dict[str, Any]) -> str:
    request = obj(row.get("request"))
    value = request.get("sessionId")
    if value:
        return str(value)
    meta = obj(obj(request.get("context")).get("gameMeta"))
    return str(meta.get("sessionId", ""))


def has_token_usage(row: Dict[str, Any]) -> bool:
    metadata = obj(obj(row.get("result")).get("metadata"))
    return any(key in metadata for key in ["promptTokensShare", "completionTokensShare", "totalTokensShare"])


def percentile(values: List[int], pct: int) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct / 100))
    return ordered[index]


def exit_code(report: Dict[str, Any], required: str) -> int:
    if required == "none":
        return 0
    gates = obj(report.get("gates"))
    mapping = {
        "local": "localTraining",
        "production": "productionData",
        "paper": "paperEvidence",
    }
    return 0 if obj(gates.get(mapping[required])).get("ready") else 1


def inline_json(value: Any) -> str:
    return json.dumps(value if value is not None else {}, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def ratio(n: int, d: int) -> float:
    return 0.0 if d <= 0 else n / d


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
