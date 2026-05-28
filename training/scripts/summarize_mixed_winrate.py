#!/usr/bin/env python3
"""Summarize mixed AI battle reports by natural win rate for one team."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total <= 0:
        return (0.0, 0.0)
    p = successes / total
    denominator = 1.0 + z * z / total
    center = (p + z * z / (2.0 * total)) / denominator
    half = z * math.sqrt((p * (1.0 - p) + z * z / (4.0 * total)) / total) / denominator
    return (center - half, center + half)


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float:
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


def int_number(value: Any) -> int:
    return int(number(value))


def natural_winners(game: dict[str, Any]) -> list[dict[str, Any]]:
    if not game.get("gameOver") or game.get("forceEndReason") not in (None, ""):
        return []
    explicit_team = str(game.get("naturalWinnerTeam", "") or "").strip()
    explicit_player_id = str(game.get("naturalWinnerPlayerId", "") or "").strip()
    if explicit_team or explicit_player_id:
        for player in game.get("playersByBoardRank", []):
            if not isinstance(player, dict):
                continue
            if explicit_player_id and str(player.get("playerId", "") or "").strip() == explicit_player_id:
                return [player]
            if explicit_team and normalized_player_team(player) == explicit_team.lower():
                return [player]
    parsed = winner_from_last_action(game)
    if parsed is not None:
        return [parsed]
    winners = [
        player
        for player in game.get("playersByBoardRank", [])
        if isinstance(player, dict) and int_number(player.get("completeSets")) >= 3
    ]
    return winners if len(winners) == 1 else []


def winner_from_last_action(game: dict[str, Any]) -> dict[str, Any] | None:
    summary = str(game.get("lastActionSummary", "") or "").strip().lower()
    if not summary:
        return None
    for player in game.get("playersByBoardRank", []):
        if not isinstance(player, dict):
            continue
        display_name = str(player.get("displayName", "") or "").strip().lower()
        player_id = str(player.get("playerId", "") or "").strip().lower()
        if display_name and summary.startswith(f"{display_name} wins"):
            return player
        if player_id and summary.startswith(f"{player_id} wins"):
            return player
    return None


def team_rank(game: dict[str, Any], team: str) -> int | None:
    ranks = [
        int_number(player.get("rank"))
        for player in game.get("playersByBoardRank", [])
        if isinstance(player, dict) and player_matches_team(player, team)
    ]
    return min(ranks) if ranks else None


def normalized_player_team(player: dict[str, Any]) -> str:
    raw_team = str(player.get("team", "") or "").strip().lower()
    name = str(player.get("displayName", "") or "").strip().lower()
    player_id = str(player.get("playerId", "") or "").strip().lower()
    text = f"{raw_team} {name} {player_id}"
    if "lookahead" in text or "search" in text or "strong" in text:
        return "lookahead"
    if "hard" in text:
        return "hard"
    if "normal" in text:
        return "normal"
    if "easy" in text:
        return "easy"
    if "deepseek" in text:
        return "deepseek"
    if "openai" in text or "gpt" in text:
        return "openai"
    return raw_team or "unknown"


def player_matches_team(player: dict[str, Any], team: str) -> bool:
    return normalized_player_team(player) == team.strip().lower()


def summarize_report(path: Path, team: str) -> dict[str, Any]:
    target_team = team.strip().lower()
    report = json.loads(path.read_text(encoding="utf-8"))
    games = [game for game in report.get("games", []) if isinstance(game, dict)]
    wins = 0
    natural = 0
    forced = 0
    unknown = 0
    ranks: list[int] = []
    snapshots: list[int] = []
    end_reasons: dict[str, int] = {}
    winner_teams: dict[str, int] = {}
    for game in games:
        snapshots.append(int_number(game.get("snapshots")))
        reason = game.get("forceEndReason")
        if reason in (None, "") and game.get("gameOver"):
            reason_key = "NATURAL_WIN"
        else:
            reason_key = str(reason or "UNKNOWN")
        end_reasons[reason_key] = end_reasons.get(reason_key, 0) + 1

        rank = team_rank(game, team)
        if rank is not None:
            ranks.append(rank)
        winners = natural_winners(game)
        if winners:
            natural += 1
            winner_team = normalized_player_team(winners[0])
            winner_teams[winner_team] = winner_teams.get(winner_team, 0) + 1
            if winner_team == target_team:
                wins += 1
        elif game.get("forceEndReason"):
            forced += 1
        else:
            unknown += 1

    total = len(games)
    return {
        "path": str(path),
        "lineup": report.get("lineup", ""),
        "team": target_team,
        "games": total,
        "naturalGames": natural,
        "forcedGames": forced,
        "unknownGames": unknown,
        "teamNaturalWins": wins,
        "teamNaturalWinRate": wins / natural if natural else 0.0,
        "teamNaturalWinRate95ci": list(wilson_interval(wins, natural)),
        "teamWinsPerRequestedGame": wins / total if total else 0.0,
        "averageTeamBoardRank": statistics.mean(ranks) if ranks else 0.0,
        "medianTeamBoardRank": statistics.median(ranks) if ranks else 0.0,
        "averageSnapshots": statistics.mean(snapshots) if snapshots else 0.0,
        "endReasons": dict(sorted(end_reasons.items())),
        "naturalWinnerTeams": dict(sorted(winner_teams.items())),
    }


def combine(rows: list[dict[str, Any]], team: str) -> dict[str, Any]:
    target_team = team.strip().lower()
    total_games = sum(int(row["games"]) for row in rows)
    natural = sum(int(row["naturalGames"]) for row in rows)
    forced = sum(int(row["forcedGames"]) for row in rows)
    unknown = sum(int(row["unknownGames"]) for row in rows)
    wins = sum(int(row["teamNaturalWins"]) for row in rows)
    weighted_rank_sum = sum(float(row["averageTeamBoardRank"]) * int(row["games"]) for row in rows)
    weighted_snap_sum = sum(float(row["averageSnapshots"]) * int(row["games"]) for row in rows)
    return {
        "team": target_team,
        "reports": len(rows),
        "games": total_games,
        "naturalGames": natural,
        "forcedGames": forced,
        "unknownGames": unknown,
        "teamNaturalWins": wins,
        "teamNaturalWinRate": wins / natural if natural else 0.0,
        "teamNaturalWinRate95ci": list(wilson_interval(wins, natural)),
        "teamWinsPerRequestedGame": wins / total_games if total_games else 0.0,
        "averageTeamBoardRank": weighted_rank_sum / total_games if total_games else 0.0,
        "averageSnapshots": weighted_snap_sum / total_games if total_games else 0.0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--team", default="lookahead")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    rows = [summarize_report(path, args.team) for path in args.reports]
    result = {
        "schema": "monopoly-mixed-winrate-summary-v1",
        "primaryMetric": "team natural wins / natural-ended games",
        "team": args.team,
        "reports": rows,
        "totals": combine(rows, args.team),
    }
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
