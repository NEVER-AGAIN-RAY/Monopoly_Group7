#!/usr/bin/env python3
"""Generate a human-readable quality report for Monopoly Deal distillation data."""

from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence


REQUIRED_KINDS = ["PLAY_CARD", "JUST_SAY_NO", "PAYMENT", "OVERFLOW_DISCARD"]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--metrics", action="append", type=Path, default=[])
    parser.add_argument("--gameplay", action="append", type=Path, default=[])
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--json-output", type=Path, default=None)
    parser.add_argument("--source", default="", help="Preferred teacher source, usually deepseek.")
    parser.add_argument("--min-rows", type=int, default=5000)
    args = parser.parse_args(argv)

    rows = load_rows(args.inputs)
    metrics_list = [load_json(path) for path in args.metrics]
    gameplay_list = [load_json(path) for path in args.gameplay]
    primary_metrics = metrics_list[0] if metrics_list else {}
    report = build_report(rows, primary_metrics, args.source.strip(), args.min_rows)
    report["modelComparisons"] = [compact_metrics(metrics) for metrics in metrics_list]
    report["gameplayEvaluations"] = [compact_gameplay(gameplay) for gameplay in gameplay_list]
    markdown = render_markdown(report)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(markdown, encoding="utf-8")
    else:
        print(markdown)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


def load_rows(paths: Sequence[Path]) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                text = line.strip()
                if not text:
                    continue
                try:
                    rows.append(json.loads(text))
                except json.JSONDecodeError as exc:
                    raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
    return rows


def load_json(path: Path | None) -> Dict[str, Any]:
    if not path:
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def build_report(
    rows: Sequence[Dict[str, Any]],
    metrics: Dict[str, Any],
    preferred_source: str,
    min_rows: int,
) -> Dict[str, Any]:
    by_kind = collections.Counter(decision_kind(row) for row in rows)
    by_source = collections.Counter(teacher_source(row) for row in rows)
    sessions = {request(row).get("sessionId", "") for row in rows}
    actors = {request(row).get("actorPlayerId", "") for row in rows}
    candidate_counts = [len(request(row).get("candidates", [])) for row in rows]
    chosen_index_counts = collections.Counter(chosen_index(row) for row in rows)
    rounds = [number(game_meta(row).get("roundNumber")) for row in rows]
    player_counts = collections.Counter(int(number(game_meta(row).get("playerCount"))) for row in rows)
    actions_used = collections.Counter(int(number(game_meta(row).get("actionsUsedThisTurn"))) for row in rows)
    token_usage = token_usage_summary(rows)
    fallback_sources = {
        source: count for source, count in by_source.items()
        if source != preferred_source and (preferred_source or source != "local_heuristic")
    }

    rows_count = len(rows)
    source_rows = by_source.get(preferred_source, 0) if preferred_source else rows_count
    source_ratio = source_rows / rows_count if rows_count else 0.0
    missing_kinds = [kind for kind in REQUIRED_KINDS if by_kind.get(kind, 0) == 0]

    gates = [
        gate("min_rows", rows_count >= min_rows, f"{rows_count} / {min_rows} rows"),
        gate("all_decision_kinds", not missing_kinds, "missing " + ",".join(missing_kinds) if missing_kinds else "covered"),
    ]
    if preferred_source:
        gates.append(gate(
            "preferred_source_95pct",
            source_ratio >= 0.95,
            f"{preferred_source} {source_ratio:.1%}",
        ))
    validation = obj(metrics.get("validation"))
    if validation:
        top1 = number(validation.get("top1"))
        first = number(validation.get("firstCandidateTop1"))
        random_expected = number(validation.get("randomExpectedTop1"))
        gates.append(gate("beats_first_candidate", top1 > first, f"top1 {top1:.3f} vs first {first:.3f}"))
        gates.append(gate("beats_random", top1 > random_expected, f"top1 {top1:.3f} vs random {random_expected:.3f}"))

    candidate_summary = {
        "min": min(candidate_counts) if candidate_counts else 0,
        "p50": percentile(candidate_counts, 50),
        "p90": percentile(candidate_counts, 90),
        "max": max(candidate_counts) if candidate_counts else 0,
        "avg": sum(candidate_counts) / len(candidate_counts) if candidate_counts else 0.0,
    }
    training_ready = all(item["ok"] for item in gates)
    production_ready = training_ready and preferred_source == "deepseek"
    return {
        "rows": rows_count,
        "sessions": len(sessions),
        "actors": len(actors),
        "byKind": dict(sorted(by_kind.items())),
        "byTeacherSource": dict(sorted(by_source.items())),
        "preferredSource": preferred_source,
        "preferredSourceRatio": source_ratio,
        "fallbackSources": dict(sorted(fallback_sources.items())),
        "candidateCounts": candidate_summary,
        "chosenIndex": dict(sorted((str(k), v) for k, v in chosen_index_counts.items())),
        "roundRange": [min(rounds) if rounds else 0, max(rounds) if rounds else 0],
        "playerCounts": dict(sorted((str(k), v) for k, v in player_counts.items())),
        "actionsUsedThisTurn": dict(sorted((str(k), v) for k, v in actions_used.items())),
        "tokenUsage": token_usage,
        "metrics": compact_metrics(metrics),
        "qualityGates": gates,
        "readyForTraining": training_ready,
        "readyForProductionTraining": production_ready,
        "labelQualityTier": "deepseek_teacher" if preferred_source == "deepseek" else "local_baseline",
    }


