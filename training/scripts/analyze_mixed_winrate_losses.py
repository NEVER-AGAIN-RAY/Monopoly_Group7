#!/usr/bin/env python3
"""Analyze natural-win mixed-battle losses for one target team."""

from __future__ import annotations

import argparse
import json
import statistics
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


def normalized_team(player: dict[str, Any]) -> str:
    text = " ".join(
        str(player.get(key, "") or "").lower()
        for key in ("team", "displayName", "playerId")
    )
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
    return str(player.get("team", "") or "unknown").lower()


def natural_winner(game: dict[str, Any]) -> dict[str, Any] | None:
    if not game.get("gameOver") or game.get("forceEndReason") not in (None, ""):
        return None
    explicit_player_id = str(game.get("naturalWinnerPlayerId", "") or "").strip()
    explicit_team = str(game.get("naturalWinnerTeam", "") or "").strip().lower()
    if explicit_player_id or explicit_team:
        for player in game.get("playersByBoardRank", []):
            if not isinstance(player, dict):
                continue
            if explicit_player_id and str(player.get("playerId", "") or "").strip() == explicit_player_id:
                return player
            if explicit_team and normalized_team(player) == explicit_team:
                return player
    parsed = winner_from_last_action(game)
    if parsed is not None:
        return parsed
    winners = [
        player
        for player in game.get("playersByBoardRank", [])
        if isinstance(player, dict) and as_int(player.get("completeSets")) >= 3
    ]
    return winners[0] if len(winners) == 1 else None


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


def target_player(game: dict[str, Any], team: str) -> dict[str, Any] | None:
    players = [
        player
        for player in game.get("playersByBoardRank", [])
        if isinstance(player, dict) and normalized_team(player) == team
    ]
    players.sort(key=lambda player: as_int(player.get("rank")))
    return players[0] if players else None


def board_score(player: dict[str, Any]) -> int:
    if "boardScore" in player:
        return as_int(player.get("boardScore"))
    return (
        as_int(player.get("completeSets")) * 1000
        + as_int(player.get("propertyCount")) * 20
        + as_int(player.get("bankM")) * 10
        + as_int(player.get("handCount"))
    )


def median(values: list[float]) -> float:
    return float(statistics.median(values)) if values else 0.0


def mean(values: list[float]) -> float:
    return float(statistics.mean(values)) if values else 0.0


