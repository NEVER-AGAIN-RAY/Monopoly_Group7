#!/usr/bin/env python3
"""Summarize a directory of LocalRankerEvaluationRunner JSON files."""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path
from typing import Any, Dict, List, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, default=None)
    args = parser.parse_args(argv)

    rows = [compact(load_json(path), path) for path in sorted(expand_inputs(args.inputs))]
    summary = build_summary(rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(render_markdown(summary), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def expand_inputs(paths: Sequence[Path]) -> List[Path]:
    out: List[Path] = []
    for path in paths:
        if path.is_dir():
            out.extend(sorted(path.glob("*.json")))
        else:
            out.append(path)
    return out


def load_json(path: Path) -> Dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"{path}: invalid JSON: {exc}") from exc


def compact(data: Dict[str, Any], path: Path) -> Dict[str, Any]:
    return {
        "path": str(path),
        "players": int(number(data.get("players"))),
        "opponentStrategy": str(data.get("opponentStrategy", "unknown")),
        "rankerSeat": int(number(data.get("rankerSeat"))),
        "gamesRequested": int(number(data.get("gamesRequested"))),
        "completedGames": int(number(data.get("completedGames"))),
        "naturalWinRate": number(data.get("naturalWinRate")),
        "rankerWinRate": ranker_win_rate(data),
        "averageSnapshots": number(data.get("averageSnapshots")),
        "averageRankerBoardRank": number(data.get("averageRankerBoardRank")),
        "rankerBoardLeadRate": number(data.get("rankerBoardLeadRate")),
        "rankerBoardTiedLeadRate": number(data.get("rankerBoardTiedLeadRate")),
        "winsByPlayerId": obj(data.get("winsByPlayerId")),
        "endReasons": obj(data.get("endReasons")),
    }


def build_summary(rows: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    total_games = sum(int(row["gamesRequested"]) for row in rows)
    total_completed = sum(int(row["completedGames"]) for row in rows)
    weighted_natural = weighted_average(rows, "naturalWinRate", "gamesRequested")
    weighted_ranker_win = weighted_average(rows, "rankerWinRate", "gamesRequested")
    weighted_rank = weighted_average(rows, "averageRankerBoardRank", "gamesRequested")
    weighted_lead = weighted_average(rows, "rankerBoardLeadRate", "gamesRequested")
    end_reasons: Dict[str, int] = {}
    for row in rows:
        for key, value in obj(row.get("endReasons")).items():
            end_reasons[key] = end_reasons.get(key, 0) + int(number(value))
    return {
        "schema": "monopoly-deal-gameplay-matrix-v1",
        "runs": len(rows),
        "gamesRequested": total_games,
        "completedGames": total_completed,
        "naturalWinRate": weighted_natural,
        "rankerWinRate": weighted_ranker_win,
        "averageRankerBoardRank": weighted_rank,
        "rankerBoardLeadRate": weighted_lead,
        "endReasons": dict(sorted(end_reasons.items())),
        "byRun": list(rows),
        "byOpponent": group_summary(rows, "opponentStrategy"),
        "byPlayerCount": group_summary(rows, "players"),
    }


def group_summary(rows: Sequence[Dict[str, Any]], key: str) -> Dict[str, Dict[str, Any]]:
    grouped: Dict[str, List[Dict[str, Any]]] = {}
    for row in rows:
        grouped.setdefault(str(row.get(key, "unknown")), []).append(row)
    out: Dict[str, Dict[str, Any]] = {}
    for group_key, group_rows in sorted(grouped.items()):
        out[group_key] = {
            "runs": len(group_rows),
            "gamesRequested": sum(int(row["gamesRequested"]) for row in group_rows),
            "completedGames": sum(int(row["completedGames"]) for row in group_rows),
            "naturalWinRate": weighted_average(group_rows, "naturalWinRate", "gamesRequested"),
            "rankerWinRate": weighted_average(group_rows, "rankerWinRate", "gamesRequested"),
            "averageRankerBoardRank": weighted_average(group_rows, "averageRankerBoardRank", "gamesRequested"),
            "rankerBoardLeadRate": weighted_average(group_rows, "rankerBoardLeadRate", "gamesRequested"),
        }
    return out


def weighted_average(rows: Sequence[Dict[str, Any]], value_key: str, weight_key: str) -> float:
    total_weight = sum(number(row.get(weight_key)) for row in rows)
    if total_weight <= 0:
        values = [number(row.get(value_key)) for row in rows]
        return statistics.fmean(values) if values else 0.0
    return sum(number(row.get(value_key)) * number(row.get(weight_key)) for row in rows) / total_weight


def render_markdown(summary: Dict[str, Any]) -> str:
    lines = [
        "# Gameplay Matrix Report",
        "",
        f"- Runs: {summary['runs']}",
        f"- Games requested: {summary['gamesRequested']}",
        f"- Completed games: {summary['completedGames']}",
        f"- Natural win rate: {summary['naturalWinRate']:.3f}",
        f"- Ranker win rate: {summary['rankerWinRate']:.3f}",
        f"- Avg ranker board rank: {summary['averageRankerBoardRank']:.2f}",
        f"- Board lead rate: {summary['rankerBoardLeadRate']:.3f}",
        f"- End reasons: {summary['endReasons']}",
        "",
        "## By Opponent",
        "| Opponent | Runs | Games | Natural Win Rate | Ranker Win Rate | Avg Rank | Lead Rate |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for key, row in summary["byOpponent"].items():
        lines.append(table_row(key, row))
    lines.extend([
        "",
        "## By Player Count",
        "| Players | Runs | Games | Natural Win Rate | Ranker Win Rate | Avg Rank | Lead Rate |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ])
    for key, row in summary["byPlayerCount"].items():
        lines.append(table_row(key, row))
    lines.extend([
        "",
        "## Runs",
        "| Players | Opponent | Seat | Games | Completed | Natural Win Rate | Ranker Win Rate | Avg Rank | Lead Rate | Path |",
        "|---:|---|---:|---:|---:|---:|---:|---:|---:|---|",
    ])
    for row in summary["byRun"]:
        lines.append(
            f"| {row['players']} | {row['opponentStrategy']} | {row['rankerSeat']} | "
            f"{row['gamesRequested']} | {row['completedGames']} | "
            f"{row['naturalWinRate']:.3f} | {row['rankerWinRate']:.3f} | "
            f"{row['averageRankerBoardRank']:.2f} | "
            f"{row['rankerBoardLeadRate']:.3f} | `{row.get('path', '')}` |"
        )
    lines.append("")
    return "\n".join(lines)


def table_row(key: str, row: Dict[str, Any]) -> str:
    return (
        f"| {key} | {row['runs']} | {row['gamesRequested']} | "
        f"{row['naturalWinRate']:.3f} | {row['rankerWinRate']:.3f} | "
        f"{row['averageRankerBoardRank']:.2f} | "
        f"{row['rankerBoardLeadRate']:.3f} |"
    )


def ranker_win_rate(data: Dict[str, Any]) -> float:
    if "rankerWinRate" in data:
        return number(data.get("rankerWinRate"))
    games = data.get("games")
    evaluated = int(number(data.get("evaluatedGames") or data.get("gamesRequested")))
    if evaluated <= 0 or not isinstance(games, list):
        return 0.0
    wins = sum(1 for game in games if isinstance(game, dict) and bool(game.get("rankerWon")))
    return wins / evaluated


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