def render_markdown(report: Dict[str, Any]) -> str:
    lines = [
        "# Distillation Data Quality Report",
        "",
        f"- Rows: {report['rows']}",
        f"- Sessions: {report['sessions']}",
        f"- Actors: {report['actors']}",
        f"- Preferred source: {report['preferredSource'] or 'all'} ({report['preferredSourceRatio']:.1%})",
        f"- Training gates passed: {'yes' if report['readyForTraining'] else 'no'}",
        f"- DeepSeek production ready: {'yes' if report['readyForProductionTraining'] else 'no'}",
        f"- Label quality tier: {report['labelQualityTier']}",
        "",
        "## Quality Gates",
    ]
    for item in report["qualityGates"]:
        mark = "PASS" if item["ok"] else "FAIL"
        lines.append(f"- {mark} `{item['name']}`: {item['detail']}")
    lines.extend(["", "## Decision Coverage"])
    for key, value in report["byKind"].items():
        lines.append(f"- `{key}`: {value}")
    lines.extend(["", "## Teacher Sources"])
    for key, value in report["byTeacherSource"].items():
        lines.append(f"- `{key}`: {value}")
    cc = report["candidateCounts"]
    lines.extend([
        "",
        "## Candidate Counts",
        f"- Avg: {cc['avg']:.2f}",
        f"- P50: {cc['p50']:.0f}",
        f"- P90: {cc['p90']:.0f}",
        f"- Min/Max: {cc['min']} / {cc['max']}",
        "",
        "## Game Shape",
        f"- Rounds: {report['roundRange'][0]:.0f} to {report['roundRange'][1]:.0f}",
        f"- Player counts: {report['playerCounts']}",
        f"- Actions used when deciding: {report['actionsUsedThisTurn']}",
        "",
        "## Token Usage",
    ])
    usage = report.get("tokenUsage", {})
    if usage.get("rowsWithUsage", 0):
        lines.extend([
            f"- Rows with usage: {usage.get('rowsWithUsage', 0)}",
            f"- Prompt token share: {usage.get('promptTokensShare', 0):.0f}",
            f"- Completion token share: {usage.get('completionTokensShare', 0):.0f}",
            f"- Total token share: {usage.get('totalTokensShare', 0):.0f}",
            f"- Avg total tokens per decision: {usage.get('avgTotalTokensPerDecision', 0):.1f}",
        ])
    else:
        lines.append("- No DeepSeek usage metadata recorded.")
    lines.extend([
        "",
        "## Metrics",
    ])
    metrics = report.get("metrics", {})
    if metrics:
        if metrics.get("splitBy"):
            split_note = str(metrics.get("splitBy"))
            if metrics.get("validationPlayerCounts"):
                split_note += f" validationPlayerCounts={metrics.get('validationPlayerCounts')}"
            lines.append(f"- Split: {split_note}")
        if metrics.get("balanceByKind"):
            lines.append(f"- Kind balance weights: {metrics.get('kindWeights', {})}")
        for split in ["train", "validation"]:
            row = metrics.get(split)
            if row:
                lines.append(
                    f"- {split}: top1={row.get('top1', 0):.3f}, "
                    f"mrr={row.get('mrr', 0):.3f}, "
                    f"first={row.get('firstCandidateTop1', 0):.3f}, "
                    f"random={row.get('randomExpectedTop1', 0):.3f}"
                )
    else:
        lines.append("- No metrics file supplied.")
    comparisons = report.get("modelComparisons", [])
    if len(comparisons) > 1:
        lines.extend([
            "",
            "## Model Comparison",
            "| Model | Validation Top1 | Validation MRR | First Baseline | Random Baseline |",
            "|---|---:|---:|---:|---:|",
        ])
        for row in comparisons:
            validation = row.get("validation", {})
            lines.append(
                f"| {row.get('modelType', 'unknown')} | "
                f"{validation.get('top1', 0):.3f} | "
                f"{validation.get('mrr', 0):.3f} | "
                f"{validation.get('firstCandidateTop1', 0):.3f} | "
                f"{validation.get('randomExpectedTop1', 0):.3f} |"
            )
    gameplay = report.get("gameplayEvaluations", [])
    if gameplay:
        lines.extend([
            "",
            "## Gameplay Evaluation",
            "| Opponent | Games | Natural Win Rate | Ranker Win Rate | Avg Snapshots | Avg Ranker Board Rank | Board Lead Rate | End Reasons |",
            "|---|---:|---:|---:|---:|---:|---:|---|",
        ])
        for row in gameplay:
            lines.append(
                f"| {row.get('opponentStrategy', 'unknown')} | "
                f"{int(row.get('gamesRequested', 0))} | "
                f"{row.get('naturalWinRate', 0):.3f} | "
                f"{row.get('rankerWinRate', 0):.3f} | "
                f"{row.get('averageSnapshots', 0):.1f} | "
                f"{row.get('averageRankerBoardRank', 0):.2f} | "
                f"{row.get('rankerBoardLeadRate', 0):.3f} | "
                f"{row.get('endReasons', {})} |"
            )
    lines.append("")
    return "\n".join(lines)