def numeric_stats(rows: list[dict[str, Any]], keys: list[str]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key in keys:
        values = [as_float(row.get(key)) for row in rows]
        out[key] = {
            "mean": mean(values),
            "median": median(values),
            "min": min(values) if values else 0.0,
            "max": max(values) if values else 0.0,
        }
    return out


def bucket_counts(rows: list[dict[str, Any]], key: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for row in rows:
        value = str(as_int(row.get(key)))
        out[value] = out.get(value, 0) + 1
    return dict(sorted(out.items(), key=lambda item: int(item[0])))


def bucket_by_snapshots(snapshots: int) -> str:
    if snapshots < 90:
        return "<90"
    if snapshots < 120:
        return "90-119"
    if snapshots < 150:
        return "120-149"
    if snapshots < 180:
        return "150-179"
    return "180+"


def summarize_report(path: Path, team: str) -> dict[str, Any]:
    report = json.loads(path.read_text(encoding="utf-8"))
    games = [game for game in report.get("games", []) if isinstance(game, dict)]
    rows: list[dict[str, Any]] = []
    snapshot_bucket_counts: dict[str, dict[str, int]] = {}
    for game in games:
        winner = natural_winner(game)
        target = target_player(game, team)
        if winner is None or target is None:
            continue
        winner_team = normalized_team(winner)
        outcome = "win" if winner_team == team else "loss"
        snapshots = as_int(game.get("snapshots"))
        row = {
            "game": as_int(game.get("game")),
            "outcome": outcome,
            "snapshots": snapshots,
            "targetRank": as_int(target.get("rank")),
            "targetCompleteSets": as_int(target.get("completeSets")),
            "winnerCompleteSets": as_int(winner.get("completeSets")),
            "targetPropertyCount": as_int(target.get("propertyCount")),
            "winnerPropertyCount": as_int(winner.get("propertyCount")),
            "targetBankM": as_int(target.get("bankM")),
            "winnerBankM": as_int(winner.get("bankM")),
            "targetHandCount": as_int(target.get("handCount")),
            "winnerHandCount": as_int(winner.get("handCount")),
            "targetBoardScore": board_score(target),
            "winnerBoardScore": board_score(winner),
            "targetNearCompleteColors": as_int(target.get("nearCompleteColors")),
            "winnerNearCompleteColors": as_int(winner.get("nearCompleteColors")),
            "targetMaxMissingToComplete": as_int(target.get("maxMissingToComplete")),
            "winnerMaxMissingToComplete": as_int(winner.get("maxMissingToComplete")),
            "lastActionSummary": game.get("lastActionSummary", ""),
        }
        rows.append(row)
        bucket = bucket_by_snapshots(snapshots)
        snapshot_bucket_counts.setdefault(bucket, {"win": 0, "loss": 0})
        snapshot_bucket_counts[bucket][outcome] += 1

    losses = [row for row in rows if row["outcome"] == "loss"]
    wins = [row for row in rows if row["outcome"] == "win"]
    score_gaps = [row["targetBoardScore"] - row["winnerBoardScore"] for row in losses]
    set_gaps = [row["targetCompleteSets"] - row["winnerCompleteSets"] for row in losses]
    close_losses = [
        row for row in losses
        if row["targetCompleteSets"] >= 2
        or row["targetBoardScore"] >= row["winnerBoardScore"] - 1000
    ]
    blowout_losses = [
        row for row in losses
        if row["targetCompleteSets"] <= 1 and row["winnerCompleteSets"] >= 3
    ]
    feature_keys = [
        "snapshots",
        "targetCompleteSets",
        "winnerCompleteSets",
        "targetPropertyCount",
        "winnerPropertyCount",
        "targetBankM",
        "winnerBankM",
        "targetHandCount",
        "winnerHandCount",
        "targetBoardScore",
        "winnerBoardScore",
        "targetNearCompleteColors",
        "winnerNearCompleteColors",
        "targetMaxMissingToComplete",
        "winnerMaxMissingToComplete",
    ]
    return {
        "_rows": rows,
        "path": str(path),
        "lineup": report.get("lineup", ""),
        "gamesAnalyzed": len(rows),
        "wins": len(wins),
        "losses": len(losses),
        "winRate": len(wins) / len(rows) if rows else 0.0,
        "lossSnapshots": {
            "mean": mean([row["snapshots"] for row in losses]),
            "median": median([row["snapshots"] for row in losses]),
        },
        "winSnapshots": {
            "mean": mean([row["snapshots"] for row in wins]),
            "median": median([row["snapshots"] for row in wins]),
        },
        "lossTargetMinusWinnerBoardScore": {
            "mean": mean(score_gaps),
            "median": median(score_gaps),
            "min": min(score_gaps) if score_gaps else 0,
            "max": max(score_gaps) if score_gaps else 0,
        },
        "lossTargetMinusWinnerCompleteSets": {
            "mean": mean(set_gaps),
            "median": median(set_gaps),
        },
        "closeLosses": len(close_losses),
        "blowoutLosses": len(blowout_losses),
        "lossesByTargetCompleteSets": bucket_counts(losses, "targetCompleteSets"),
        "winsByTargetCompleteSets": bucket_counts(wins, "targetCompleteSets"),
        "featureStats": {
            "wins": numeric_stats(wins, feature_keys),
            "losses": numeric_stats(losses, feature_keys),
        },
        "snapshotBuckets": snapshot_bucket_counts,
        "largestScoreGapLosses": sorted(losses, key=lambda row: row["targetBoardScore"] - row["winnerBoardScore"])[:10],
        "closestLosses": sorted(losses, key=lambda row: (
            abs(row["targetBoardScore"] - row["winnerBoardScore"]),
            -row["targetCompleteSets"],
        ))[:10],
    }


def combine(reports: list[dict[str, Any]]) -> dict[str, Any]:
    total_games = sum(as_int(report.get("gamesAnalyzed")) for report in reports)
    wins = sum(as_int(report.get("wins")) for report in reports)
    losses = sum(as_int(report.get("losses")) for report in reports)
    close_losses = sum(as_int(report.get("closeLosses")) for report in reports)
    blowout_losses = sum(as_int(report.get("blowoutLosses")) for report in reports)
    buckets: dict[str, dict[str, int]] = {}
    all_largest: list[dict[str, Any]] = []
    all_closest: list[dict[str, Any]] = []
    for report in reports:
        for bucket, counts in report.get("snapshotBuckets", {}).items():
            buckets.setdefault(bucket, {"win": 0, "loss": 0})
            buckets[bucket]["win"] += as_int(counts.get("win"))
            buckets[bucket]["loss"] += as_int(counts.get("loss"))
        all_largest.extend(report.get("largestScoreGapLosses", []))
        all_closest.extend(report.get("closestLosses", []))
    all_rows = [
        row
        for report in reports
        for row in report.get("_rows", [])
        if isinstance(row, dict)
    ]
    all_wins = [row for row in all_rows if row.get("outcome") == "win"]
    all_losses = [row for row in all_rows if row.get("outcome") == "loss"]
    feature_keys = [
        "snapshots",
        "targetCompleteSets",
        "winnerCompleteSets",
        "targetPropertyCount",
        "winnerPropertyCount",
        "targetBankM",
        "winnerBankM",
        "targetHandCount",
        "winnerHandCount",
        "targetBoardScore",
        "winnerBoardScore",
        "targetNearCompleteColors",
        "winnerNearCompleteColors",
        "targetMaxMissingToComplete",
        "winnerMaxMissingToComplete",
    ]
    return {
        "gamesAnalyzed": total_games,
        "wins": wins,
        "losses": losses,
        "winRate": wins / total_games if total_games else 0.0,
        "closeLosses": close_losses,
        "blowoutLosses": blowout_losses,
        "featureStats": {
            "wins": numeric_stats(all_wins, feature_keys),
            "losses": numeric_stats(all_losses, feature_keys),
        },
        "lossesByTargetCompleteSets": merge_count_buckets(reports, "lossesByTargetCompleteSets"),
        "winsByTargetCompleteSets": merge_count_buckets(reports, "winsByTargetCompleteSets"),
        "snapshotBuckets": buckets,
        "largestScoreGapLosses": sorted(
            all_largest,
            key=lambda row: row["targetBoardScore"] - row["winnerBoardScore"],
        )[:10],
        "closestLosses": sorted(
            all_closest,
            key=lambda row: (
                abs(row["targetBoardScore"] - row["winnerBoardScore"]),
                -row["targetCompleteSets"],
            ),
        )[:10],
    }


def merge_count_buckets(reports: list[dict[str, Any]], key: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for report in reports:
        for bucket, value in report.get(key, {}).items():
            out[str(bucket)] = out.get(str(bucket), 0) + as_int(value)
    return dict(sorted(out.items(), key=lambda item: int(item[0])))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--team", default="lookahead")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    team = args.team.strip().lower()
    reports = [summarize_report(path, team) for path in args.reports]
    result = {
        "schema": "monopoly-mixed-winrate-loss-analysis-v1",
        "team": team,
        "reports": [public_report(report) for report in reports],
        "totals": combine(reports),
    }
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


def public_report(report: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in report.items() if not key.startswith("_")}


if __name__ == "__main__":
    raise SystemExit(main())