def gate(name: str, ok: bool, detail: str) -> Dict[str, Any]:
    return {"name": name, "ok": bool(ok), "detail": detail}


def compact_metrics(metrics: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for key in ["modelPath", "jsonModelPath", "modelType", "device", "inputDim", "epochs", "durationSeconds"]:
        if key in metrics:
            out[key] = metrics[key]
    for key in ["splitBy", "validationPlayerCounts", "balanceByKind", "kindBalanceMax", "kindWeights"]:
        if key in metrics:
            out[key] = metrics[key]
    for split in ["train", "validation"]:
        if split in metrics:
            row = obj(metrics[split])
            out[split] = {
                "top1": number(row.get("top1")),
                "mrr": number(row.get("mrr")),
                "firstCandidateTop1": number(row.get("firstCandidateTop1")),
                "randomExpectedTop1": number(row.get("randomExpectedTop1")),
                "decisions": int(number(row.get("decisions"))),
                "candidates": int(number(row.get("candidates"))),
                "byKind": row.get("byKind", {}),
            }
    return out


def compact_gameplay(gameplay: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for key in [
        "modelPath",
        "gamesRequested",
        "players",
        "maxSnapshotsPerGame",
        "rankerSeat",
        "opponentStrategy",
        "completedGames",
        "naturalWinRate",
        "rankerWinRate",
        "averageSnapshots",
        "averageRankerBoardRank",
        "rankerBoardLeadRate",
        "rankerBoardTiedLeadRate",
        "winsByPlayerId",
        "endReasons",
    ]:
        if key in gameplay:
            out[key] = gameplay[key]
    if "rankerWinRate" not in out:
        out["rankerWinRate"] = ranker_win_rate(gameplay)
    return out


def ranker_win_rate(gameplay: Dict[str, Any]) -> float:
    games = gameplay.get("games")
    evaluated = int(number(gameplay.get("evaluatedGames") or gameplay.get("gamesRequested")))
    if evaluated <= 0 or not isinstance(games, list):
        return 0.0
    wins = sum(1 for game in games if isinstance(game, dict) and bool(game.get("rankerWon")))
    return wins / evaluated


def token_usage_summary(rows: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    prompt = 0.0
    completion = 0.0
    total = 0.0
    rows_with_usage = 0
    for row in rows:
        metadata = obj(result(row).get("metadata"))
        if not any(key in metadata for key in ["promptTokensShare", "completionTokensShare", "totalTokensShare"]):
            continue
        rows_with_usage += 1
        prompt += number(metadata.get("promptTokensShare"))
        completion += number(metadata.get("completionTokensShare"))
        total += number(metadata.get("totalTokensShare"))
    return {
        "rowsWithUsage": rows_with_usage,
        "promptTokensShare": prompt,
        "completionTokensShare": completion,
        "totalTokensShare": total,
        "avgTotalTokensPerDecision": total / rows_with_usage if rows_with_usage else 0.0,
    }


def request(row: Dict[str, Any]) -> Dict[str, Any]:
    return obj(row.get("request"))


def result(row: Dict[str, Any]) -> Dict[str, Any]:
    return obj(row.get("result"))


def game_meta(row: Dict[str, Any]) -> Dict[str, Any]:
    return obj(obj(request(row).get("context")).get("gameMeta"))


def decision_kind(row: Dict[str, Any]) -> str:
    return str(request(row).get("decisionKind", "unknown"))


def teacher_source(row: Dict[str, Any]) -> str:
    return str(obj(result(row).get("metadata")).get("source", "unknown"))


def chosen_index(row: Dict[str, Any]) -> int:
    choice = result(row).get("choiceId")
    for index, candidate in enumerate(request(row).get("candidates", [])):
        if isinstance(candidate, dict) and candidate.get("id") == choice:
            return index
    return -1


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def percentile(values: Sequence[int], pct: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * pct / 100
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return float(ordered[low])
    ratio = rank - low
    return ordered[low] * (1 - ratio) + ordered[high] * ratio


if __name__ == "__main__":
    raise SystemExit(main())
